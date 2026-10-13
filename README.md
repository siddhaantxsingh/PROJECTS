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

Twelve further projects from the original plan have not been started. The next ones planned are:

- RISC-V CPU
- distributed training
- Industrial Edge
- EdgeFleet
- the INT8/FP8 accelerator
- Nebula
- the CPU-only parts of the CUDA kernels, the LLM inference engine and InferX

See [HANDOFF.md](HANDOFF.md) for the full list, what blocks each project, and the exact next steps.

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
