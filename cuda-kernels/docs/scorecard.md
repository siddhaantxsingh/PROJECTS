# Scorecard (2026-10-15)

Scores are 0–10. Evidence: [analysis.md](analysis.md), [testing.md](testing.md), [requirements.md](requirements.md), [limitations.md](limitations.md).

| Area | Score | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 7/10 | Tensor cores, an emulator that models fragment layouts, and SASS checks are uncommon, but there is no measured GPU speed. |
| Engineering maturity | 7/10 | 150 emulator checks, mutation runs and CI, but nothing has run on real hardware and the fragment model is only checked against documentation. |
| Deployment readiness | 3/10 | This is a kernel library with a benchmark harness, not a packaged library. It has never executed on a GPU. |
| Completion of intended scope | 6/10 | GPU timing is BLOCKED, there is no cuBLAS comparison, no bf16/FP8, no cp.async pipeline and no attention backward. |
