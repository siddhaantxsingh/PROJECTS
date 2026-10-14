# Tasks

Legend: [x] done · [~] in progress · [ ] not started · [B] blocked (see MASTER_BLOCKERS.md)

## Standalone repos (baseline + upgrade loop, on branch ccr-a6e6c567-y4w7k8)
- [~] RaftKV: membership reconfiguration, inter-process partition chaos, disk-fault injection, root CI
- [~] LSM Engine: crash-point testing, model-based tests, corruption, MVCC, C ABI safety, RocksDB comparison, CI
- [~] TANDEM: multi-node sync, offline convergence, persistence crash tests, session revalidation, load test
- [~] EMBER: scoped LLVM backend, optimization equivalence, GC stress, fuzzing and sanitizers
- [~] VerifyCloud: Compose stack, MinIO, sandbox, lease fencing, adversarial RTL, GitHub checks against a fake API, root CI

## PROJECTS second round
- [~] Industrial Edge: TinyML INT8 on Cortex-M (QEMU), predictive-maintenance evaluation, drift detection, MQTT over TLS with auth, Docker
- [~] LLM inference engine: GGUF loading, SmolLM2-135M, tokenizer, validation against independent references, perplexity, benchmarks
- [~] Groundwork + Campus AI OS: neural embeddings, a local LLM, re-run benchmarks, Docker builds
- [~] INT8/FP8 accelerator: ORFS synthesis, place and route, STA, GDSII, gate-level simulation
- [ ] RISC-V CPU: caches, ORFS timing and area
- [ ] Distributed training: PyTorch DDP (gloo) cross-check
- [ ] MCP Fabric: OAuth 2.1 resource server, approvals, TLS
- [ ] Nebula: container lifecycle via Docker with resource limits, TLS
- [ ] EvalOps: Python workers, outbox and idempotency, LLM judge using a local model, GitHub checks against a fake API
- [ ] UVM: SVA assertions, register model (RAL)
- [ ] VectorDB: Docker build, fault-injection fsync test
- [ ] CUDA kernels: tensor-core (mma) kernel verified on the emulator, compiled to SASS
- [ ] EdgeFleet: signing-key rotation
- [ ] InferX: run against real-model engines (after the LLM engine work)

## Cross-cutting
- [~] RESUME_EVIDENCE.md
- [ ] SCORECARD.md (20 projects, four scores each, confidence, evidence links)
- [ ] OWNER_ACTION_PLAN_20_PROJECTS.pdf
- [ ] Final README/HANDOFF update, push, CI check
