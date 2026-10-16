# Projects

A portfolio of 15 engineering projects, one per top-level directory. Each has its own README, documentation (`docs/`: design, testing, benchmarks, limitations, requirements matrix, scorecard, owner actions), tests and CI workflow (`.github/workflows/<project>.yml`, triggered by changes under that directory). **The latest CI run of every workflow is green** (run ids in [master/MASTER_VERIFICATION.md](master/MASTER_VERIFICATION.md)).

Five more projects live in their own repositories and were audited at the same time: [RAFT-KV](https://github.com/siddhaantxsingh/RAFT-KV), [LSM_ENGINE](https://github.com/siddhaantxsingh/LSM_ENGINE), [TANDEM](https://github.com/siddhaantxsingh/TANDEM-Collaborative-Editor), [EMBER](https://github.com/siddhaantxsingh/EMBER-Compiler) and [VerifyCloud](https://github.com/siddhaantxsingh/VerifyCloud). Their work is on branch `ccr-a6e6c567-y4w7k8` in each repo.

**Start here:**

* [master/SCORECARD.md](master/SCORECARD.md): all 20 projects, four scores each, evidence, gaps and resume bullets backed by evidence.
* [master/RESUME_EVIDENCE.md](master/RESUME_EVIDENCE.md): every resume claim checked against the code.
* [master/OWNER_ACTION_PLAN_20_PROJECTS.pdf](master/OWNER_ACTION_PLAN_20_PROJECTS.pdf): steps only the owner can do (RTX 4060, boards, accounts, deployment).

| Project | Directory | Stack | Status (2026-10-16) |
|---|---|---|---|
| **Groundwork**: RAG + evaluation | [`groundwork/`](groundwork/) | Python, FastAPI, PostgreSQL + pgvector | 81 tests. Real nomic embeddings: hybrid retrieval NDCG@10 0.70 vs BM25 0.63; local LLM (Qwen2.5-0.5B) with grounding enforcement; injection defences |
| **Campus AI OS** | [`campus-ai-os/`](campus-ai-os/) | FastAPI, React | LLM answers with citations end to end (local model); Docker image built and smoke-tested |
| **Vector DB** | [`vectordb/`](vectordb/) | Rust: HNSW, PQ, SIMD | Benchmarked against FAISS; WAL fault injection; Docker image tested in CI |
| **RISC-V CPU** | [`riscv-cpu/`](riscv-cpu/) | SystemVerilog, Verilator | RV32IM 5-stage; riscv-tests 64/64; 400/400 random programs match Spike; I/D caches; sky130 timing (reduced flow); no FPGA run |
| **Distributed training** | [`distributed-training/`](distributed-training/) | Python/NumPy, TCP | DDP + ZeRO-1 matching PyTorch to 1e-15 (cross-check in CI); GPU BLOCKED |
| **Industrial Edge** | [`industrial-edge/`](industrial-edge/) | Go, C | INT8 fault classifier bit-exact on Cortex-M (QEMU), synthetic data; MQTT over TLS/mTLS; Docker |
| **EdgeFleet** | [`edgefleet/`](edgefleet/) | Go, Ed25519 | Signed A/B OTA, staged rollouts, release-key rotation |
| **INT8/FP8 accelerator** | [`int8-fp8-accelerator/`](int8-fp8-accelerator/) | SystemVerilog, OpenROAD | Bit-exact GEMM; PE taken to GDS on sky130 at 100 MHz; top level synthesised and placed |
| **Nebula GPU cloud** | [`nebula-gpu-cloud/`](nebula-gpu-cloud/) | Rust | Gang scheduler, crash-safe journal, hardened Docker job runtime; real GPU BLOCKED |
| **CUDA kernels** | [`cuda-kernels/`](cuda-kernels/) | CUDA C++, PTX | 150 emulator checks; tensor-core GEMM; SASS for sm_80/89/90; GPU timing BLOCKED |
| **LLM inference engine** | [`llm-inference-engine/`](llm-inference-engine/) | Rust | Runs pretrained SmolLM2-135M; matches llama.cpp; batching 3.1×; CPU only |
| **InferX** | [`inferx/`](inferx/) | Go | KV-aware routing and autoscaling; with SmolLM2 the routing gain is within noise |
| **MCP Fabric** | [`mcp-fabric/`](mcp-fabric/) | Java 21, Spring Boot | OAuth 2.1 resource server, approvals, no token passthrough, rug-pull pinning, TLS |
| **EvalOps** | [`evalops/`](evalops/) | Java 21, Python | Outbox, idempotency, leased Python workers, validated LLM judge, GitHub checks (fake API) |
| **UVM SoC verification** | [`uvm-soc-verification/`](uvm-soc-verification/) | SystemVerilog, pyuvm, SV-UVM | SVA, RAL, native SV-UVM on Verilator 5.052; 12/12 bugs caught |

Statuses are VERIFIED / IMPLEMENTED BUT UNVERIFIED / PARTIAL / BLOCKED / NOT IMPLEMENTED. Each project's `docs/requirements.md` gives the status per requirement, and `docs/limitations.md` lists what is known not to work.

Nothing is deployed. No GPU, FPGA or real MCU board was available, so those results are BLOCKED; the owner action plan gives the exact steps to run them.

See [HANDOFF.md](HANDOFF.md) for the history of the work.

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
