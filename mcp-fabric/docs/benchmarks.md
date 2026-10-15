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

## Re-run after the security changes (2026-10-15): inconclusive

The security features add per-call work: a SHA-256 of the tool's canonical definition (pin check), a path check, and an approval-rule match. The 2026-10-15 re-runs were made while many other agents loaded the same 4-vCPU VM. Raw JSON and `uptime` logs are in `docs/evidence/2026-10-15/`.

| run | load average (1 min) | version | p50 sequential | 1 / 8 / 32 clients, calls/s |
|---|---|---|---|---|
| new, 3 runs | 9.3 to 10.8 | after | 3.03 / 3.14 / 3.15 ms | 303–369 / 428–546 / 601–737 |
| A/B 1 | 3.8 → 86 | before | 4.98 ms | 144 / 203 / 212 |
| A/B 1 | 86 | after | 4.91 ms | 241 / 284 / 341 |
| A/B 2 | 112 | before | 4.32 ms | 239 / 393 / 426 |
| A/B 2 | 40 | after | 5.39 ms | 208 / 185 / 6 |

Under this load the old version's own numbers fell up to tenfold. The comparison therefore cannot separate the new code's cost from noise. The 32-client figure of 6 calls/s in the last row was not reproduced in any other run and is not explained. The 2026-10-14 table above remains the reference for the earlier version. A clean re-run on an idle machine is an owner action.
