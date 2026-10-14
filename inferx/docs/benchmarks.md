# Benchmarks

Produced by `bench/run.sh 2026-10-14` on a 4-vCPU cloud VM. InferX launches real `llm-inference-engine` replicas: `llm serve` with one rayon thread each and a 160-block (2,560-token) KV pool, so the cache is under pressure. The load generator streams completions (`max_tokens` 16).

**Workload.** 16 shared prefixes of real held-out Python code (~600 bytes, ~250 tokens each), chosen at random per request, plus a unique 1-line suffix. This is the system-prompt / shared-context pattern that prefix caching serves. The prefix-cache hit ratio is the share of prompt tokens the engines report as served from their KV cache (`usage.cached_tokens`).

## Routing policy (4 replicas, 16 concurrent clients, 40 s)

| policy | requests/s | prefix-cache hit ratio | TTFT p50 / p99 | latency p50 / p99 | per-replica requests |
|---|---|---|---|---|---|
| round_robin | 18.3 | 48 % | 256 / 1181 ms | 607 / 2782 ms | 187 / 187 / 187 / 187 |
| least_loaded | 22.0 | 47 % | 207 / 857 ms | 700 / 1568 ms | 225 / 227 / 208 / 226 |
| prefix_affinity | 30.6 | 66 % | 197 / 1224 ms | 454 / 1700 ms | 289 / 250 / 340 / 364 |

## Autoscaling (prefix affinity, 1–4 replicas, target 4 in flight per replica)

Load schedule: 2 clients for 15 s, 24 clients for 40 s, then 2 clients for 30 s. Scale-down needs 10 s of low load.

Overall: 1911 requests, 0 shed, 0 failed; latency p50 / p99 431 / 2262 ms.

Replica events (gateway log):

```
12:20:53.965 launched replica-1 (pid 18762, http://127.0.0.1:41545)
12:21:10.019 launched replica-2 (pid 18795, http://127.0.0.1:37497)
12:21:10.071 launched replica-3 (pid 18801, http://127.0.0.1:38351)
12:21:10.122 launched replica-4 (pid 18807, http://127.0.0.1:40291)
12:21:59.966 draining replica-4
12:21:59.970 stopped replica-4
12:22:00.967 draining replica-3
12:22:00.972 stopped replica-3
12:22:01.967 draining replica-2
12:22:02.074 stopped replica-2
```

Completed requests per second, averaged over 5 s:

```
t=  1-  5s clients= 2 completed/s=  4.6
t=  6- 10s clients= 2 completed/s=  6.2
t= 11- 15s clients= 2 completed/s=  5.6
t= 16- 20s clients=24 completed/s= 20.2
t= 21- 25s clients=24 completed/s= 44.0
t= 26- 30s clients=24 completed/s= 34.2
t= 31- 35s clients=24 completed/s= 35.8
t= 36- 40s clients=24 completed/s= 35.0
t= 41- 45s clients=24 completed/s= 38.6
t= 46- 50s clients=24 completed/s= 43.4
t= 51- 55s clients=24 completed/s= 40.6
t= 56- 60s clients= 2 completed/s= 21.6
t= 61- 65s clients= 2 completed/s= 23.4
t= 66- 70s clients= 2 completed/s= 11.6
t= 71- 75s clients= 2 completed/s=  5.2
t= 76- 80s clients= 2 completed/s=  4.2
t= 81- 85s clients= 2 completed/s=  6.8
```

## Reading the results

* **Prefix affinity turns the KV cache into a shared-context cache.** It reaches 66 % of prompt tokens served from cache, against 48 % under round robin. With affinity each replica mostly sees its own few of the 16 prefixes; under round robin each sees all 16, and its 2,560-token pool, shared with the running sequences, keeps evicting them. Requests per second go from 18.3 to 30.6 (1.68×), and the median time to first token from 256 to 197 ms.
* **Bounded load limits hot-spotting, but not completely.** Every request is hashed to a preferred replica, yet per-replica counts stay within about 20 % of the mean. The price is in the tail: least-loaded routing has the lowest TTFT p99, because it never queues behind a busy replica that holds the prefix.
* **The autoscaler follows the step.** It launches replicas at once when 24 clients arrive, and retires them one per tick after load has stayed low for the stabilisation window. Draining replicas finish their in-flight requests first: no request failed and none was shed.
* **Scaling down has a cost the autoscaler does not see.** With 2 clients, 4 replicas complete about 22 requests/s and 1 replica about 5/s. Each replica adds its own KV cache: with 4, the 16 prefixes mostly stay cached and each request gets a CPU thread of its own; on 1 they are evicted again. The autoscaler sizes the pool by requests in flight only ([limitations](limitations.md)). A cache-aware policy would weigh hit rate and latency too.

