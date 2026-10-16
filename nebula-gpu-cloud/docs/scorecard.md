# Scorecard (2026-10-15, self-assessed)

Moved here from the README. The cross-project scorecard is in [`../../master/SCORECARD.md`](../../master/SCORECARD.md).

| Score | /10 | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 8 | Strong scheduler, durability and container work, but nothing has run on a real GPU. |
| Engineering maturity | 7 | Property, crash and real-Docker tests; but one lock, no TLS, and the agent shells out to the docker CLI. |
| Deployment readiness | 4 | No TLS/mTLS, no HA, no real-GPU validation, no packaging or service units. |
| Completion of intended scope | 7 | Scheduling, recovery, quotas and containers done; real GPU, NVML health and mTLS are not. |
