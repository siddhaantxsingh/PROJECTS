# Evidence index (2026-10-15/16)

The build machine was shared and restarted several times during this work. Some logs record runs that were cut short; they are kept as-is.

| log | what it shows |
|---|---|
| test-ideal.log, test-ideal-final.log | idealised core: lint, riscv-tests, random (400), interrupts, predictor variants |
| test-cache-all.log | 3 cache configurations, final RTL: riscv-tests, 400 random + directed cache test, interrupts, self-modifying code (all rc=0) |
| test-single.log | single-cycle baseline: riscv-tests, 400 random + cache test, interrupts |
| spike-compare.log | idealised core vs Spike, 400/400 |
| spike-compare-cached-single.log | two cached builds and the single-cycle core vs Spike, 400/400 each. An earlier, killed attempt (which included the directed cache test, which Spike cannot run) left a traceback/`rc=1` fragment at the top of an older version; the final file is from the clean rerun |
| mutants.log | 9/9 injected cache bugs caught (final RTL) |
| cache-sweep.log, cache-sweep-recheck.log | cache sweep (results/2026-10-15/cache_sweep.jsonl); the recheck reproduces 4 points with identical cycles after the last RTL edit |
| pd-sweep1.log | first PD sweep: only rv_core 20 ns finished; the rest failed when the machine restarted |
| pd-sweep2.log | rv_single 30/26 ns and rv_core 21.5/23 ns; rv_top failed because the OpenROAD install was deleted from the shared scratch area |
| pd-sweep3.log | rv_top 20/23 ns after reinstalling OpenROAD |
