# Requirements matrix — Project 7 (LLM inference engine)

| Requirement | Status | Evidence |
|---|---|---|
| Correct transformer forward pass (RMSNorm, RoPE, GQA, SwiGLU) | VERIFIED | NumPy float64 reference; held-out loss = JAX to 1.8e-8 |
| Tokenizer | VERIFIED | byte-identical to the Python trainer's tokenizer on held-out text |
| Paged KV cache | VERIFIED | non-contiguous blocks in reference test; block size 4/16; NaN poisoning |
| Continuous batching, chunked prefill | VERIFIED | exact-equality tests; 3.1× throughput |
| Prefix caching | VERIFIED | reuse, position/prefix correctness, eviction tests |
| Preemption under KV pressure | VERIFIED | exact outputs with a 12-block pool |
| Sampling (greedy, temperature, top-k, top-p, seeds, stop strings) | VERIFIED | sampler distribution test; seed and stop tests |
| Weight quantisation | VERIFIED | int8: 3.2× smaller, +0.01 % perplexity |
| Speculative decoding | VERIFIED | output = greedy for 3 drafts; 1.16× at k = 2 |
| OpenAI-style HTTP server with streaming, disconnect handling | VERIFIED | `tests/server.rs` |
| Pretrained open-weights model (e.g. Llama) | BLOCKED | huggingface.co unreachable; model trained here instead |
| GPU execution | BLOCKED | no GPU |
| CI | VERIFIED | `.github/workflows/llm-inference-engine.yml` (run 38560348688 green) |
