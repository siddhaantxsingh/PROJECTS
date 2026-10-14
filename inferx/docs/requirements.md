# Requirements matrix — Project 17 (InferX)

| Requirement | Status | Evidence |
|---|---|---|
| Gateway in front of model replicas (OpenAI-style API, streaming) | VERIFIED | gateway tests; real-engine benchmark |
| Routing policies incl. KV-cache-aware prefix affinity with bounded load | VERIFIED | router tests; 1.68× throughput vs round robin with real engines |
| Admission control: global queue with deadline, load shedding, per-tenant caps | VERIFIED | admission test |
| Health checks, passive ejection, retries | VERIFIED | failure-handling test |
| Autoscaling of replica processes with graceful drain | VERIFIED | autoscale and pool tests; autoscaling benchmark (0 failed, 0 shed) |
| Metrics | VERIFIED (basic) | `/metrics`, `/v1/replicas` |
| GPU replicas, GPU-aware placement | BLOCKED | no GPU |
| Multi-instance gateway, cloud autoscaling | NOT IMPLEMENTED | limitations |
| CI | VERIFIED | `.github/workflows/inferx.yml` (run 38587619872 green) |
