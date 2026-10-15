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
| Throughput and overhead measured | VERIFIED for the 2026-10-14 version; PARTIAL after the security changes | `docs/benchmarks.md`; the 2026-10-15 re-run was under heavy shared load and is inconclusive |
| OAuth 2.1 resource server: JWKS (cache, rotation, rate-limited refresh), iss/aud/exp/nbf/skew, scopes | VERIFIED (in-test IdP only) | `SecurityIntegrationTest.tokenValidationMatrix`, `signingKeyRotationIsPickedUp`; `docs/evidence/2026-10-15/mvn-verify.log`. Real IdP: owner action |
| Rejection of alg=none, HS256 key confusion, unknown kid, forged signature, malformed tokens | VERIFIED | `tokenValidationMatrix` (each asserts 401 and its `error_description`) |
| `WWW-Authenticate` with `resource_metadata`; Protected Resource Metadata endpoint | VERIFIED | `protectedResourceMetadataAndChallenge` |
| Scope/tenant mapping to tool policy (least privilege), session bound to principal | VERIFIED | `scopesAndTenantsNarrowTheToolPolicy`, `SecurityUnitTests.scopesNarrowButNeverWidenTheTenantPolicy` |
| Separate admin scope for the admin API | VERIFIED | `adminApiNeedsTheAdminScope` |
| Static tokens as opt-in dev mode | VERIFIED | `FabricIntegrationTest` (mode static), `ExampleConfigTest` |
| Human approvals (pending, approve/deny, timeout, idempotency, no self-approval, audit) | VERIFIED | `approvalsParkCallsUntilAnAdminDecides`, `SecurityUnitTests.approvalDecisionsAreIdempotentAndExpire` |
| Credential isolation (no token passthrough; per-upstream injected credentials; scrubbed stdio env) | VERIFIED | `clientTokensNeverReachUpstreamsAndUpstreamCredentialsAreInjected` |
| Tool-poisoning screening (redact/block/flag) | VERIFIED (heuristic) | `poisonedToolMetadataIsRedacted`, `SecurityUnitTests.toolGuardFlagsInjectionButNotOrdinaryDescriptions` |
| Tool-definition pinning and rug-pull quarantine | VERIFIED | `changedToolDefinitionIsQuarantinedUntilAccepted`, `SecurityUnitTests.toolPinsPersistAcrossRestarts` |
| Oversized / deeply nested request rejection | VERIFIED | `oversizedAndDeeplyNestedPayloadsAreRefused` (413 with and without Content-Length; 400 for 5,000-deep JSON) |
| Path-traversal checks on declared file arguments | VERIFIED | `pathTraversalIsRejectedBeforeTheUpstream`, `SecurityUnitTests.pathGuard` |
| TLS served by the gateway | VERIFIED (self-signed test keystore) | `TlsTest` (TLS 1.3 handshake, untrusted cert refused) |
| Real IdP (Keycloak/Auth0) end-to-end | NOT VERIFIED | owner action, [OWNER_ACTIONS.md](OWNER_ACTIONS.md) |
| Token introspection / opaque tokens | NOT IMPLEMENTED | see limitations |
| Sampling, elicitation, roots, subscriptions, progress forwarding | NOT IMPLEMENTED | — |
| Multi-instance / HA | NOT IMPLEMENTED | — |
| CI | VERIFIED | `.github/workflows/mcp-fabric.yml` (run 38740636150 green, 2026-10-15) |
