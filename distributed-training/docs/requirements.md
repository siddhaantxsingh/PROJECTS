# Requirements matrix — Project 9 (Distributed training, CPU)

| Requirement | Status | Evidence |
|---|---|---|
| Collective communication (all-reduce, reduce-scatter, all-gather, broadcast, barrier) | VERIFIED | from-scratch TCP implementation; `tests/test_comm.py` |
| Ring all-reduce + baseline algorithm | VERIFIED | ring vs direct; benchmarked against OpenMPI |
| Data-parallel training (DDP) | VERIFIED | equals single process to 1e-10 (float64); convergence run |
| Gradient bucketing + comm/compute overlap | VERIFIED (functionally) | results identical with/without overlap; overlap gives no speed-up here (documented) |
| Gradient accumulation | VERIFIED | equals larger batch |
| Optimizer state sharding (ZeRO-1) | VERIFIED | equals DDP; per-rank state = 1/W (exact byte count tested) |
| Sharded checkpointing (atomic, checksummed) | VERIFIED | bit-identical resume; corrupted shard rejected |
| Fault tolerance (worker failure → restart → resume) | VERIFIED | injected crash; final params bit-identical to failure-free run |
| Failure detection (no hang on dead peer) | VERIFIED | `test_peer_failure_raises_commerror_not_hang` |
| Throughput scaling measurement | VERIFIED (1–4 ranks, single host) | docs/benchmarks.md |
| GPU / NCCL | BLOCKED | no GPU in the environment |
| PyTorch integration | BLOCKED | PyTorch CPU wheel index unreachable |
| Multi-host | IMPLEMENTED BUT UNVERIFIED | plain TCP; only loopback tested |
| Tensor / pipeline parallelism, ZeRO-2/3, mixed precision | NOT IMPLEMENTED | — |
| CI | VERIFIED | `.github/workflows/distributed-training.yml` green on GitHub Actions (run 38484856196) |
