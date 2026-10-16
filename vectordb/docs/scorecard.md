# Scorecard (2026-10-16, self-assessed)

Evidence: [benchmarks.md](benchmarks.md), [testing.md](testing.md), [audit.md](audit.md), [requirements.md](requirements.md), [limitations.md](limitations.md).

| Score | /10 | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 8 | HNSW, PQ, SIMD and WAL from scratch with a FAISS comparison, but only synthetic datasets. |
| Engineering maturity | 8 | Crash-at-every-byte, fault-injection, property and audit tests; no fuzzing and no real-device fsync error. |
| Deployment readiness | 4 | Image builds and runs, but no auth, TLS, replication or backups. |
| Completion of intended scope | 8 | Engine, APIs, durability and Docker done; real datasets and replication are not. |
