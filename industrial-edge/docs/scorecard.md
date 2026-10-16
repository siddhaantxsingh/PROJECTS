# Scorecard (2026-10-14)

| Score (0–10) | | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 8 | Everything ML runs on synthetic data and emulated hardware; no real sensor or board. |
| Engineering maturity | 7 | Strong tests (bit-exact checks, crash tests, TLS refusals), but drift/alarm thresholds are untuned and one e2e test flaked under heavy machine load. |
| Deployment readiness | 4 | Docker smoke test only; no real PLCs, no fleet management, demo PKI, model false-alarm rate too high for production. |
| Completion of intended scope | 7 | TinyML, PdM evaluation, drift, TLS and Docker done; real-board latency and real data are owner-only; fleet cloud workflows not built. |
