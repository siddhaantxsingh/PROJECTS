# Requirements matrix — Project 20 (EdgeFleet)

| Requirement | Status | Evidence |
|---|---|---|
| Device enrollment with identity | VERIFIED | single-use tokens; Ed25519 device keys; tests |
| Authenticated device API (replay-safe) | VERIFIED | proto tests; unsigned/replayed requests refused |
| Signed releases, verified on device | VERIFIED | server rejects unsigned; agent rejects tampered (tests) |
| A/B updates with automatic rollback | VERIFIED | crash at every step; boot counter; real-process e2e |
| Staged rollouts (waves, gates, bake) | VERIFIED | rollout tests; fleetsim 2,000 devices |
| Automatic halt of bad releases | VERIFIED | blast radius 15 / 2,000 devices |
| Pause / resume / abort | VERIFIED | abort rolls back (test) |
| Resumable downloads | VERIFIED | Range test (only missing bytes sent) |
| Durable control-plane state, crash-safe | VERIFIED | journal cut at every byte; restart mid-rollout |
| Fleet-scale simulation and measurement | VERIFIED | `cmd/fleetsim`, docs/benchmarks.md |
| Observability | VERIFIED (basic) | `/metrics` (devices by version, rollout wave, heartbeats, auth failures) |
| Admin CLI | VERIFIED | `fleetctl` used by e2e |
| HA / multi-instance control plane | NOT IMPLEMENTED | — |
| Delta updates, key rotation, TUF | NOT IMPLEMENTED | — |
| CI | VERIFIED | `.github/workflows/edgefleet.yml` (run 38517425606 green) |
