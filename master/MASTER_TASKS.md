# Tasks (final state, 2026-10-16)

Legend: [x] done and pushed with green CI · [B] blocked on the owner (see MASTER_BLOCKERS.md)

## Standalone repos (branch ccr-a6e6c567-y4w7k8 in each)
- [x] RaftKV: membership changes, network-fault chaos between processes, disk-fault tests, WAL/fsync fixes, root CI
- [x] LSM Engine: 13 defects fixed (2 critical), crash and corruption sweeps, model test, C ABI (ASan/valgrind/Miri), RocksDB comparison, CI
- [x] TANDEM: ack-without-persist bug, cross-node session revocation, offline property test, kill -9 crash test, load test
- [x] EMBER: LLVM 18 ORC JIT backend, differential testing, GC stress under ASan, libFuzzer
- [x] VerifyCloud: CI at root, real MinIO, Compose stack, gVisor, lease fencing with real processes, adversarial RTL tests

## PROJECTS monorepo
- [x] Industrial Edge: INT8 TinyML (QEMU Cortex-M), PdM evaluation, drift, MQTT TLS/mTLS, Docker
- [x] LLM inference engine: GGUF, SmolLM2-135M, validation against NumPy and llama.cpp, perplexity, benchmarks
- [x] Groundwork + Campus AI OS: neural embeddings, local LLM, reranker, grounding enforcement, Docker images
- [x] INT8/FP8 accelerator: ORFS flow; PE to GDS; top level synthesised, placed and gate-level simulated
- [x] RISC-V CPU: caches, single-cycle baseline, reduced-flow timing
- [x] Distributed training: PyTorch cross-check (DDP, ZeRO-1, resume) in CI
- [x] MCP Fabric: OAuth 2.1 resource server, approvals, credential isolation, threat tests, TLS
- [x] Nebula: Docker job runtime, GPU detection
- [x] EvalOps: outbox, idempotency, Python workers, LLM judge with validation, GitHub checks
- [x] InferX: re-benchmarked with SmolLM2 (routing gain within noise; autoscaling verified)
- [x] UVM: SVA, RAL, native SV-UVM on Verilator 5.052
- [x] VectorDB: WAL fault injection, Docker image in CI
- [x] CUDA kernels: tensor-core GEMM, backward passes, sm_89
- [x] EdgeFleet: release-key rotation

## Cross-cutting
- [x] RESUME_EVIDENCE.md
- [x] SCORECARD.md (20 projects, four scores, confidence, CI runs, supported bullets)
- [x] OWNER_ACTION_PLAN_20_PROJECTS.pdf
- [x] Root README and HANDOFF updated

## Owner-only (not doable here)
- [B] RTX 4060 runs: CUDA kernels, Nebula, distributed training
- [B] Cortex-M board run (Industrial Edge)
- [B] FPGA bring-up (RISC-V) or removing the FPGA claim
- [B] Commercial UVM simulator run
- [B] Real GitHub App, IdP, LLM API key, deployments, betas
- [B] Internship verification
