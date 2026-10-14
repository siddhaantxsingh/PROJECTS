# HANDOFF — 15-project engineering portfolio

> **Update, 2026-10-14 (Claude Code session): all 15 projects are done** in this monorepo (`siddhaantxsingh/PROJECTS`, branch `ccr-a6e6c567-y4w7k8`), one top-level directory each. The latest GitHub Actions run of every project workflow is green. The three earlier blockers were all resolved:
>
> * **Maven.** Maven Central became reachable, so MCP Fabric and EvalOps were built in Java.
> * **UVM.** UVM runs through pyuvm on cocotb + Verilator.
> * **GPU.** There is still no GPU. The CUDA kernels were verified on a CPU SIMT emulator and compiled to SASS with NVIDIA's ptxas from PyPI. The LLM engine and InferX run on CPU.

Workspace: `/home/user/PROJECTS` (Claude Code cloud container, 4 vCPU, 15 GiB). Each project has `README.md` and `docs/` (design, testing, benchmarks, limitations, `requirements.md` with a VERIFIED / BLOCKED / … status per requirement).

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

Numbers are from each project's `docs/` and were measured in this container. CI is the run that verified each workflow.

| # | Project | Directory | Key evidence | Main BLOCKED / not implemented |
|---|---|---|---|---|
| 10 | RAG + evaluation (Groundwork) | `groundwork/` | 72 tests; retrieval and injection benchmarks; audit | paraphrased injections undetected; lexical groundedness |
| 14 | Campus AI OS | `campus-ai-os/` | 39 API tests + browser E2E; audit | no SSO/MFA; single process |
| 6 | Vector DB | `vectordb/` | 36 tests; benchmarked against FAISS; audit (6 defects fixed) | no auth; slow mid-selectivity filters |
| 11 | RISC-V CPU | `riscv-cpu/` | riscv-tests 64/64; 250/250 random programs match Spike; CoreMark 2.87/MHz | no FPGA run |
| 9 | Distributed training | `distributed-training/` | DDP + ZeRO-1 + elastic restart; 36 tests | GPU / NCCL BLOCKED (CPU + TCP only) |
| 16 | Industrial Edge | `industrial-edge/` | Modbus + MQTT; no loss across broker outage and kill -9; 10k records/s | no real PLC hardware |
| 20 | EdgeFleet | `edgefleet/` | signed A/B OTA; staged rollouts; a bad release halted after 15/2,000 devices | no HA control plane; no TUF |
| 13 | INT8/FP8 accelerator | `int8-fp8-accelerator/` | bit-exact INT8/FP8 GEMM vs model; exact FP8 accumulation; 90.5 % utilisation | no FPGA/ASIC run |
| 18 | Nebula GPU cloud | `nebula-gpu-cloud/` | topology-aware gang scheduler (property tests); crash-safe journal; e2e with real agents; simulator | real GPUs BLOCKED (simulated); no HA |
| 8 | CUDA kernels | `cuda-kernels/` | 100 checks on CPU SIMT emulator; sm_80/sm_90 SASS via ptxas, 0 spills; modelled coalescing/bank conflicts | **GPU timing BLOCKED** (no GPU) |
| 7 | LLM inference engine | `llm-inference-engine/` | paged KV, continuous batching (3.1×), prefix cache, int8, speculative decoding; matches JAX to 1.8e-8; bit-exact across strategies | pretrained weights BLOCKED (HF unreachable; model trained here); GPU BLOCKED |
| 17 | InferX | `inferx/` | prefix-affinity routing 1.68× vs round robin on real engines; autoscaling 1→4→1, 0 failed | GPU replicas BLOCKED; single gateway |
| 15 | MCP Fabric | `mcp-fabric/` | 14 tests incl. real stdio + HTTP upstream processes; hash-chained audit; ~2.3 ms overhead | no OAuth/TLS; no sampling/elicitation forwarding |
| 19 | EvalOps | `evalops/` | 8 tests incl. bootstrap calibration; evaluates llm-inference-engine (int8 passes gate, draft fails) | no chat format / LLM judge / sandboxed code exec |
| 12 | UVM SoC verification | `uvm-soc-verification/` | pyuvm; 70/70 seeded runs; 100 % functional coverage; 12/12 injected bugs caught | SystemVerilog UVM on a commercial simulator BLOCKED |

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


### Projects completed on 2026-10-14

Each project's README and `docs/testing.md` give the full evidence. What follows notes how each was verified and what testing found.

- **RISC-V CPU:**
  - Problems Spike comparison exposed, and their fixes: counters zeroed in generated programs, and `_zicntr` added to the ISA string.
  - The interrupt test enabled interrupts too early (fixed).
- **Distributed training:** the cosine LR schedule depended on `--steps`, so `--stop-after` was added for resume tests.
- **Industrial Edge:** testing exposed and fixed these bugs (most have fail-before regression tests):
  - the swinging-door compression variant violated its error bound;
  - the health check set lastOK even on errors;
  - the uplink did not drain while disconnected.
- **EdgeFleet:** the rollback target could be missing for devices that had not yet heartbeated; enrollment now records the version.
- **INT8/FP8 accelerator:** the cycle formula is K + 3N + 3 and is checked cycle-exactly.
- **Nebula:**
  - Mutation checks run against the scheduler tests.
  - The simulator's slow-down factors are labelled as assumptions; a run without them is included.
- **CUDA kernels:**
  - ptxas showed register arrays spilling to local memory, fixed with unroll pragmas.
  - The tracer found an 8-way bank conflict and uncoalesced stores, both fixed.
  - CI needed GCC 13 headers for clang 18 CUDA mode.
- **LLM inference engine:**
  - The model was trained here (JAX, 3.0 M parameters, Python stdlib corpus). Training ran detached with checkpoint/resume, because worker restarts killed it once.
  - One mutant remains unkilled (documented).
  - The EvalOps demo found a missing SIGTERM handler, now fixed.
- **InferX:** `go test -race` found a double `exec.Cmd.Wait` (fixed). An invalid cache-benefit experiment, which used a fixed stride, was corrected.
- **MCP Fabric:** metrics export is disabled in `@SpringBootTest` by default, so the test needs `@AutoConfigureObservability`. The benchmark shows the audit fsync caps throughput: 2,255 calls/s, against 5,815 on tmpfs.
- **EvalOps:**
  - The gate rule design was fixed: a `minValue`-only rule no longer implies `maxDrop = 0`.
  - `gate.sh` exited 1 on a parse error, which a CI job would read as a failed gate. It now exits 2.
- **UVM:** the first bug-injection run caught 11 of 12 bugs. The escape (W1C on the wrong bit) revealed a stimulus hole, which was closed.

## Next steps (none required; options)

- Choose a LICENSE (owner decision pending).
- Build and publish the Docker images. This was not done: Docker Hub was blocked in the first sandbox, and nothing was deployed.
- Run on real hardware where it is BLOCKED here: GPU timings for `cuda-kernels` (`make gpu-bench`), GPU replicas for InferX and Nebula, and FPGA runs for the RTL projects.
- Items listed in each project's `docs/limitations.md`. Examples: group commit for the MCP Fabric audit log, OAuth for MCP Fabric, a cache-aware InferX autoscaler.

## Known bugs and open issues

See each project's `docs/limitations.md`. Notes for the earlier projects:

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


## Tests actually run

Every project's tests ran in this container before each commit, and again in CI (all green). Earlier records for the first three projects:

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


## Setup notes for this container

- **Go** is at `/usr/local/go/bin`.
- **cocotb 1.9.2 + pyuvm 5.0** are in a scratch venv. cocotb 2.x needs Verilator ≥ 5.036; this container has 5.020.
- **Java 21 + Maven 3.9** resolve through Maven's own proxy settings. A plain `curl` to Maven Central returned 429.
- **JAX (CPU)** was installed from PyPI to train the LLM model.
- **The CUDA toolkit pieces** (headers, ptxas, libdevice, cudart, libnvfatbin) come from NVIDIA's PyPI wheels via `cuda-kernels/tools/fetch_cuda.sh`.
- **huggingface.co and download.pytorch.org are unreachable.**
- **Never `pkill -f` with a pattern that also appears in the running command.** It kills the shell; use `pkill -x` or PIDs.

## Tracking files

The `master/` tracking files from the first sandbox are not in this repository. The per-project `docs/requirements.md` files replace them as the status record.
