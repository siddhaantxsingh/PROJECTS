# Master progress — 20-project audit and upgrade

Started 2026-10-14. The directive is `MASTER_20_PROJECTS_PROMPT.md`, supplied by the owner and not stored in the repo.

## Where each project lives

| # | Project | Location | Branch for this work |
|---|---|---|---|
| 1 | RaftKV | github.com/siddhaantxsingh/RAFT-KV (`raft-kv/`) | `ccr-a6e6c567-y4w7k8` |
| 2 | LSM Engine | github.com/siddhaantxsingh/LSM_ENGINE (`lsm-engine/`) | `ccr-a6e6c567-y4w7k8` |
| 3 | TANDEM | github.com/siddhaantxsingh/TANDEM-Collaborative-Editor | `ccr-a6e6c567-y4w7k8` |
| 4 | EMBER | github.com/siddhaantxsingh/EMBER-Compiler | `ccr-a6e6c567-y4w7k8` |
| 5 | VerifyCloud | github.com/siddhaantxsingh/VerifyCloud (`verifycloud/`) | `ccr-a6e6c567-y4w7k8` |
| 6–20 | the other 15 | github.com/siddhaantxsingh/PROJECTS, one folder each | `ccr-a6e6c567-y4w7k8` |

`main` is never pushed to and no pull requests are opened; the owner merges.

## Milestones

| Date | Milestone |
|---|---|
| 2026-10-14 | All 15 PROJECTS folders finished, with green CI (commit 4fe1ec7) |
| 2026-10-14 | The 5 standalone repos cloned; the baseline and upgrade audits started |
| 2026-10-14 | Second round on PROJECTS started: Industrial Edge TinyML and security, LLM engine with pretrained weights, real embeddings and LLM for Groundwork and Campus, RTL-to-GDSII for the accelerator |

| 2026-10-14 → 16 | All 20 audits and upgrades finished; each is pushed to its branch with green CI. The work was interrupted by 6 usage-limit resets and several worker restarts; every agent was resumed from its saved state |
| 2026-10-16 | SCORECARD.md, RESUME_EVIDENCE.md and OWNER_ACTION_PLAN_20_PROJECTS.pdf finished |

See `SCORECARD.md` for the final per-project state.

## Resume point for a future session

Nothing is in progress. To continue: read `SCORECARD.md` (the "Main gap" column) and `MASTER_BLOCKERS.md`, then pick owner-unblocked items. The first GPU results from the owner go into `cuda-kernels/results/gpu/`, `nebula-gpu-cloud/docs/evidence/owner-gpu-*` and `distributed-training/benchmarks/results/*/gpu/`.
