# Requirements matrix — Project 15 (MCP Fabric)

| Requirement | Status | Evidence |
|---|---|---|
| MCP server endpoint (Streamable HTTP, sessions, version negotiation) | VERIFIED | `handshakeSessionsAndAuth` |
| Federation of stdio and HTTP upstream MCP servers | VERIFIED | integration tests with two real upstream processes |
| Tools, resources, prompts; pagination; list_changed propagation | VERIFIED | catalog and list_changed tests |
| Client authentication and per-tool authorization | VERIFIED | auth and policy tests |
| Argument validation against tool input schemas | VERIFIED | validation test (upstream never called) |
| Rate limiting | VERIFIED | token-bucket tests (unit + HTTP 429) |
| Timeouts, cancellation forwarding | VERIFIED | timeout/cancellation test |
| Resilience: process restart, circuit breaker | VERIFIED | crash and breaker tests |
| Tamper-evident audit log | VERIFIED | audit tests (edit, delete, torn write) |
| Metrics and admin status | VERIFIED | Prometheus and admin tests |
| Throughput and overhead measured | VERIFIED | `docs/benchmarks.md` |
| OAuth 2.1 authorization (MCP auth spec), TLS | NOT IMPLEMENTED | static bearer tokens; see limitations |
| Sampling, elicitation, roots, subscriptions, progress forwarding | NOT IMPLEMENTED | — |
| Multi-instance / HA | NOT IMPLEMENTED | — |
| CI | VERIFIED | `.github/workflows/mcp-fabric.yml` (run 38560670251 green) |
