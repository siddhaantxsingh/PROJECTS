//! Byte-level BPE, identical to `train/tokenizer.py` (checked against its
//! output on held-out text in `tests/reference.rs`).

use std::collections::HashMap;

pub struct Tokenizer {
    rank: HashMap<(u32, u32), u32>,
    bytes: Vec<Vec<u8>>,
}

fn is_word(b: u8) -> bool {
    b >= 0x80 || b == b'_' || b.is_ascii_alphanumeric()
}

/// Pre-tokenizer: merges never cross these chunk boundaries.
pub fn chunks(data: &[u8]) -> Vec<&[u8]> {
    let (mut out, mut i, n) = (vec![], 0, data.len());
    while i < n {
        let c = data[i];
        let mut j;
        if c == b' ' && i + 1 < n && is_word(data[i + 1]) {
            j = i + 1;
            while j < n && is_word(data[j]) {
                j += 1;
            }
        } else if is_word(c) {
            j = i;
            while j < n && is_word(data[j]) {
                j += 1;
            }
        } else if c == b' ' {
            j = i;
            while j < n && data[j] == b' ' {
                j += 1;
            }
            if j < n && is_word(data[j]) && j - i > 1 {
                j -= 1;
            }
        } else if c == b'\n' {
            j = i;
            while j < n && data[j] == b'\n' {
                j += 1;
            }
        } else {
            j = i + 1;
        }
        out.push(&data[i..j]);
        i = j;
    }
    out
}

impl Tokenizer {
    pub fn from_json(s: &str) -> Result<Tokenizer, String> {
        let v: serde_json::Value = serde_json::from_str(s).map_err(|e| e.to_string())?;
        let merges: Vec<(u32, u32)> = serde_json::from_value(v["merges"].clone()).map_err(|e| e.to_string())?;
        let mut bytes: Vec<Vec<u8>> = (0..=255u8).map(|b| vec![b]).collect();
        let mut rank = HashMap::new();
        for (i, &(a, b)) in merges.iter().enumerate() {
            let mut t = bytes[a as usize].clone();
            t.extend_from_slice(&bytes[b as usize]);
            bytes.push(t);
            rank.insert((a, b), i as u32);
        }
        Ok(Tokenizer { rank, bytes })
    }

    pub fn vocab_size(&self) -> usize {
        self.bytes.len()
    }

    fn encode_chunk(&self, w: &[u8], out: &mut Vec<u32>) {
        let mut t: Vec<u32> = w.iter().map(|&b| b as u32).collect();
        while t.len() > 1 {
            // lowest-rank adjacent pair; ties go to the leftmost
            let mut best: Option<(u32, usize)> = None;
            for i in 0..t.len() - 1 {
                if let Some(&r) = self.rank.get(&(t[i], t[i + 1]))
                    && best.is_none_or(|(br, _)| r < br)
                {
                    best = Some((r, i));
                }
            }
            let Some((r, i)) = best else { break };
            t[i] = 256 + r;
            t.remove(i + 1);
        }
        out.extend_from_slice(&t);
    }

    pub fn encode(&self, data: &[u8]) -> Vec<u32> {
        let mut out = vec![];
        for c in chunks(data) {
            self.encode_chunk(c, &mut out);
        }
        out
    }

    pub fn token_bytes(&self, id: u32) -> &[u8] {
        &self.bytes[id as usize]
    }

    pub fn decode(&self, ids: &[u32]) -> Vec<u8> {
        ids.iter().flat_map(|&i| self.bytes[i as usize].iter().copied()).collect()
    }
}
