# Scorecard (2026-10-15, self-assessed)

Moved here from the README. The cross-project scorecard is in [`../../master/SCORECARD.md`](../../master/SCORECARD.md).

| Score | /10 | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 8 | Crash-safe A/B updates, staged rollouts and key rotation are well tested, but it has never run on real devices. |
| Engineering maturity | 7 | Crash-at-every-step, mutation-checked and real-binary tests; but one lock on the heartbeat path and no TLS. |
| Deployment readiness | 3 | Not deployed, no TLS, no bootloader integration, no HA, shared admin token. |
| Completion of intended scope | 7 | OTA, rollouts and release-key rotation done; TUF, device-key rotation and delta updates are not. |
