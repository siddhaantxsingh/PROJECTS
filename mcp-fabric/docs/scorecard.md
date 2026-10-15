# Scorecard — MCP Fabric (2026-10-15)

| Dimension | Score | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 8/10 | Strong and test-backed: an OAuth resource server, approvals, a poisoning/rug-pull guard, and tests that prove no token passthrough. But it has no real users and no real-IdP run. |
| Engineering maturity | 7/10 | 33 tests and green CI. But approvals and sessions are in memory, tool screening is heuristic, and there is no load test after the security changes on a quiet machine. |
| Deployment readiness | 5/10 | TLS and OAuth are in place, but untested against a real IdP or real certificate. It runs as a single instance with no HA, no container image, and no introspection or revocation. |
| Completion of intended scope | 8/10 | All five requested items are implemented and tested. Still missing: a real IdP end-to-end run, cancellation of pending approvals, per-tenant approval rules, and screening of tool results. |
