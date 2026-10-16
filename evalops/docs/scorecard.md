# Scorecard — EvalOps

Moved from the README. Scores are self-assessed, 0–10.

| Score (0–10) | Value | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 8 | Covers outbox, idempotency, fencing tokens and judge validation with real tests, but it is one service with no real users or real GitHub integration run. |
| Engineering maturity | 7 | Crash tests for the outbox are simulated in-process (no JVM kill), there is no dead-letter handling, and no auth on any endpoint. |
| Deployment readiness | 4 | Single instance on embedded H2, no auth, GitHub App never run against real GitHub, no container image or deployment manifests. |
| Completion of intended scope | 7 | Outbox, idempotency, workers and checks are done; the judge works but the only judge model available here is at chance, and real GitHub use is owner-blocked. |
