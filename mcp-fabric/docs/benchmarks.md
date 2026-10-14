# Benchmarks

Produced by `mvn test -Dtest=BenchmarkTest -Dbench=true` (plus `-Dbench.auditDir=/dev/shm` for the second column) on a 4-vCPU cloud VM. The gateway, the test client and the upstream demo server (a child process speaking MCP over stdio) all share those 4 vCPUs. Raw results are in `bench/results/2026-10-14/`.

The tool is `echo`. Each call through the gateway is authenticated, checked against the client's policy, rate limited, validated against the tool's JSON Schema, forwarded and audited (one fsync).

## Latency, one call at a time (3,000 calls after 500 warm-up)

| path | p50 | p99 | mean |
|---|---|---|---|
| upstream direct (stdio), no gateway | 0.14 ms | 1.37 ms | 0.19 ms |
| through mcp-fabric (HTTP), audit on disk | 2.43 ms | 7.24 ms | 2.66 ms |
| through mcp-fabric (HTTP), audit on tmpfs | 2.08 ms | 7.59 ms | 2.36 ms |

## Throughput (5 s per row)

| concurrent clients | calls/s, audit on disk | p50 / p99 | calls/s, audit on tmpfs | p50 / p99 |
|---|---|---|---|---|
| 1 | 476 | 1.8 / 6.3 ms | 682 | 1.3 / 5.6 ms |
| 8 | 1,534 | 4.6 / 15.0 ms | 2,522 | 2.6 / 10.5 ms |
| 32 | 2,255 | 13.0 / 33.8 ms | 5,815 | 4.9 / 15.3 ms |

## Reading the results

* **Overhead.** The gateway adds about 2.3 ms at p50 to a call that takes 0.14 ms against the upstream directly. That covers an HTTP round trip from the test client (JDK `HttpClient` → Tomcat), policy and schema checks, the stdio hop and the audit write.
* **The audit log's fsync is the throughput limit under concurrency.** Appends are serialised, one `force()` each. With the log on tmpfs, throughput at 32 clients rises from 2,255 to 5,815 calls/s, while sequential latency barely changes. Group commit (one fsync for all records waiting at that moment) is the obvious next step; it is listed in the limitations.
* Real tool calls (file access, web requests, databases) take milliseconds to seconds, so the per-call overhead matters less than the audit throughput ceiling at high concurrency.
