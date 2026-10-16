# 20-project scorecard (2026-10-16)

Each project has four scores from 0 to 10:

* **P:** fresher-portfolio strength;
* **M:** engineering maturity;
* **D:** deployment readiness;
* **S:** completion of the intended scope in the owner's directive.

Scores come from each project's own audit or scorecard file, which gives the "why not higher" line for each. No project scores 9.5+. That level needs evidence nobody can produce here: real hardware, real users, an idle benchmark machine and independent review.

**Confidence** is how far the evidence can be reproduced:

* **High:** CI reruns the key checks on every push.
* **Medium:** the key numbers come from runs on one shared, overloaded machine.
* **Low:** the key claim depends on something not yet run (a GPU, a real device, a real account).

All work is on branch `ccr-a6e6c567-y4w7k8` in each repository. Nothing is merged to `main`.

**Averages over the 20 projects:** portfolio 7.8, maturity 7.1, deployment 4.1, scope 7.2.

* Deployment readiness is low on purpose. Nothing is deployed, and every real-hardware, real-account and real-user step is the owner's (see `OWNER_ACTION_PLAN_20_PROJECTS.pdf`).
* The fastest way to raise scores is to run the RTX 4060 scripts (CUDA kernels, Nebula, distributed training) and the Cortex-M board test.

## Standalone repositories

| # | Project | P | M | D | S | Confidence | Main evidence | Main gap | CI run (green) |
|---|---|---|---|---|---|---|---|---|---|
| 1 | RaftKV (Go) | 8 | 7 | 4 | 7 | High | Single-server membership changes; network-fault chaos between real processes: 7/7 runs linearizable; 5-node failover p50 360 ms over 45 trials; WAL and fsync bugs fixed | Joint consensus, backup/restore; one negative control does not fire | 38740044982 |
| 2 | LSM Engine (Rust) | 8 | 7 | 3 | 8 | High | 2 critical bugs fixed (an acknowledged-write race; a corrupt MANIFEST that deleted live tables); 6,055 simulated crash images; 9,247 bit flips with 0 silent corruption; C ABI clean under ASan, valgrind and Miri; on par with RocksDB on a small single-thread test | No fuzzing; on-disk format changed; no real power-loss test | 38740008581 |
| 3 | TANDEM (TypeScript/Yjs) | 8 | 7.5 | 5 | 7.5 | High | Acknowledged-but-unsaved edit bug found and fixed; sessions revoked across nodes; kill -9 of server and PostgreSQL: 3,706 acknowledged edits, 0 lost; 200/200 offline convergence runs | Not deployed; latency measured on an overloaded machine; no OT (the resume claim is false) | 38707130851 |
| 4 | EMBER (C++/LLVM) | 8 | 7 | 5 | 7 | High | LLVM 18 ORC JIT backend; output identical to the VM on 68 programs and 3,000 generated ones; 2.2–10.8× faster; about 900k fuzz executions with no crash | No GC in the native backend; no AOT output | 38740180634 |
| 5 | VerifyCloud (TypeScript/Fastify) | 8 | 7 | 5.5 | 7.5 | High | CI that had never run now runs; 65 test failures against real S3 fixed; lease fencing tested with SIGKILL, SIGSTOP and clock skew; sandbox-escape tests; Compose stack, MinIO and gVisor verified | Disk budget can be exceeded (F6); real GitHub App and LLM blocked | 38740646066 |

## PROJECTS monorepo

| # | Project | P | M | D | S | Confidence | Main evidence | Main gap | CI run (green) |
|---|---|---|---|---|---|---|---|---|---|
| 6 | Vector DB (Rust) | 8 | 8 | 4 | 8 | High | HNSW/PQ/SIMD, filtered search, benchmarked against FAISS; WAL fault injection (injected errors plus a real EFBIG); Docker image built and smoke-tested in CI | Synthetic 100k datasets only (no 1M or SIFT); no auth or replication | 38863621885 |
| 7 | LLM inference engine (Rust) | 8 | 7 | 4 | 7 | High | Runs the pretrained SmolLM2-135M; logits within 4e-5 of an fp64 reference; greedy output identical to llama.cpp; perplexity 18.8685 vs llama.cpp's 18.8695; batching 3.1×; prefix cache cuts time to first token 25× | No GPU path; k-quants not supported; Qwen tested only on a tiny synthetic file | 38739671965 |
| 8 | CUDA kernels | 7 | 7 | 3 | 6 | Low (GPU) / High (emulator) | 150 emulator checks; fp16 tensor-core GEMM (mma.sync, ldmatrix); HMMA in SASS for sm_80/89/90 with 0 spills; softmax and LayerNorm backward | **No GPU run**; no cuBLAS comparison | 38821227301 |
| 9 | Distributed training (Python) | 8 | 7 | 3 | 7 | High | Matches PyTorch DDP to 1.6e-15 in gradients and ZeroRedundancyOptimizer to 3.9e-14 in parameters; bit-identical resume; the cross-check runs in CI | No GPU or NCCL; PyTorch is 2–3× faster | 38861771722 |
| 10 | Groundwork RAG (Python) | 8 | 7 | 5 | 7 | Medium | Real nomic embeddings: hybrid NDCG@10 0.70 vs BM25 0.63 (p = 0.005); grounding checker; 0/10 injections succeed with all defences | Small LLMs ground answers poorly; the neural benchmark used a sub-corpus | 38820295035 |
| 11 | RISC-V CPU (SystemVerilog) | 8 | 7 | 3 | 7 | High (simulation) / Medium (timing) | RV32IM 5-stage; riscv-tests 64/64; 400/400 random programs match Spike; I/D caches (direct-mapped or 2-way, write buffer) at memory latency 1–50; 9/9 injected cache bugs caught; single-cycle baseline; reduced OpenROAD flow: pipeline 43.5 MHz vs single-cycle 38.5 MHz | **No FPGA run**; full place and route blocked by disk; the multiplier limits the pipeline, so CoreMark/s is near parity with single-cycle | 38862838902 |
| 12 | UVM SoC verification | 8 | 7 | 6 | 8 | High | 23 SVA properties, each testable one fired on its injected bug; register model generated from one spec; native SV-UVM on Verilator 5.052 with UVM 2020.3.1 runs in CI; 12/12 bugs caught, 82/82 coverage | No commercial simulator; no-X properties untested | 38822290359 |
| 13 | INT8/FP8 accelerator | 8 | 7 | 2 | 6 | Medium | Single processing element taken RTL→GDS on sky130 at 100 MHz with 0 route DRC and a formally equivalent final netlist; FP8 costs 3.5× area; synthesised 4×4 top level matches the golden model in gate-level simulation | Top-level CTS, routing and GDS not finished; no DRC/LVS | 38739244942 |
| 14 | Campus AI OS (FastAPI/React) | 7 | 7 | 5 | 7 | Medium | LLM answers with citations end to end (was BLOCKED); Docker image built and smoke-tested | No real users; not deployed | 38820295140 |
| 15 | MCP Fabric (Java/Spring) | 8 | 7 | 5 | 8 | High | OAuth 2.1 resource server (JWKS rotation, protected-resource metadata); approvals; no token passthrough (byte-level test); rug-pull pinning; TLS; 33 tests | No real IdP run; no introspection; benchmark inconclusive | 38782465392 |
| 16 | Industrial Edge (Go + C) | 8 | 7 | 4 | 7 | Medium | INT8 classifier bit-exact across C, Go and Python, also on Cortex-M4/M3 in QEMU; 96.7 % on held-out speeds (synthetic data); predictive maintenance F1 0.92; MQTT over TLS with mutual auth; Docker | No real board or real data; one flaky end-to-end test under load | 38705880336 |
| 17 | InferX (Go) | 7 | 7 | 4 | 7 | Medium | Autoscaling 1→3→1 with 0 failures on SmolLM2 | With SmolLM2 the routing gain is within noise (the earlier 1.68× was with a tiny model); no GPU | 38861505199 |
| 18 | Nebula GPU cloud (Rust) | 8 | 7 | 4 | 7 | Medium | Gang scheduler with property tests; crash-safe journal; hardened Docker runtime (limits, OOM, cancel, orphan cleanup) tested against a real daemon; nvidia-smi detection | **Real GPU pass-through not run**; no mTLS or HA | 38822614992 |
| 19 | EvalOps (Java + Python) | 8 | 7 | 4 | 7 | High | Transactional outbox; idempotency keys; leased Python workers with fencing tokens (tested by killing workers); GitHub checks against a fake API; judge validation with κ | The 135M judge is at chance; no real GitHub App | 38861505080 |
| 20 | EdgeFleet (Go) | 8 | 7 | 3 | 7 | High | Signed A/B OTA; staged rollout halted a bad release after 15/2,000 devices; release-key rotation with expiry, revocation and rollback protection; tampering-proxy tests; 7/7 mutants caught | No real devices; no TUF or root-key rotation; no HA | 38821179155 |

## Discipline pass/fail gates used

| Discipline | Gate (all must hold for PASS) |
|---|---|
| Distributed systems / storage | Linearizability or model checking under injected faults; crash tests at every write point; failing-first tests for each fixed bug; CI green |
| Compilers | Differential testing against a reference on corpora and generated programs; sanitizers; fuzzing with no open crash |
| ML systems | Numerical agreement with an independent reference (stated tolerance); benchmarks with load context; no claim without a log |
| GPU | Correctness on hardware **and** timing on hardware. Emulator-only work is PARTIAL |
| RTL / verification | Bit-exact against a golden model; Spike/reference differential testing for CPUs; coverage closure; assertions shown to fire on bugs; silicon or FPGA claims only with reports from that flow |
| Web / platform | Auth and session tests; Compose stack verified; real deployment, users and accounts are owner steps |
| Embedded / edge | Bit-exact firmware vs reference; real-board latency (QEMU is PARTIAL) |

## Supported resume bullets

Every number has a log in the linked project. Use these instead of the claims marked NOT SUPPORTED in `RESUME_EVIDENCE.md`.

### Systems and storage (SWE)
- **RaftKV (Go):**
  - Implemented Raft single-server membership changes with learner catch-up.
  - Verified linearizability under network partitions, packet loss and SIGKILL across real 3–5 process clusters (7/7 chaos runs).
  - Measured 5-node leader failover at a p50 of 360 ms over 45 trials.
  - Fixed two write-ahead-log durability bugs (a short write, and fsync errors on retry), each with a test that failed before the fix.
- **LSM Engine (Rust):**
  - Found and fixed two critical bugs: lost acknowledged writes, and a corrupt MANIFEST that deleted live tables.
  - Built a simulated power-loss filesystem and checked 6,055 crash images against a model.
  - Ran a 9,247-case bit-flip sweep with 0 silent corruptions.
  - Shipped a C ABI that is clean under ASan, valgrind and Miri.
  - Write and point-read throughput is on par with RocksDB on a small single-threaded test.
- **TANDEM (TypeScript, Yjs CRDT):**
  - Found a bug where an edit was acknowledged but never stored, using a randomized multi-node offline test (200/200 runs converge).
  - Proved acknowledge-after-commit by repeatedly killing the server and PostgreSQL with kill -9: 3,706 acknowledged edits, 0 lost.
  - Revoked sessions across nodes.
  - Median edit propagation is 25–44 ms with 50 editors on a single node.
- **VerifyCloud (TypeScript, Verilator):**
  - Brought up CI that had never run, and fixed 65 test failures against real S3/MinIO.
  - Verified lease fencing with real worker processes under SIGKILL, SIGSTOP and clock skew.
  - Sandbox-escape attempts (DPI-C calls into libc, host file reads) were contained under Docker; the two re-run under gVisor were also contained.
- **MCP Fabric (Java 21, Spring Boot):**
  - Built an OAuth 2.1 resource server per the MCP specification (JWKS rotation, protected-resource metadata, scope-to-policy mapping), with 11 end-to-end security tests using real RSA and EC keys.
  - Added human approvals and tool-poisoning and rug-pull defences.
  - A test proves client tokens never reach upstream servers.
- **EvalOps (Java + Python):**
  - Built a transactional outbox with idempotent consumers, plus idempotency keys.
  - Built leased Python workers with fencing tokens, tested by killing and pausing real worker processes.
  - Validated an LLM judge against labelled data with Cohen's κ, and reported that a 135M judge is at chance.

### Compilers
- **EMBER (C++, LLVM 18):**
  - Built an ORC JIT native backend whose output, errors and stack traces match the bytecode VM exactly on 68 programs and on 3,000 generated programs.
  - It runs 2.2–10.8× faster, JIT time included.
  - Ran 5 libFuzzer targets for about 900k executions with no crashes.
  - Mutation testing closed two test gaps.

### ML systems
- **LLM inference engine (Rust):**
  - Loads GGUF files with a BPE tokenizer and runs the pretrained SmolLM2-135M.
  - Logits match an fp64 reference to 4e-5, and greedy output is identical to llama.cpp.
  - Perplexity is within 0.005 % of llama.cpp's.
  - Continuous batching gives 3.1× throughput, and the prefix cache cuts time to first token 25× (CPU).
- **Distributed training (Python):**
  - Built a DDP and ZeRO-1 runtime from scratch.
  - It matches PyTorch DDP to 1.6e-15 in gradients and ZeroRedundancyOptimizer to 3.9e-14 in parameters, with bit-identical resume.
  - The PyTorch cross-check runs in CI.
- **Groundwork (Python):**
  - Hybrid BM25 + nomic-embedding retrieval reached NDCG@10 0.70 vs 0.63 for BM25 (p = 0.005).
  - A grounding checker removes unsupported sentences.
  - Prompt-injection success fell from 6/10 to 0/10 with defences against a local Qwen2.5-0.5B.
- **CUDA kernels:**
  - Wrote an fp16 tensor-core GEMM in inline PTX (mma.sync, ldmatrix), verified on a custom CPU SIMT emulator that models warp fragment layouts.
  - HMMA instructions confirmed in SASS for sm_80, sm_89 and sm_90, with 0 spills.
  - *No GPU timings until run on hardware.*

### Hardware (Electronics)
- **INT8/FP8 accelerator:**
  - Took the INT8/FP8 processing element through the OpenROAD RTL-to-GDS flow on sky130 at 100 MHz, with 0 routing DRC violations and a formally equivalent final netlist.
  - Measured the area cost of exact FP8 accumulation at 3.5×.
  - Gate-level simulation of the 100k-cell synthesised 4×4 top level is bit-exact against the golden model.
- **UVM SoC verification:**
  - Wrote 23 SVA properties; every one that can fail under Verilator fired on its injected bug.
  - Generated the register model from a single JSON spec; it alone catches both W1C bugs.
  - Wrote a native SystemVerilog UVM testbench that runs in CI on Verilator 5.052 with UVM 2020.3.1.
- **Industrial Edge (Go, C, personal project, synthetic data):**
  - Built an INT8 fault classifier, bit-exact across Python, C and Go and on Cortex-M4/M3 under QEMU.
  - ~11 KB flash and ~10.5 KB RAM.
  - 96.7 % accuracy on held-out operating speeds.
  - An estimated 6–9 ms per inference at 80 MHz (an emulator estimate, not measured on hardware).
- **RISC-V CPU:**
  - RV32IM 5-stage pipeline in SystemVerilog, verified with riscv-tests (64/64), against Spike (400/400 random programs) and in lockstep with a reference model.
  - Parameterised I/D caches with a write buffer, plus a study of cache size and memory latency (CoreMark/MHz from 0.96 to 2.79 at 20-cycle memory).
  - 9/9 injected cache bugs caught.
  - Compared IPC × Fmax against a single-cycle baseline on sky130 with a reduced OpenROAD flow. The result was near parity, because the multiplier is the critical path.
- **Nebula GPU cloud (Rust):**
  - A GPU-cluster scheduler with topology-aware gang scheduling and a crash-safe journal.
  - The node agent runs jobs in hardened Docker containers (resource limits, read-only root, all capabilities dropped, no network), tested against a real Docker daemon: limits, OOM, cancel, and orphan cleanup after kill -9.
  - *Real-GPU pass-through has not run yet.*
- **EdgeFleet (Go):**
  - Signed A/B OTA updates with root-signed, expiring, rollback-protected rotation of the release-signing keys.
  - Verified with a tampering-proxy test, and 7/7 hand-broken checks were caught.
- **Vector DB (Rust):**
  - HNSW/PQ vector database benchmarked against FAISS (PQ codes 15× smaller, 0.95 recall@10 with exact re-ranking).
  - Write-ahead log fault-tested with injected errors and a real kernel I/O error (EFBIG).
  - Docker image built and smoke-tested in CI.

