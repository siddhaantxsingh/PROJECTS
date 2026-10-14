# InferX (project 17)

An inference gateway and autoscaler in front of LLM engine replicas, written in Go with only the standard library. It provides:

* **KV-cache-aware routing:** prefix-affinity consistent hashing with bounded load;
* **admission control:** a bounded wait queue, load shedding and per-tenant caps;
* **health checks**, outlier ejection and retries;
* **an autoscaler** that launches and gracefully drains real `llm-inference-engine` processes;
* **streaming passthrough** and metrics.

**Status: done (CPU).** All tests pass. The benchmarks use real `llm-inference-engine` replicas. GPU replicas are BLOCKED (no GPU here).

## Measured

From [docs/benchmarks.md](docs/benchmarks.md): 4 real engine replicas, 16 concurrent streaming clients, and 16 shared code prefixes.

| policy | requests/s | prefix-cache hit ratio | TTFT p50 |
|---|---|---|---|
| round_robin | 18.3 | 48 % | 256 ms |
| least_loaded | 22.0 | 47 % | 207 ms |
| **prefix_affinity** | **30.6** | **66 %** | **197 ms** |

Under a 2 → 24 → 2 client load step, the autoscaler went from 1 to 4 replicas and back, with 0 failed and 0 shed requests.

## Running

```sh
go build -o inferx ./cmd/inferx && ./inferx -config configs/example.json   # launches llm serve replicas
curl -N localhost:8080/v1/completions -H 'Authorization: Bearer change-me-team-a' -d '{"prompt":"import os\n","max_tokens":16,"stream":true}'
go test ./...                                    # uses the simulated engine; no binaries needed
bench/run.sh                                     # real-engine benchmark (needs ../llm-inference-engine)
```

## Documentation

* [Design](docs/design.md).
* [Testing](docs/testing.md).
* [Benchmarks](docs/benchmarks.md).
* [Limitations](docs/limitations.md).
* [Requirements matrix](docs/requirements.md).
