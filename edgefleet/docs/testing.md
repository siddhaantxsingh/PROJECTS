# Testing

`go test ./...`, about 25 s. `go test -race ./internal/...` is clean.

| Area | Tests |
|---|---|
| `internal/proto` | Manifest signatures: any field change or a wrong key is rejected. Request authentication: replayed nonce, tampered body, changed path, ±5 min skew and unknown device are each rejected. |
| `internal/store` | Replay; compaction followed by more events; **journal cut at every byte** replays exactly the complete events; corruption in the middle of the journal is an error; rollout transitions keep the results journaled separately. |
| `internal/rollout` | Wave sizes are proportional (20,000 devices, within 4σ) and deterministic; different rollouts pick different canaries; desired-version logic for every state (running, paused, halted, aborted, failed device, selector); gate: wait for reports, bake time, advance, halt; parameter validation. |
| `internal/server` (real HTTP, agents in simulation mode) | Good rollout completes in waves, and wave 0 stays small. A bad release halts at the canary, and every device ends on the old version. A flaky release under the threshold completes, with failures left rolled back. Operator abort rolls back. A tampered artifact is rejected by the agents; an unsigned upload is rejected by the server. Tokens are single-use; the admin API and unsigned heartbeats are refused. **An agent crash at each update step** (after download, after install, at trial boot, after the health check) followed by a reboot from disk always leaves a valid version and converges. A crash loop during the trial is rolled back by the boot counter, and the failure is reported once. A resumed download fetches only the missing half. A control-plane restart mid-rollout keeps the wave and results, and the rollout finishes. |
| `e2e` (built `fleetd`, `fleet-agent` and `fleetctl` binaries; artifacts are real shell scripts the agents execute and health-check) | 3 devices go from 1.0 to 2.0, and the running process is checked to be the new version. A 3.0 release that crashes on start triggers rollbacks, the rollout halts, and every device runs a live 2.0 process again. An agent killed and restarted comes back on 2.0. Repeated 3×. |
