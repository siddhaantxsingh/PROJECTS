# Projects

A portfolio of production-oriented engineering projects. Each project lives in its own top-level directory, with its own README, documentation, tests and CI workflow (`.github/workflows/<project>.yml`, triggered by changes under that directory).

| Project | Directory | Stack | Status |
|---|---|---|---|
| **Groundwork**: RAG + evaluation engine | [`groundwork/`](groundwork/) | Python, FastAPI, PostgreSQL + pgvector, Redis | Done. 72 tests pass; benchmarks and audit done. |
| **Campus AI OS**: course knowledge platform | [`campus-ai-os/`](campus-ai-os/) | FastAPI backend (uses Groundwork), React + Vite | Done as a vertical slice. 39 API tests and 1 browser E2E test pass; audit done. |
| **vectordb**: ANN search engine | [`vectordb/`](vectordb/) | Rust: HNSW, PQ, SIMD, WAL + snapshots, REST + gRPC | Done. 36 tests pass; benchmarked against FAISS; audit done. |

The project statuses use these terms:

- **VERIFIED:** tested, with evidence in the repository.
- **IMPLEMENTED BUT UNVERIFIED**
- **PARTIALLY IMPLEMENTED**
- **BLOCKED**
- **NOT IMPLEMENTED**

Each project's `docs/requirements.md` gives the status per requirement, and its `docs/limitations.md` lists what is known not to work. None of the projects is deployed. The Docker images have not been built.

Projects still being built (in build order):

| Project | Directory | Stack | Status |
|---|---|---|---|
| RISC-V CPU | [`riscv-cpu/`](riscv-cpu/) | SystemVerilog, Verilator, C++ | Done: riscv-tests 64/64, random tests match Spike, CoreMark 2.87/MHz |
| Distributed training | [`distributed-training/`](distributed-training/) | Python/NumPy, TCP | Done (CPU): DDP + ZeRO-1 + elastic restart, 36 tests |
| Industrial Edge | [`industrial-edge/`](industrial-edge/) | Go (stdlib), Modbus TCP, MQTT | Done: no loss across broker outage + kill -9; 10k records/s |
| EdgeFleet | [`edgefleet/`](edgefleet/) | Go (stdlib), Ed25519 | Done: signed A/B OTA, staged rollouts; bad release halted after 15/2,000 devices |
| INT8/FP8 accelerator | [`int8-fp8-accelerator/`](int8-fp8-accelerator/) | | Not started |
| Nebula GPU cloud | [`nebula-gpu-cloud/`](nebula-gpu-cloud/) | | Not started |
| CUDA kernels | [`cuda-kernels/`](cuda-kernels/) | | Not started (no GPU here) |
| LLM inference engine | [`llm-inference-engine/`](llm-inference-engine/) | | Not started |
| InferX | [`inferx/`](inferx/) | | Not started |
| MCP Fabric | [`mcp-fabric/`](mcp-fabric/) | Java / Spring | Not started (Maven is reachable now) |
| EvalOps | [`evalops/`](evalops/) | Java / Spring | Not started (Maven is reachable now) |
| UVM SoC verification | [`uvm-soc-verification/`](uvm-soc-verification/) | | Not started |

See [HANDOFF.md](HANDOFF.md) for the plan and what blocks each project.

## Running the tests

```bash
# services for groundwork / campus-ai-os
#   PostgreSQL 16 with pgvector at postgresql://postgres@127.0.0.1:5432/postgres, and Redis at localhost:6379

cd groundwork && pip install -e '.[dev]' && pytest
cd campus-ai-os && pip install -e ../groundwork -e 'backend[dev]' && (cd backend && pytest)
cd campus-ai-os/frontend && npm ci && npm run build && npx playwright test
cd vectordb && cargo test
```

Author: Siddhant Singh (siddhaantxsingh@gmail.com). No license has been chosen yet; all rights are reserved until one is added.
