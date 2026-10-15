# Testing

`mvn verify` runs 33 tests (plus one skipped opt-in benchmark) in about 1–2 minutes. The log of the 2026-10-15 run is in `evidence/2026-10-15/mvn-verify.log`.

## `SecurityIntegrationTest` (11 tests)

This test runs the gateway in OAuth mode against an in-test JWKS server. The server publishes real RSA-2048 and EC P-256 keys and counts how often it is fetched. Behind the gateway are `alpha` (stdio, started with `--threat` and `--capture`) and `beta` (HTTP, `--capture`). Both record every byte they receive.

| Test | What it proves |
|---|---|
| `protectedResourceMetadataAndChallenge` | PRM at both well-known paths. A 401 carries `resource_metadata`. A token in the query string is ignored. |
| `tokenValidationMatrix` | Accepted with 200: RS256, ES256, `aud` lists that include the resource, expiry within the skew, and `scp` arrays. Rejected with 401 and the matching `error_description`: expired, `nbf` in the future, wrong or missing `aud`, wrong `iss`, unknown `kid`, forged signature with a known `kid`, `alg=none`, HS256 keyed with the RSA public key, and malformed tokens. Missing scope gets 403 with `scope="mcp:tools"`; an unknown tenant gets 403. The JWKS is fetched once and cached, and 11 unknown-`kid` tokens cause at most 4 re-fetches. |
| `signingKeyRotationIsPickedUp` | A new `kid` is refused until it is published, then accepted without a restart. |
| `scopesAndTenantsNarrowTheToolPolicy` | `mcp:server:beta` shows and permits only `beta__*`. No server scope means no tools. The `readonly` tenant sees only `alpha__echo`. Another `sub` cannot use the session. |
| `adminApiNeedsTheAdminScope` | Client tokens get 403 on `/admin`, and an admin token gets 403 on `/mcp`. |
| `approvalsParkCallsUntilAnAdminDecides` | Covers approve (the call completes), self-approval (403), idempotent re-approval, a conflicting deny (409), deny (-32001) and timeout (-32003, with a late approval refused). Checks the audit records and the chain. |
| `poisonedToolMetadataIsRedacted` | The poisoned `notes` tool is listed with its description redacted and the zero-width character stripped. Its findings are reported and audited. Benign tools are untouched. |
| `pathTraversalIsRejectedBeforeTheUpstream` | Seven traversal and escape forms get -32602, and the upstream's call counter proves they were never forwarded. Paths inside the root pass. |
| `oversizedAndDeeplyNestedPayloadsAreRefused` | A 1.5 MiB body gets 413 with and without Content-Length. JSON nested 5,000 deep gets 400. |
| `clientTokensNeverReachUpstreamsAndUpstreamCredentialsAreInjected` | No client token, whether signature or payload, appears in anything either upstream received. `beta` saw only its configured `Authorization`. `alpha`'s environment holds only allowlisted variables plus `ALPHA_TOKEN`. |
| `changedToolDefinitionIsQuarantinedUntilAccepted` | The upstream rewrites `echo`'s description (a rug pull). The tool is hidden, calls are refused and the change is audited. After an admin accepts it, the tool comes back with the new, poisoned text redacted. |

## `TlsTest` (2 tests) and `SecurityUnitTests` (5 tests)

* HTTPS with a `keytool`-generated EC keystore: an MCP handshake over TLS 1.3. A client that does not trust the certificate gets `SSLHandshakeException`, and plain HTTP to the TLS port gets no MCP answer.
* Unit tests cover:
  * approval idempotency, conflict, expiry and self-approval (fake clock);
  * tool-guard patterns, with five ordinary descriptions not flagged;
  * trusted versus untrusted handling;
  * pin persistence across a restart;
  * path-guard cases;
  * scopes narrowing but never widening a tenant's tools.

## `FabricIntegrationTest` (10 tests)

Each test drives a real gateway on a random port over HTTP. Behind it are two **separate processes** running the demo MCP server: `alpha` over stdio (a child of the gateway) and `beta` over Streamable HTTP.

| Test | What it proves |
|---|---|
| `handshakeSessionsAndAuth` | Missing or wrong tokens get 401. A session belongs to its client: another client's token gets 404, and a missing session id gets 400. Protocol versions are negotiated (2025-06-18, 2025-03-26, fallback). An unsupported version header gets 400. An unknown method gets -32601. `DELETE` ends the session. |
| `catalogIsFederatedAndPaginated` | Both upstreams' tools appear namespaced. The upstream's own pagination (3 per page) and the gateway's (4 per page) are both followed; a bad cursor gets -32602. Resources are routed by URI, and prompts by namespace. |
| `toolCallsAreRoutedAndValidated` | Calls reach the right upstream. Tool errors pass through as `isError`. Schema violations are rejected with the validator's messages and **never reach the upstream** (checked with the upstream's own call counter). |
| `authorizationAndRateLimits` | A client limited to `alpha__*` (minus `*__crash`) sees and can call only those tools. A 3-call burst admits 3–4 calls and rejects the rest with 429/-32029, then refills at 1/s. Non-tool requests are not limited. |
| `concurrentCallsKeepTheirResponses` | 100 concurrent calls across both upstreams each get their own answer. |
| `timeoutsAndCancellationReachTheUpstream` | A 10 s call times out at 2 s with -32002, and the upstream records the forwarded cancellation. A client's `notifications/cancelled` is translated to the upstream request id and reaches the upstream. |
| `listChangedPropagatesToClients` | An upstream adds a tool and sends `list_changed`. The gateway refreshes, notifies the client's SSE stream, and the new tool becomes callable. |
| `crashedStdioUpstreamRestartsAndCircuitBreakerOpens` | The stdio server process exits mid-call: the call fails, and a new process (new pid) is handshaken and serves. Two timeouts open `beta`'s circuit; calls then fail in under 500 ms; after the cooldown the trial call closes it. |
| `auditLogRecordsEveryCallAndDetectsTampering` | Records exist for every outcome (ok, forbidden, invalid, rate_limited). Arguments are stored only as hashes. The chain verifies through the admin endpoint, which refuses client tokens. Editing one record, or deleting one, is detected. |
| `upstreamStatusAndMetrics` | `/admin/upstreams` lists both upstreams. `/actuator/prometheus` exposes the call timers, upstream readiness and rejections. |

## `UnitTests` (3 tests) and `ExampleConfigTest` (2 tests)

* The circuit breaker opens, allows exactly one half-open probe, reopens when the probe fails and closes when it succeeds (fake clock).
* Policy: glob matching, deny over allow, a token bucket that refills only up to the burst (fake clock).
* The audit log recovers from a torn final write and continues a valid chain.
* `config/example.yml` binds (static mode), and its documented tokens authenticate with the documented permissions. `config/oauth-example.yml` binds and builds a verifier.

## Benchmark

`BenchmarkTest` is opt-in (`mvn test -Dtest=BenchmarkTest -Dbench=true`); see [benchmarks.md](benchmarks.md).
