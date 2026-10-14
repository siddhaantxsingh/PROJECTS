//! Weights and the batched forward pass (Llama architecture: RMSNorm, RoPE,
//! grouped-query attention, SwiGLU, tied embeddings).
//!
//! The forward pass takes a flat list of tokens from any number of
//! sequences, each with its position and its sequence's KV block table. All
//! projections run as one matmul over every token in the step (each weight
//! row is read once per step); attention is per token over its own
//! sequence's cached keys and values.

use crate::kv::KvCache;
use crate::ops::{self, Mat};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Config {
    pub vocab: usize,
    pub dim: usize,
    pub layers: usize,
    pub heads: usize,
    pub kv_heads: usize,
    pub hidden: usize,
    pub max_seq: usize,
    pub rope_theta: f32,
    pub eps: f32,
}

impl Config {
    pub fn head_dim(&self) -> usize {
        self.dim / self.heads
    }
    pub fn kv_dim(&self) -> usize {
        self.kv_heads * self.head_dim()
    }
}

pub struct Layer {
    pub attn_norm: Vec<f32>,
    pub mlp_norm: Vec<f32>,
    pub wq: Mat,
    pub wk: Mat,
    pub wv: Mat,
    pub wo: Mat,
    pub w1: Mat,
    pub w3: Mat,
    pub w2: Mat,
}

pub struct Model {
    pub cfg: Config,
    pub embed: Vec<f32>,
    /// the embedding matrix used as the output projection (may be int8)
    pub lm_head: Mat,
    pub norm: Vec<f32>,
    pub layers: Vec<Layer>,
    /// RoPE cos/sin per position, [max_seq, head_dim / 2]
    rope_cos: Vec<f32>,
    rope_sin: Vec<f32>,
}

/// One token of a forward step.
#[derive(Clone, Debug)]
pub struct StepToken<'a> {
    pub token: u32,
    pub pos: usize,
    /// the sequence's KV blocks (block i holds positions i*B .. (i+1)*B)
    pub blocks: &'a [u32],
    pub want_logits: bool,
}

#[derive(Deserialize)]
struct TensorInfo {
    name: String,
    shape: Vec<usize>,
    offset: usize,
}

#[derive(Deserialize)]
struct Header {
    config: Config,
    tensors: Vec<TensorInfo>,
}

impl Model {
    pub fn load(path: &std::path::Path) -> Result<Model, String> {
        let raw = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
        if raw.len() < 12 || &raw[..4] != b"LLMW" {
            return Err("not an LLMW file".into());
        }
        let ver = u32::from_le_bytes(raw[4..8].try_into().unwrap());
        let hl = u32::from_le_bytes(raw[8..12].try_into().unwrap()) as usize;
        if ver != 1 {
            return Err(format!("unsupported version {ver}"));
        }
        let hdr: Header = serde_json::from_slice(raw.get(12..12 + hl).ok_or("truncated header")?).map_err(|e| e.to_string())?;
        let base = (12 + hl).div_ceil(64) * 64;
        let get = |name: &str, shape: &[usize]| -> Result<Vec<f32>, String> {
            let t = hdr
                .tensors
                .iter()
                .find(|t| t.name == name)
                .ok_or(format!("missing tensor {name}"))?;
            if t.shape != shape {
                return Err(format!("{name}: shape {:?}, expected {shape:?}", t.shape));
            }
            let n: usize = shape.iter().product();
            let start = base + t.offset;
            let bytes = raw.get(start..start + 4 * n).ok_or(format!("{name}: truncated"))?;
            Ok(bytes.as_chunks::<4>().0.iter().map(|b| f32::from_le_bytes(*b)).collect())
        };
        let c = &hdr.config;
        if !c.dim.is_multiple_of(c.heads) || !c.heads.is_multiple_of(c.kv_heads) || !c.head_dim().is_multiple_of(2) {
            return Err("inconsistent config".into());
        }
        let (d, hd) = (c.dim, c.head_dim());
        let mat = |name: String, r: usize, k: usize| -> Result<Mat, String> {
            Ok(Mat::F32 {
                rows: r,
                cols: k,
                w: get(&name, &[r, k])?,
            })
        };
        let mut layers = vec![];
        for i in 0..c.layers {
            let p = |k: &str| format!("layers.{i}.{k}");
            layers.push(Layer {
                attn_norm: get(&p("attn_norm"), &[d])?,
                mlp_norm: get(&p("mlp_norm"), &[d])?,
                wq: mat(p("wq"), c.heads * hd, d)?,
                wk: mat(p("wk"), c.kv_heads * hd, d)?,
                wv: mat(p("wv"), c.kv_heads * hd, d)?,
                wo: mat(p("wo"), d, c.heads * hd)?,
                w1: mat(p("w1"), c.hidden, d)?,
                w3: mat(p("w3"), c.hidden, d)?,
                w2: mat(p("w2"), d, c.hidden)?,
            });
        }
        let embed = get("embed", &[c.vocab, d])?;
        let half = hd / 2;
        let mut rope_cos = vec![0f32; c.max_seq * half];
        let mut rope_sin = vec![0f32; c.max_seq * half];
        for pos in 0..c.max_seq {
            for i in 0..half {
                // same formula as the trainer: theta^(-2i/hd), computed in f64
                let inv = 1.0 / (c.rope_theta as f64).powf((2 * i) as f64 / hd as f64);
                let a = pos as f64 * inv;
                rope_cos[pos * half + i] = a.cos() as f32;
                rope_sin[pos * half + i] = a.sin() as f32;
            }
        }
        Ok(Model {
            lm_head: Mat::F32 {
                rows: c.vocab,
                cols: d,
                w: embed.clone(),
            },
            embed,
            norm: get("norm", &[d])?,
            layers,
            rope_cos,
            rope_sin,
            cfg: hdr.config,
        })
    }

    /// Weight-only int8 quantisation of every matrix (norms and the
    /// embedding lookup stay f32; the output projection is quantised).
    pub fn quantize(&mut self) {
        for l in &mut self.layers {
            for m in [&mut l.wq, &mut l.wk, &mut l.wv, &mut l.wo, &mut l.w1, &mut l.w3, &mut l.w2] {
                *m = m.quantize();
            }
        }
        self.lm_head = self.lm_head.quantize();
    }

    pub fn weight_bytes(&self) -> usize {
        let mut n = self.lm_head.bytes() + self.embed.len() * 4;
        for l in &self.layers {
            n += [&l.wq, &l.wk, &l.wv, &l.wo, &l.w1, &l.w3, &l.w2]
                .iter()
                .map(|m| m.bytes())
                .sum::<usize>();
        }
        n
    }

    fn rope(&self, v: &mut [f32], pos: usize) {
        let half = self.cfg.head_dim() / 2;
        let (c, s) = (
            &self.rope_cos[pos * half..(pos + 1) * half],
            &self.rope_sin[pos * half..(pos + 1) * half],
        );
        for head in v.chunks_exact_mut(2 * half) {
            for i in 0..half {
                let (x0, x1) = (head[2 * i], head[2 * i + 1]);
                head[2 * i] = x0 * c[i] - x1 * s[i];
                head[2 * i + 1] = x0 * s[i] + x1 * c[i];
            }
        }
    }

    /// Run one step. Writes every token's K/V into the cache, then attends
    /// over positions 0..=pos of its sequence. Returns logits for the tokens
    /// with `want_logits`, in order.
    pub fn forward(&self, toks: &[StepToken], kv: &mut KvCache) -> Vec<Vec<f32>> {
        let c = &self.cfg;
        let (n, d, hd, kvd) = (toks.len(), c.dim, c.head_dim(), c.kv_dim());
        let group = c.heads / c.kv_heads;
        let bs = kv.block_size();
        for t in toks {
            assert!(t.pos < c.max_seq, "position {} beyond max_seq", t.pos);
            assert!(t.pos / bs < t.blocks.len(), "no KV block for position {}", t.pos);
        }
        let mut x: Vec<f32> = toks
            .iter()
            .flat_map(|t| self.embed[t.token as usize * d..(t.token as usize + 1) * d].iter().copied())
            .collect();
        let mut a = vec![0f32; n * d];
        let mut q = vec![0f32; n * d];
        let mut k = vec![0f32; n * kvd];
        let mut v = vec![0f32; n * kvd];
        let mut att = vec![0f32; n * d];
        let mut o = vec![0f32; n * d];
        let mut g = vec![0f32; n * c.hidden];
        let mut u = vec![0f32; n * c.hidden];
        let scale = 1.0 / (hd as f32).sqrt();
        for (li, l) in self.layers.iter().enumerate() {
            for i in 0..n {
                ops::rmsnorm(&x[i * d..(i + 1) * d], &l.attn_norm, c.eps, &mut a[i * d..(i + 1) * d]);
            }
            l.wq.matmul(&a, n, &mut q);
            l.wk.matmul(&a, n, &mut k);
            l.wv.matmul(&a, n, &mut v);
            for (i, t) in toks.iter().enumerate() {
                self.rope(&mut q[i * d..(i + 1) * d], t.pos);
                self.rope(&mut k[i * kvd..(i + 1) * kvd], t.pos);
                let slot = t.blocks[t.pos / bs] as usize * bs + t.pos % bs;
                kv.write(li, slot, &k[i * kvd..(i + 1) * kvd], &v[i * kvd..(i + 1) * kvd]);
            }
            let kvr = &*kv;
            att.par_chunks_mut(hd).enumerate().for_each(|(idx, out)| {
                let (i, h) = (idx / c.heads, idx % c.heads);
                let t = &toks[i];
                let qh = &q[i * d + h * hd..i * d + (h + 1) * hd];
                let kh = h / group;
                let mut scores = Vec::with_capacity(t.pos + 1);
                let mut m = f32::NEG_INFINITY;
                for p in 0..=t.pos {
                    let slot = t.blocks[p / bs] as usize * bs + p % bs;
                    let s = ops::dot(qh, kvr.key(li, slot, kh)) * scale;
                    m = m.max(s);
                    scores.push(s);
                }
                let mut sum = 0f32;
                for s in scores.iter_mut() {
                    *s = (*s - m).exp();
                    sum += *s;
                }
                out.fill(0.0);
                for (p, &w) in scores.iter().enumerate() {
                    let slot = t.blocks[p / bs] as usize * bs + p % bs;
                    let vh = kvr.value(li, slot, kh);
                    let w = w / sum;
                    for (o, &vv) in out.iter_mut().zip(vh) {
                        *o = w.mul_add(vv, *o);
                    }
                }
            });
            l.wo.matmul(&att, n, &mut o);
            for (xi, oi) in x.iter_mut().zip(&o) {
                *xi += oi;
            }
            for i in 0..n {
                ops::rmsnorm(&x[i * d..(i + 1) * d], &l.mlp_norm, c.eps, &mut a[i * d..(i + 1) * d]);
            }
            l.w1.matmul(&a, n, &mut g);
            l.w3.matmul(&a, n, &mut u);
            for (gi, ui) in g.iter_mut().zip(&u) {
                *gi = ops::silu(*gi) * ui;
            }
            l.w2.matmul(&g, n, &mut o);
            for (xi, oi) in x.iter_mut().zip(&o) {
                *xi += oi;
            }
        }
        let want: Vec<usize> = (0..n).filter(|&i| toks[i].want_logits).collect();
        if want.is_empty() {
            return vec![];
        }
        let mut hsel = vec![0f32; want.len() * d];
        for (j, &i) in want.iter().enumerate() {
            ops::rmsnorm(&x[i * d..(i + 1) * d], &self.norm, c.eps, &mut hsel[j * d..(j + 1) * d]);
        }
        let mut logits = vec![0f32; want.len() * c.vocab];
        self.lm_head.matmul(&hsel, want.len(), &mut logits);
        logits.chunks_exact(c.vocab).map(|r| r.to_vec()).collect()
    }
}
