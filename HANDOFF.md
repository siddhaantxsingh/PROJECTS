# HANDOFF — 15-project engineering portfolio

> **Update, 2026-10-13 (Claude Code session):** this session finished the vector DB (project 6) and published the three finished projects to GitHub, in `siddhaantxsingh/PROJECTS` on branch `ccr-a6e6c567-y4w7k8`, as one monorepo with one top-level directory per project. Sections marked *(updated)* below reflect this session. Two things were not available to this session: the per-repo git bundles, so the earlier commit history was **not** carried over (the first commit imports the packaged working trees), and the `master/` tracking files, so the status table below serves as the tracker.

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
| 6 | Vector DB (Rust) | `vectordb/` | **Done** *(updated)*: 36 tests pass, including the server end-to-end tests; benchmarked at 100k × 128 against FAISS; audit found 6 defects, all fixed with regression tests; docs, ADRs, CI and Dockerfile written. |
| 11, 9, 16, 20, 13, 18 | RISC-V, distributed training (CPU), Industrial Edge, EdgeFleet, accelerator RTL, Nebula | — | Not started (feasible here, with the limits listed in `master/MASTER_RESOURCE_BUDGET.md`). |
| 8, 7, 17 | CUDA kernels, LLM inference engine, InferX | — | Not started; their GPU parts are BLOCKED (no GPU or nvcc here). |
| 15, 19 | MCP Fabric, EvalOps (Java/Spring) | — | BLOCKED: Maven Central returns 403 through the sandbox proxy. |
| 12 | UVM SoC verification | — | BLOCKED: no UVM-capable simulator (only Verilator 5.020). |

Revised order (recorded in `master/MASTER_DECISIONS.md`): 10 ✓ → 14 ✓ → 6 ✓ → **11 (next)** → 9 → 16 → 20 → 13 → 18 → 8 → 7 → 17. The blocked projects (15, 19, 12) come last.

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
- **Server** (tested as of 2026-10-13; see `vectordb/docs/testing.md`):
  - `server.rs`: axum REST and tonic gRPC; the proto is compiled with protox in `build.rs`, so no protoc is needed. Also a Prometheus `/metrics` endpoint and collection-name validation.
  - `main.rs`: `serve` and `check` subcommands, graceful shutdown on SIGINT/SIGTERM.

## Unfinished work: exact next steps *(updated)*

### Vector DB (project 6): done

What this session did:

- Made the server tests pass. The only compile error was a missing `IntoFuture` import. The servers now also shut down gracefully, so the restart test restarts for real.
- Added an exclusive `LOCK` file per collection. Before this, two handles could append to the same WAL.
- Wrote the real `vdb-bench` harness and `benchmarks/faiss_baseline.py`. Results are in `vectordb/benchmarks/results/2026-10-13/` and are rendered to `vectordb/docs/benchmarks.md`. The first version of the harness drew its queries from different cluster centres than the base set; this was found and fixed before publishing.
- Ran the adversarial audit with a subagent:
  - the reproductions were committed while still failing, with the log in `test-logs/audit-fail-before.txt`;
  - the defects were then fixed;
  - details are in `vectordb/docs/audit.md`.

Optional follow-ups, all documented as limitations:

- a cost-based filter planner and a columnar metadata index (filtered search at 1–10 % selectivity takes about 13–18 ms);
- an IVF coarse quantizer for PQ;
- fault-injection tests for the WAL rollback path;
- real datasets such as SIFT1M.

### Next: projects 11 → 9 → 16 → 20 → 13 → 18

After those come the CPU-only parts of 8, 7 and 17, and then the consolidated 15-project report. Add each project as a new top-level directory in this monorepo, with its own `.github/workflows/<name>.yml` that uses `paths:` filters, as in the existing workflows.

### GitHub / CI *(updated)*

- CI now runs on GitHub Actions for all three projects.
- The first runs exposed two problems, both fixed:
  - the Python suites imported `tests.*`, which only resolved under `python -m pytest` (fixed by setting `pythonpath = ["."]`);
  - GitHub's newer stable clippy (1.99) added a lint that the local 1.97 toolchain lacked.

## Known bugs and open issues

- **vectordb** *(updated)*: see `vectordb/docs/limitations.md`. The main items:
  - no authentication;
  - slow filtered search at mid and low selectivity;
  - maintenance operations block traffic;
  - no fault-injection tests.
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
  - CI runs on GitHub and is green for all three projects *(updated)*;
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
| vectordb | `cargo test --test api` *(updated, 2026-10-13)* | 2 passed |
| vectordb | `cargo test` (lib 17, api 2, collection 9, audit 7, audit_pq_rerank 1) *(2026-10-13)* | 36 passed (`vectordb/test-logs/cargo-test-after-audit.txt`) |
| vectordb | audit reproductions against the pre-fix code *(2026-10-13)* | 5 failed and 1 aborted the process (`vectordb/test-logs/audit-fail-before.txt`) |
| vectordb | `vdb-bench` with the `lowrank` and `mixture` datasets, plus `faiss_baseline.py` (faiss-cpu 1.15.1) *(2026-10-13)* | `vectordb/benchmarks/results/2026-10-13/` |
| vectordb | clippy `-D warnings` on 1.97 and 1.99; build on MSRV 1.89 *(2026-10-13)* | clean / OK |
| groundwork | `pytest` re-run in the Claude Code container (PG 16.15 + pgvector 0.6.0 + Redis 7.0) *(2026-10-13)* | 72 passed |
| campus-ai-os | backend `pytest`, `npm run build`, `npx playwright test` re-run *(2026-10-13)* | 39 passed; build OK; 1 passed |

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
- **Blocked (HTTP 403):** Maven Central, Hugging Face, Docker Hub, GitHub. This applied to the earlier sandbox. The Claude Code container used on 2026-10-13 can push to GitHub and has 4 vCPUs and 15 GiB RAM.
- The weekly usage allowance is not visible to the agent, so no figures are recorded.

## Tracking files

`master/MASTER_PROGRESS.md`, `MASTER_TASKS.md`, `MASTER_DECISIONS.md`, `MASTER_BLOCKERS.md`, `MASTER_VERIFICATION.md`, `MASTER_RESOURCE_BUDGET.md`. They are current through project 14. Add a project 6 entry once the server tests pass.
