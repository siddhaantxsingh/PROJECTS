# Scorecard — InferX

Moved from the README. Scores are self-assessed, 0–10.

| Score (0–10) | Value | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 7 | Real routing and autoscaling with real model processes, but the pretrained-model benchmark shows only a small, noisy gain. |
| Engineering maturity | 7 | Race-tested, fault-injection tests, real-process pool tests; single gateway instance and no TLS. |
| Deployment readiness | 4 | Local processes only: no container images, no cloud autoscaling, no multi-instance state, static API keys. |
| Completion of intended scope | 7 | CPU scope is done and re-run with a pretrained model; GPU replicas are blocked, and the SmolLM2 benchmark is too small to confirm the cache-affinity win. |
