# Limitations

* **A small model trained here.**
  * 3.0 M parameters on 4.3 M tokens of Python source. It writes plausible-looking Python but is not useful as an assistant.
  * The engine reads its own `LLMW` format. It cannot load GGUF or safetensors, so no pretrained checkpoint has been run through it.
* **CPU only.** The kernels are AVX2+FMA with a portable fallback. There is no AVX-512/AMX path, no GPU, and no tensor parallelism.
* **Weight-only int8.** Activations stay f32. No 4-bit or group-wise quantisation, and no int8 KV cache.
* **Attention cost.** Attention is computed per (token, head) over the whole cached prefix. There is no FlashAttention-style tiling on CPU, so long contexts would be slow; the trained context is 512.
* **Preemption recomputes.** Preempted sequences are recomputed rather than swapped out. Recomputation is exact, but it repeats the work.
* **Speculative decoding** is greedy-only and single-sequence. It is not integrated into the batched scheduler; it is a separate API used by `llm spec`.
* **The server** has no authentication, rate limiting or TLS (see `inferx/` for a gateway). Only `/v1/completions` is supported, not chat completions.
