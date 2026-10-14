# Requirements matrix — Project 18 (Nebula GPU cloud)

| Requirement | Status | Evidence |
|---|---|---|
| Multi-tenant job API with per-tenant credentials and isolation | VERIFIED | e2e: 401 / 403 / 404 cases; tenants see only their own jobs |
| Topology-aware placement (NVLink islands, nodes) | VERIFIED | sched tests; `topology_placement_is_compact` property; e2e island check; simulator compactness |
| Gang scheduling for multi-node jobs (all-or-nothing) | VERIFIED | sched gang test; e2e 12-GPU job on two nodes with ranks |
| Fair share with weights; quotas | VERIFIED | sched tests; simulator per-tenant waits |
| Backfill (EASY) that does not delay the reserved job | VERIFIED | sched backfill tests; simulator |
| Priority preemption with a minimal victim set | VERIFIED | sched tests (minimality checked by mutation); e2e preemption with process kill and restart |
| Node failure detection and job requeue | VERIFIED | e2e agent SIGKILL → requeue after timeout; property tests |
| GPU failure handling | VERIFIED (injected, simulated GPUs) | e2e `fail-gpu-N`; property tests |
| Node agents running real commands with GPU isolation env | VERIFIED (env only) | e2e checks `CUDA_VISIBLE_DEVICES`, `NEBULA_*`; no real GPUs or cgroups |
| Durable, crash-safe control-plane state | VERIFIED | journal cut at every byte; replay = live state (property); snapshot/journal crash window; e2e restart with running jobs |
| Usage metering per job / tenant | VERIFIED | metering test; e2e `/v1/usage` |
| Billing (prices, invoices) | NOT IMPLEMENTED | — |
| Policy comparison by simulation | VERIFIED | `nebula-sim`; docs/benchmarks.md (slow-down factors are assumptions) |
| Control-plane load measurement | VERIFIED | `nebula-bench`; docs/benchmarks.md |
| Observability | VERIFIED (basic) | `/metrics` (GPUs, jobs, nodes, passes, preemptions, failures); structured logs |
| Drain / undrain nodes | VERIFIED | e2e |
| Real GPUs, NVML/DCGM health, containers / cgroups | BLOCKED | no GPU on the build machine; not implemented |
| HA control plane, fencing of partitioned nodes | NOT IMPLEMENTED | docs/limitations.md |
| TLS / mTLS, per-node identity | NOT IMPLEMENTED | static bearer tokens; docs/limitations.md |
| CI | VERIFIED | `.github/workflows/nebula-gpu-cloud.yml` (run 38522697861 green) |
