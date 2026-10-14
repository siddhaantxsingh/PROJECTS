# Requirements matrix — Project 19 (EvalOps)

| Requirement | Status | Evidence |
|---|---|---|
| Versioned, immutable datasets | VERIFIED | `datasetsAreVersionedByContent` |
| Evaluation runs against model endpoints (concurrency, retries, errors) | VERIFIED | `runsRetryCacheAndReport` |
| Reproducibility: target snapshots, response cache, seeded statistics | VERIFIED | cache test (zero requests), reproducibility test |
| Metrics library | VERIFIED | known-value tests |
| Confidence intervals and paired significance tests | VERIFIED | coverage, false-positive-rate and power simulations |
| Regression gates for CI | VERIFIED | gate tests; `scripts/gate.sh` |
| Resume after interruption | VERIFIED | `interruptedRunsResumeWithoutRedoingFinishedItems` |
| Evaluation of a real model | VERIFIED | `docs/demo.md` (llm-inference-engine f32 vs int8) |
| Chat / multi-turn, LLM-as-judge, sandboxed code execution | NOT IMPLEMENTED | limitations |
| Auth, multi-tenancy | NOT IMPLEMENTED | limitations |
| CI | IMPLEMENTED BUT UNVERIFIED | `.github/workflows/evalops.yml` |
