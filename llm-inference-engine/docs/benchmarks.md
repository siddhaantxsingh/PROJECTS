# Benchmarks

Produced by `bench/run.sh 2026-10-14` on a 4-vCPU cloud VM (Intel Xeon with AVX2/AVX-512; the engine uses its AVX2+FMA path), single process, 4 rayon threads. The model is the 3.0 M-parameter, 4-layer model trained in `train/` (see [design.md](design.md)); everything here is CPU.

## Accuracy

Held-out set: 59,136 tokens of Python standard-library files the model never saw, 256-token windows.

| evaluation | loss (nats/token) | perplexity | weight bytes |
|---|---|---|---|
| trainer (JAX, f32) | 1.2051337 | 3.3372 | — |
| engine, f32 | 1.2051338 | 3.3372 | 13.17 MB |
| engine, int8 weights | 1.2052032 | 3.3374 | 4.12 MB |

* The f32 engine and the JAX trainer differ by 1.8e-08 nats/token, which is float rounding. That is an end-to-end check of tokenizer, weights and forward pass at scale.
* int8 weights (per-row symmetric) cost 0.01 % perplexity. The int8 figure counts every weight matrix; the f32 embedding table used for input lookup stays alongside the quantised output projection.

## Throughput: continuous batching

64 requests, each with a 96-token prompt cut from held-out code, 128 greedy tokens each, all submitted at once. `max_seqs` caps how many sequences share a step.

| weights | max_seqs | output tokens/s | total tokens/s | wall time | mean decode batch | TTFT p50 / max |
|---|---|---|---|---|---|---|
| f32 | 1 | 610 | 1,068 | 13.4 s | 1.0 | 6.8 / 13.2 s |
| f32 | 4 | 1,169 | 2,046 | 7.0 s | 4.0 | 3.4 / 6.7 s |
| f32 | 16 | 1,744 | 3,052 | 4.7 s | 15.7 | 2.4 / 3.9 s |
| f32 | 64 | 1,883 | 3,296 | 4.3 s | 52.4 | 0.7 / 1.4 s |
| int8 | 1 | 616 | 1,078 | 13.3 s | 1.0 | 6.9 / 13.1 s |
| int8 | 16 | 1,586 | 2,775 | 5.2 s | 15.7 | 2.4 / 4.1 s |
| int8 | 64 | 1,816 | 3,179 | 4.5 s | 52.4 | 0.8 / 1.6 s |

## Speculative decoding

16 prompts (64 tokens), 128 greedy tokens each, one sequence at a time. The draft is the 1-layer, d = 128 model (held-out loss 1.79 vs 1.18). **Every speculative output was checked to equal plain greedy decoding** (the benchmark asserts it).

| mode | tokens/s | speedup | draft acceptance | target passes per token |
|---|---|---|---|---|
| plain greedy | 678 | 1.00× | — | 1.00 |
| speculative k=2 | 784 | 1.16× | 63 % | 0.45 |
| speculative k=4 | 714 | 1.05× | 52 % | 0.34 |
| speculative k=6 | 639 | 0.94× | 44 % | 0.29 |

## Reading the results

* **Batching is the main throughput lever.** Going from one sequence per step to 64 raises output throughput 3.1× (610 → 1,883 tokens/s). Each step reads every weight row once for all sequences, and per-step overheads (thread dispatch, small matmuls) are shared. The median time to first token falls with it, because queued requests start sooner.
* **int8 weights do not speed this model up.** All weights together are 13 MB and stay in this machine's cache, so the decode step is not limited by memory bandwidth, and converting int8 back to float costs extra instructions. Weight-only quantisation pays off when weights are much larger than the cache. Here it buys a 3.2× smaller footprint at no measurable loss in accuracy.
* **Speculative decoding gives a modest gain at small k.** The target needs fewer than half as many passes per token. But at batch 1 this model's step cost is dominated by fixed per-pass overhead, not by FLOPs, so the draft (about 10× fewer FLOPs) is not proportionally cheaper. Larger k loses because acceptance falls.

