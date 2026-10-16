# Cache results

Rendered from `results/2026-10-15/cache_sweep.jsonl` by
`python3 tools/cache-report.py results/2026-10-15/cache_sweep.jsonl`.
Reproduce with `make bench` (to build the ELFs), then
`tools/cache-sweep.sh results/<date>/cache_sweep.jsonl`.

Every run below had lockstep checking against the ISS on, and passed. All
numbers are simulated cycles, so the shared, loaded machine (load averages
16-20 during the sweep) does not affect them. Two points (CoreMark,
1 KiB direct-mapped, latency 20 and 50) were re-run after the last RTL edit
and gave identical cycle counts (`docs/evidence/2026-10-15/cache-sweep-recheck.log`).

Memory model:
* reads: first word after *latency* cycles, then one word per cycle (a
  16-byte line costs latency + 3 cycles);
* writes: posted, 2 cycles of memory occupancy each.

Column definitions:
* **CoreMark/MHz:** 10 iterations per million cycles of CoreMark's timed
  region. This is not an official score.
* **CPI:** whole program, including start-up.
* **I refills / 1k fetches:** line refills, including wrong-path ones, per
  1000 instructions taken by IF.
* **D refills / 1k accesses:** load-miss line refills per 1000 loads and
  stores accepted.
* **stall %:** cycles in which IF waited for the I-cache, or MEM waited for
  the D-cache (refill, uncached read, full write buffer).

| configuration | memory latency | CoreMark/MHz | CoreMark CPI | I refills / 1k fetches | D refills / 1k accesses | imem stall % | dmem stall % | Dhrystone CPI | qsort CPI | spmv CPI |
|---|---|---|---|---|---|---|---|---|---|---|
| idealised memory (pipelined) | 0 (combinational) | 2.87 | 1.114 | - | - | - | - | 1.236 | 1.143 | 1.057 |
| single-cycle baseline (idealised memory) | 0 (combinational) | 3.19 | 1.001 | - | - | - | - | 1.105 | 1.013 | 1.002 |
| I 256 B 1-way / D 256 B 1-way, 16 B lines, WB 4 | 1 | 1.96 | 1.631 | 57.2 | 138.8 | 20.3 | 11.6 | 2.587 | 1.605 | 2.893 |
| I 256 B 1-way / D 256 B 1-way, 16 B lines, WB 4 | 10 | 1.31 | 2.428 | 57.2 | 138.8 | 34.5 | 19.8 | 4.604 | 2.296 | 5.684 |
| I 256 B 1-way / D 256 B 1-way, 16 B lines, WB 4 | 20 | 0.96 | 3.314 | 57.2 | 138.8 | 42.2 | 24.3 | 6.870 | 3.067 | 8.786 |
| I 256 B 1-way / D 256 B 1-way, 16 B lines, WB 4 | 50 | 0.53 | 5.978 | 57.2 | 138.8 | 51.6 | 29.8 | 13.774 | 5.388 | 18.091 |
| I 1024 B 1-way / D 1024 B 1-way, 16 B lines, WB 4 | 1 | 2.77 | 1.154 | 4.9 | 9.4 | 2.3 | 1.2 | 1.780 | 1.405 | 2.231 |
| I 1024 B 1-way / D 1024 B 1-way, 16 B lines, WB 4 | 10 | 2.63 | 1.215 | 4.9 | 9.4 | 5.6 | 2.8 | 2.535 | 1.793 | 4.008 |
| I 1024 B 1-way / D 1024 B 1-way, 16 B lines, WB 4 | 20 | 2.50 | 1.283 | 4.9 | 9.4 | 8.8 | 4.4 | 3.390 | 2.226 | 5.994 |
| I 1024 B 1-way / D 1024 B 1-way, 16 B lines, WB 4 | 50 | 2.16 | 1.489 | 4.9 | 9.4 | 16.7 | 8.5 | 6.085 | 3.526 | 11.973 |
| I 1024 B 2-way / D 1024 B 2-way, 16 B lines, WB 4 | 1 | 2.79 | 1.143 | 4.3 | 4.6 | 2.0 | 0.6 | 1.864 | 1.315 | 2.248 |
| I 1024 B 2-way / D 1024 B 2-way, 16 B lines, WB 4 | 10 | 2.69 | 1.189 | 4.3 | 4.6 | 4.9 | 1.5 | 2.685 | 1.571 | 4.058 |
| I 1024 B 2-way / D 1024 B 2-way, 16 B lines, WB 4 | 20 | 2.58 | 1.240 | 4.3 | 4.6 | 7.8 | 2.4 | 3.650 | 1.854 | 6.080 |
| I 1024 B 2-way / D 1024 B 2-way, 16 B lines, WB 4 | 50 | 2.30 | 1.395 | 4.3 | 4.6 | 15.4 | 4.8 | 6.561 | 2.707 | 12.174 |
| I 4096 B 1-way / D 4096 B 1-way, 16 B lines, WB 4 | 1 | 2.85 | 1.122 | 1.4 | 0.8 | 0.7 | 0.1 | 1.329 | 1.329 | 1.197 |
| I 4096 B 1-way / D 4096 B 1-way, 16 B lines, WB 4 | 10 | 2.82 | 1.135 | 1.4 | 0.8 | 1.6 | 0.3 | 1.437 | 1.607 | 1.415 |
| I 4096 B 1-way / D 4096 B 1-way, 16 B lines, WB 4 | 20 | 2.79 | 1.149 | 1.4 | 0.8 | 2.7 | 0.5 | 1.556 | 1.916 | 1.663 |
| I 4096 B 1-way / D 4096 B 1-way, 16 B lines, WB 4 | 50 | 2.69 | 1.193 | 1.4 | 0.8 | 5.6 | 1.1 | 1.918 | 2.844 | 2.435 |

## What the numbers say

* **Cache size matters most.**
  * At 20-cycle memory, CoreMark/MHz is 0.96 with 256-byte caches, 2.50
    with 1 KiB and 2.79 with 4 KiB.
  * The idealised core gets 2.87.
  * CoreMark's working set fits in a few KiB.
* **2-way helps CoreMark at 1 KiB, but not everything.**
  * At 20 cycles, 2-way gives 2.58 against 2.50 for direct-mapped, and halves
    the D refill rate.
  * Dhrystone and spmv are slightly worse with 2-way at the same capacity
    (fewer sets, and a 1-bit LRU).
* **Latency sensitivity drops as the hit rate rises.**
  * With 4 KiB caches, going from 1 to 50 cycles costs CoreMark 6 %.
  * With 256 B caches, it costs 73 %.
* **The write buffer is rarely the bottleneck for CoreMark.** For 1 KiB
  direct-mapped caches, write-buffer-full stall cycles were 102 / 164 / 384 /
  3,569 at latencies 1 / 10 / 20 / 50, out of 3.6-4.7 M cycles. Store-heavy
  code (memcpy, spmv) suffers more, because each store also costs a bus
  write.
* **Dhrystone and spmv have high CPI on small caches.** Their data sets are
  larger than the D-cache, and every HTIF `printf` goes through the uncached
  window, which drains the write buffer.

## Single-cycle baseline

The idealised-memory single-cycle core (`rv_single`) has CPI 1.001 on
CoreMark (3.19 CoreMark/MHz). It has no hazards, and DIV is the only
multi-cycle instruction. Per clock it beats the pipeline. The pipeline is
only worth having if its clock is faster; see [timing.md](timing.md).

## Bug injection (`tools/mutate-cache.py`, `results/2026-10-15/mutants.json`)

The suite: the directed cache test, 60 random programs, the interrupt test
and Dhrystone. The configuration: 2-way, 16 sets, 2-entry write buffer,
10-cycle memory, all in lockstep.

| injected bug | caught by |
|---|---|
| store hit does not update the D-cache line | cache_test (lockstep mismatch) |
| D-cache refill does not wait for the write buffer | cache_test |
| FENCE.I does not invalidate the I-cache | cache_test |
| D-cache tag compare uses only 4 tag bits | Dhrystone |
| write buffer head never advances | cache_test |
| HTIF window cached | Dhrystone |
| I-cache victim stays valid while it is refilled | cache_test |
| D-cache refill installs the line one beat early | cache_test |
| snoop invalidation ignored | Dhrystone |

**9/9 killed** (`docs/evidence/2026-10-15/mutants.log`). These are
hand-picked mutants, not a systematic mutation-coverage measurement.
