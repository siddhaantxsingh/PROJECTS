# Testing

`mvn verify` runs 14 tests in about 30 seconds.

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

## `UnitTests` (3 tests) and `ExampleConfigTest` (1 test)

* The circuit breaker opens, allows exactly one half-open probe, reopens when the probe fails and closes when it succeeds (fake clock).
* Policy: glob matching, deny over allow, a token bucket that refills only up to the burst (fake clock).
* The audit log recovers from a torn final write and continues a valid chain.
* `config/example.yml` binds, and its documented tokens authenticate with the documented permissions.

## Benchmark

`BenchmarkTest` is opt-in (`mvn test -Dtest=BenchmarkTest -Dbench=true`); see [benchmarks.md](benchmarks.md).
