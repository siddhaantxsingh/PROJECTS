# Projects

A portfolio of 15 production-oriented engineering projects. Each project lives in its own top-level directory, with its own README, documentation (`docs/`: design, testing, benchmarks, limitations, requirements matrix), tests and CI workflow (`.github/workflows/<project>.yml`, triggered by changes under that directory). **The latest CI run of every workflow is green.**

| Project | Directory | Stack | Status |
|---|---|---|---|
| **Groundwork**: RAG + evaluation engine | [`groundwork/`](groundwork/) | Python, FastAPI, PostgreSQL + pgvector, Redis | Done. 72 tests pass; benchmarks and audit done. |
| **Campus AI OS**: course knowledge platform | [`campus-ai-os/`](campus-ai-os/) | FastAPI backend (uses Groundwork), React + Vite | Done as a vertical slice. 39 API tests and 1 browser E2E test pass; audit done. |
| **vectordb**: ANN search engine | [`vectordb/`](vectordb/) | Rust: HNSW, PQ, SIMD, WAL + snapshots, REST + gRPC | Done. 36 tests pass; benchmarked against FAISS; audit done. |
| RISC-V CPU | [`riscv-cpu/`](riscv-cpu/) | SystemVerilog, Verilator, C++ | Done: riscv-tests 64/64, random tests match Spike, CoreMark 2.87/MHz |
| Distributed training | [`distributed-training/`](distributed-training/) | Python/NumPy, TCP | Done (CPU): DDP + ZeRO-1 + elastic restart, 36 tests |
| Industrial Edge | [`industrial-edge/`](industrial-edge/) | Go (stdlib), Modbus TCP, MQTT | Done: no loss across broker outage + kill -9; 10k records/s |
| EdgeFleet | [`edgefleet/`](edgefleet/) | Go (stdlib), Ed25519 | Done: signed A/B OTA, staged rollouts; bad release halted after 15/2,000 devices |
| INT8/FP8 accelerator | [`int8-fp8-accelerator/`](int8-fp8-accelerator/) | SystemVerilog, cocotb | Done (simulation): bit-exact INT8/FP8 GEMM, exact FP8 accumulation |
| Nebula GPU cloud | [`nebula-gpu-cloud/`](nebula-gpu-cloud/) | Rust (axum, tokio) | Done (simulated GPUs): topology-aware gang scheduling, fair share, backfill, preemption; crash-safe state |
| CUDA kernels | [`cuda-kernels/`](cuda-kernels/) | CUDA C++, clang + ptxas, CPU SIMT emulator | Done (no GPU: functional on emulator, compiled for sm_80/sm_90; GPU timing BLOCKED) |
| LLM inference engine | [`llm-inference-engine/`](llm-inference-engine/) | Rust (CPU, AVX2), JAX training | Done: paged KV, continuous batching (3.1×), prefix cache, int8, speculative decoding; model trained here |
| InferX | [`inferx/`](inferx/) | Go (stdlib) | Done: KV-cache-aware routing (1.68× vs round robin on real engines), admission control, autoscaling of engine processes |
| MCP Fabric | [`mcp-fabric/`](mcp-fabric/) | Java 21, Spring Boot 3 | Done: MCP gateway federating stdio/HTTP servers; authz, schema validation, rate limits, circuit breakers, hash-chained audit |
| EvalOps | [`evalops/`](evalops/) | Java 21, Spring Boot 3, H2 | Done: versioned datasets, eval runs, paired bootstrap comparisons, CI gates; evaluates llm-inference-engine |
| UVM SoC verification | [`uvm-soc-verification/`](uvm-soc-verification/) | SystemVerilog RTL, pyuvm, cocotb, Verilator | Done: 100 % functional coverage, 12/12 injected bugs caught |

The project statuses use these terms:

- **VERIFIED:** tested, with evidence in the repository.
- **IMPLEMENTED BUT UNVERIFIED**
- **PARTIALLY IMPLEMENTED**
- **BLOCKED:** cannot be done in this environment (e.g. no GPU).
- **NOT IMPLEMENTED**

Each project's `docs/requirements.md` gives the status per requirement, and its `docs/limitations.md` lists what is known not to work. None of the projects is deployed. The Docker images have not been built. No GPU was available, so GPU execution is BLOCKED everywhere it appears (`cuda-kernels`, `llm-inference-engine`, `inferx`, `nebula-gpu-cloud`); each says what was done instead.

See [HANDOFF.md](HANDOFF.md) for the state of the work and how each project was verified.

## Running the tests

Each project's README has details. In short:

```bash
# groundwork / campus-ai-os need PostgreSQL 16 + pgvector at postgresql://postgres@127.0.0.1:5432/postgres and Redis at localhost:6379
cd groundwork && pip install -e '.[dev]' && pytest
cd campus-ai-os && pip install -e ../groundwork -e 'backend[dev]' && (cd backend && pytest)
cd vectordb && cargo test
cd riscv-cpu && make fetch && make test            # Verilator + RISC-V GCC; make test-spike compares with Spike
cd distributed-training && pip install -e '.[dev]' && pytest
cd industrial-edge && go test ./...               # mosquitto for the MQTT end-to-end test
cd edgefleet && go test ./...
cd int8-fp8-accelerator && make test              # Verilator + cocotb 1.9
cd nebula-gpu-cloud && cargo test --release
cd cuda-kernels && make test                      # CPU SIMT emulator; tools/fetch_cuda.sh && make ptx for device code
cd llm-inference-engine && cargo test --release
cd inferx && go test -race ./...
cd mcp-fabric && mvn -B verify
cd evalops && mvn -B verify
cd uvm-soc-verification && python3 tools/regress.py --seeds 3 --bugs   # Verilator + cocotb 1.9 + pyuvm
```

Author: Siddhant Singh (siddhaantxsingh@gmail.com). No license has been chosen yet; all rights are reserved until one is added.
