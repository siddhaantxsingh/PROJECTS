# LLM inference engine (project 7)

A CPU inference engine for Llama-architecture models, written in Rust. It provides:

* a paged KV cache with **prefix caching**;
* **continuous batching** with chunked prefill and preemption;
* int8 weight quantisation and **speculative decoding**;
* seeded sampling (temperature, top-k, top-p, stop strings);
* an OpenAI-style HTTP server with server-sent-event streaming.

No pretrained weights were reachable from this machine (huggingface.co and the PyTorch index are blocked), so `train/` trains the model the engine serves. It is a 3.0 M-parameter, 4-layer Llama-style model, trained with JAX on the Python standard library's source, with a byte-level BPE tokenizer.

**Status: done (CPU).** All tests pass, and the benchmarks were measured on this machine. GPU execution is out of scope (no GPU; see `cuda-kernels/` for the kernel work).

## Highlights

From [docs/benchmarks.md](docs/benchmarks.md) and [docs/testing.md](docs/testing.md):

* **Matches the trainer.** The engine's held-out loss equals the JAX trainer's to 1.8·10⁻⁸ nats/token over 59k tokens. Logits match an independent float64 NumPy forward pass to under 2·10⁻⁴.
* **Batch-invariant kernels.** Every dot product sums in one fixed order, in both the AVX2 and the portable path. So batching, chunked prefill, prefix caching, preemption and speculative decoding give **bit-identical** outputs to one-at-a-time decoding, and the tests require exactly that. Freed KV blocks are poisoned with NaN, so stale cache reads cannot go unnoticed.
* **Continuous batching** raises throughput 3.1× on 4 vCPUs (610 → 1,883 output tokens/s).
* **int8 weights** are 3.2× smaller, with +0.01 % perplexity.

## Quick start

```sh
cargo build --release
./target/release/llm generate --prompt 'def fibonacci(n):' --max-tokens 60
./target/release/llm serve --listen 127.0.0.1:8080 [--q8] [--blocks 2048] [--max-seqs 64]
curl -s localhost:8080/v1/completions -d '{"prompt": "import os\n", "max_tokens": 32, "temperature": 0.8, "top_p": 0.95, "seed": 7}'
curl -sN localhost:8080/v1/completions -d '{"prompt": "class Stack:\n", "max_tokens": 32, "stream": true}'
./target/release/llm ppl --text FILE [--q8]           # held-out loss
./target/release/llm bench --text FILE                # throughput vs batch size
./target/release/llm spec --text FILE                 # speculative decoding vs greedy
```

Retrain from scratch, after `pip install jax`:

```sh
cd train
python3 build_tokenizer.py 1024
python3 train.py small 3000 artifacts/small.pkl
python3 export.py artifacts/small.pkl ../models/small.llmw
```

## Documentation

* [Design](docs/design.md): the model, the forward pass, paged KV and prefix caching, the scheduler, speculative decoding and the server.
* [Testing](docs/testing.md): reference checks, exactness across execution strategies, and mutation checks.
* [Benchmarks](docs/benchmarks.md); reproduce them with `bench/run.sh`.
* [Limitations](docs/limitations.md).
* [Requirements matrix](docs/requirements.md).
