# HANDOFF — 15-project engineering portfolio (session of 2026-10-14)

Workspace: `/home/claude/projects/p15/` (cloud sandbox). Packaged copies live in `/mnt/user-data/outputs/p15/`: zip and git bundle per repository, plus `master/`.

## Original goal

The "Ultimate Master Prompt" asks for 15 new production-oriented repositories. It runs alongside the 5 already-finished repositories (RaftKV, LSM Engine, TANDEM, EMBER, VerifyCloud), which were not to be rebuilt. The 15 are:

- Vector DB
- LLM inference engine
- CUDA kernels
- Distributed training
- RAG + evaluation
- RISC-V CPU
- UVM SoC verification
- INT8/FP8 accelerator
- Campus AI OS
- MCP Fabric
- Industrial Edge
- InferX
- Nebula GPU cloud
- EvalOps
- EdgeFleet

Each one must pass the quality gates for correctness, reliability, security, performance, operations and evidence. The prompt also sets these rules:

- Statuses are VERIFIED / IMPLEMENTED BUT UNVERIFIED / PARTIALLY IMPLEMENTED / FAILED / BLOCKED / NOT IMPLEMENTED.
- Nothing is fabricated.
- Nothing is pushed, published, deployed or paid for without authorization.
- Commits are authored by "Siddhant Singh" <siddhaantxsingh@gmail.com>.
- Master tracking files are kept up to date in `master/`.

## Status at a glance

| # | Project | Repo | Status |
|---|---|---|---|
| 10 | RAG + Evaluation Engine ("Groundwork") | `groundwork/` HEAD `4878898` | **Done**: 72 tests pass; benchmarks, audit, docs done. Packaged. |
| 14 | Campus AI OS | `campus-ai-os/` HEAD `48261a4` | **Done** as a vertical slice: 39 API tests and 1 browser E2E pass; audit done. Packaged. No real users. |
| 6 | Vector DB (Rust) | `vectordb/` HEAD `95115a6` | **In progress.** Core library is done and tested; the REST/gRPC server compiles but is **untested**; benchmark harness, FAISS comparison and docs are not started. |
| 11, 9, 16, 20, 13, 18 | RISC-V, distributed training (CPU), Industrial Edge, EdgeFleet, accelerator RTL, Nebula | — | Not started (feasible here, with the limits listed in `master/MASTER_RESOURCE_BUDGET.md`). |
| 8, 7, 17 | CUDA kernels, LLM inference engine, InferX | — | Not started; their GPU parts are BLOCKED (no GPU or nvcc here). |
| 15, 19 | MCP Fabric, EvalOps (Java/Spring) | — | BLOCKED: Maven Central returns 403 through the sandbox proxy. |
| 12 | UVM SoC verification | — | BLOCKED: no UVM-capable simulator (only Verilator 5.020). |

Revised order (recorded in `master/MASTER_DECISIONS.md`): 10 ✓ → 14 ✓ → **6 (current)** → 11 → 9 → 16 → 20 → 13 → 18 → 8 → 7 → 17. The blocked projects (15, 19, 12) come last.

## Completed changes

### Groundwork (`groundwork/`)

Python, FastAPI, PostgreSQL + pgvector, Redis.

- **Ingestion:** Markdown, HTML, text and PDF, with pages and sections. Chunks never cross a page boundary.
- **Chunking:** three strategies — structure, sentence and fixed.
- **Retrieval, written from scratch:**
  - BM25, with incremental delete;
  - LSA and hashing embedders. No pretrained model was available because huggingface.co is blocked.
  - pgvector HNSW;
  - RRF and weighted fusion;
  - RM3 query expansion;
  - a feature-based reranker.
- **Grounded answers:**
  - an extractive provider with per-sentence citations and a grounding check;
  - an OpenAI-compatible provider, tested only against a mock transport.
- **Safety:** an injection scanner, source fencing, and an output sanitiser.
- **Service:** multi-tenant API with API keys, Redis cache and rate limiting, metrics and usage accounting.
- **Benchmark:** `pystdlib` — 634 queries over CPython docstrings, split into dev and test. Best test NDCG@10 is 0.550 (BM25 + reranker) against 0.470 for default BM25. Injection: known-style 200/200 excluded; paraphrased 0/200 excluded.
- **Audit:** 13 defects were found and fixed, each with a regression test. See `docs/audit.md`.

### Campus AI OS (`campus-ai-os/`)

FastAPI backend that uses Groundwork as a library, React + Vite frontend.

- **Accounts and access:** scrypt passwords, cookie sessions, CSRF checks. RBAC at both platform and course level. Each course is its own retrieval tenant.
- **Asking:** grounded Q&A with page citations; a research mode.
- **Study tools:** extractive flashcards and quizzes (answers stay hidden until the student submits), and document comparison.
- **Course management:** k-anonymous analytics with k=5, quotas that hold under concurrent requests, data deletion, and admin features.
- **Audit:** 10 security defects were found and fixed. The fail-before evidence is the auditor's reproductions; the pre-fix code was never committed, so the tests could not be re-run against it.
- **Browser test:** the E2E test found a stemmer bug in Groundwork, which is now fixed.

### Vector DB (`vectordb/`)

Rust 1.97. Commits `234006c` (core) and `95115a6` (WIP server).

- **`distance.rs`:** L2, inner product and cosine distances, with a scalar version and AVX2+FMA versions chosen at runtime.
- **`filter.rs`:** metadata model and filter algebra (eq / in / range / and / or / not), with depth and size limits.
- **`store.rs`:** vector slots, external ids, tombstones.
- **`hnsw.rs`:** HNSW written from scratch. Uses the heuristic neighbour selection; allow-filters are applied inside the beam search; includes an invariant checker; also provides exact search.
- **`pq.rs`:** product quantization with k-means++ and Lloyd; ADC search plus exact rerank.
- **`wal.rs`:** CRC-framed write-ahead log. A torn tail or a zero-filled tail is repaired; corruption in the middle of the log is an error.
- **`snapshot.rs`:** atomic checksummed snapshots that include the HNSW graph and the PQ data.
- **`collection.rs`:**
  - every write goes to the WAL first and is applied only after that;
  - recovery loads the snapshot, then replays the WAL;
  - filtered search: if the filter's estimated selectivity is below 2 %, it falls back to exact search; otherwise HNSW runs with a scaled-up ef;
  - also: rebuild/compaction, PQ training, stats, and `check()`.
- **Server, WIP and untested:**
  - `server.rs`: axum REST and tonic gRPC; the proto is compiled with protox in `build.rs`, so no protoc is needed. Also a Prometheus `/metrics` endpoint and collection-name validation.
  - `main.rs`: `serve` and `check` subcommands, graceful shutdown on SIGINT/SIGTERM.

## Unfinished work: exact next steps

### Vector DB (project 6, current)

1. Run `cd vectordb && cargo test --test api`. `tests/api.rs` was written but **never compiled or run**; the session was interrupted at that step. It probably needs fixes: its gRPC incoming-stream helper uses `futures_core::Stream`, and `futures-core` was added to the dev-dependencies.
2. Replace the placeholder `src/bin/bench.rs` with the real benchmark harness:
   - a deterministic Gaussian-mixture dataset (for example 100k × 128), also written out in `.fvecs` format;
   - HNSW build time; recall@10 against exact search across ef values; QPS on 1 and 2 threads; p50/p95/p99 latency;
   - memory (vector bytes, graph size, RSS from `/proc/self/status`);
   - filtered queries at 50 %, 10 % and 1 % selectivity;
   - PQ recall and memory; snapshot size and time; recovery time.
3. FAISS baseline: `pip install --break-system-packages faiss-cpu` (PyPI is reachable). Write `benchmarks/faiss_baseline.py` running `IndexHNSWFlat` with the same M, efConstruction and efSearch on the same `.fvecs`. Compare recall and QPS, and label the results as single-machine, 2 vCPU.
4. Run an adversarial audit with a subagent. **Commit before fixing anything**, so that fail-before can be re-run.
5. Write the docs: README, `docs/architecture.md`, benchmarks, testing, security, limitations, requirements matrix, ADRs; a CI workflow; a Dockerfile. Then package to `/mnt/user-data/outputs/p15/vectordb.{zip,bundle}`.
6. Known design limits to document:
   - inserts are serialized by a global write lock (searches still run concurrently);
   - tombstoned nodes are kept for navigation until `rebuild`;
   - snapshotting clones the whole state in memory;
   - after a restart, newly inserted nodes get different random levels than an uninterrupted run would (RNG reseeded from `seed ^ lsn`).

### After that

Projects 11 → 9 → 16 → 20 → 13 → 18, then the CPU-only parts of 8 / 7 / 17. Finally, the consolidated 15-project report in `master/`.

## Known bugs and open issues

- **vectordb:** the REST/gRPC server and `tests/api.rs` are unverified. `src/bin/bench.rs` is a placeholder.
- **Groundwork:**
  - paraphrased prompt injections go undetected (0/200);
  - groundedness is a lexical proxy;
  - single process;
  - BM25 tuning ended at the edge of the parameter grid.
- **Campus AI OS:**
  - analytics privacy is k-anonymity plus a 1-day membership-age rule, not differential privacy;
  - single process (per-course locks are in-memory);
  - no SSO, MFA or email verification;
  - an admin demoting the last instructor leaves the course with no instructor.
- **All repos:**
  - Dockerfiles were never built (Docker Hub is blocked);
  - CI was never run on GitHub;
  - no LICENSE file (owner decision pending).

## Tests actually run (all in this sandbox) and results

| Repo | Command | Result |
|---|---|---|
| groundwork | `pytest` (PostgreSQL 16 + pgvector 0.6 + Redis 7) | 72 passed (`test-logs/pytest-2026-10-14.txt`) |
| groundwork | `benchmarks/retrieval_bench.py … --pg …` at `163ef4a` | results in `benchmarks/results/2026-10-14/`; rendered to `docs/benchmarks.md` |
| groundwork | `benchmarks/injection_bench.py … 200` | known-style 200/200 excluded; paraphrased 0/200 excluded, 12/200 answers contaminated |
| groundwork | `scripts/demo.sh` (real uvicorn server) | OK (`test-logs/demo-2026-10-14.txt`) |
| groundwork | audit regression tests run against stashed pre-fix code | 17 of 21 failed before the fix, all pass after |
| campus-ai-os | `cd backend && pytest` | 39 passed (`test-logs/pytest-2026-10-14.txt`) |
| campus-ai-os | `cd frontend && npm run build` (strict tsc) | OK |
| campus-ai-os | `cd frontend && npx playwright test` (Chromium) | 1 passed (`test-logs/e2e-2026-10-14.txt`) |
| vectordb | `cargo test --lib` | 17 passed |
| vectordb | `cargo test --test collection` | 9 passed (recovery, crash at many WAL byte offsets, corrupt snapshot, rebuild, PQ, filter property test, concurrency) |
| vectordb | `cargo build --release` (with the server) | compiles, no warnings |
| vectordb | `cargo test --test api` | **NOT RUN** |

## Setup commands

```bash
# services (the sandbox needed these restarted after the container restarted)
pg_ctlcluster 16 main start                 # PostgreSQL 16; apt-get install postgresql-16-pgvector if missing
redis-server --daemonize yes
export PATH=$PATH:/usr/local/go/bin

# groundwork
cd groundwork && pip install --break-system-packages -e '.[dev]' && pytest
python3 benchmarks/retrieval_bench.py benchmarks/results/<date> --pg postgresql://postgres@127.0.0.1:5432/postgres
python3 benchmarks/render_md.py <date>

# campus-ai-os (needs groundwork installed editable)
cd campus-ai-os && pip install --break-system-packages -e ../groundwork -e 'backend[dev]'
cd backend && pytest
cd ../frontend && npm ci && npm run build && npx playwright test   # Chromium at /opt/pw-browsers/chromium-1194 (override with CHROMIUM_PATH)

# vectordb
cd vectordb && cargo test --lib && cargo test --test collection && cargo test --test api
cargo run --release -- serve --data ./data --http 127.0.0.1:6333 --grpc 127.0.0.1:6334
```

On your own machine, restore a repository from its bundle:

```bash
git clone <name>.bundle <name>
```

The bundles are in the outputs folder `p15/`.

## Environment constraints

- 2 vCPU, 7.8 GiB RAM, about 7 GB free disk; no GPU.
- **Reachable:** PyPI, npm, crates.io, proxy.golang.org.
- **Blocked (HTTP 403):** Maven Central, Hugging Face, Docker Hub, GitHub.
- The weekly usage allowance is not visible to the agent, so no figures are recorded.

## Tracking files

`master/MASTER_PROGRESS.md`, `MASTER_TASKS.md`, `MASTER_DECISIONS.md`, `MASTER_BLOCKERS.md`, `MASTER_VERIFICATION.md`, `MASTER_RESOURCE_BUDGET.md`. They are current through project 14. Add a project 6 entry once the server tests pass.
