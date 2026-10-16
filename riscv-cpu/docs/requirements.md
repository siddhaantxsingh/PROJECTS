# Requirements matrix — Project 11 (RISC-V CPU)

| Requirement | Status | Evidence |
|---|---|---|
| RV32I base ISA | VERIFIED | riscv-tests rv32ui 41/42 (ma_data expected fail: misaligned traps); random lockstep; Spike cross-check |
| M extension | VERIFIED | rv32um 8/8; random MUL/DIV with corner operands; Spike cross-check |
| Zicsr / machine-mode CSRs | VERIFIED | rv32mi 15/16 (pmpaddr expected fail: no PMP entries); random CSR ops vs Spike |
| Exceptions (precise) | VERIFIED | illegal, ecall, ebreak, misaligned fetch/load/store in random tests and rv32mi, checked against ISS and Spike |
| Interrupts (software/timer/external, direct + vectored) | VERIFIED | `tests/irq_test.S`, ~12k random interrupts per seed × 8 seeds in lockstep |
| 5-stage pipeline with forwarding and hazards | VERIFIED | hazard-biased random programs; load-use / divider stall counters in benchmarks |
| Branch prediction | VERIFIED | BTB+BHT; ablation (CoreMark 2.87 vs 2.43 per MHz); 4-entry-BTB and disabled variants pass all random tests |
| Reference model + lockstep | VERIFIED | `tb/iss.cpp` + harness; ISS itself validated by riscv-tests and Spike |
| Spike comparison | VERIFIED | 400/400 random programs on the idealised core (335,326 events), on two cached configurations (336,292 and 339,210 events) and on the single-cycle baseline (335,326); `docs/evidence/2026-10-15/spike-compare*.log`. 62/64 riscv-tests (2 implementation-defined CSR differences, measured 2026-10-13) |
| Benchmarks (CoreMark, Dhrystone, riscv-tests) | VERIFIED (simulation) | docs/benchmarks.md; CoreMark CRCs validated; not an official CoreMark score (run < 10 s) |
| Synthesis | VERIFIED | Yosys synth_ecp5: 6,348 LUT4, 1,647 FF, 92 LUT-RAM, 4 DSP (2026-10-13); sky130hd synthesis in docs/timing.md |
| Timing / Fmax | PARTIAL | sky130hd reduced flow (Yosys → OpenROAD place/CTS/global route → OpenSTA): `rv_core` meets 23 ns (43.5 MHz), single-cycle meets 26 ns (38.5 MHz), core+256 B caches meets 23 ns; no detailed routing/DRC (ORFS image did not fit on disk). docs/timing.md, flow/results/, `docs/evidence/2026-10-15/pd-sweep*.log` |
| Area / power | PARTIAL | placed std-cell area 328,883 µm² (core), 152,845 µm² (single-cycle), 590,858 µm² (with caches as flops); vectorless power 50 / 14 / 53 mW; same flow and caveats |
| Gate-level simulation | NOT IMPLEMENTED | not attempted |
| Formal verification | NOT IMPLEMENTED | no riscv-formal/SymbiYosys available |
| Coverage measurement | NOT IMPLEMENTED | — |
| Caches / bus interface | VERIFIED (simulation) | `rtl/rv_cache.sv`: I-cache + write-through D-cache with write buffer, DM or 2-way, one memory bus with configurable latency; idealised configuration kept. riscv-tests 64/64 + 2 xfail, 400 random programs, directed cache test, interrupts on 3 configurations (`docs/evidence/2026-10-15/test-cache-all.log`); docs/caches.md |
| Cache performance study | VERIFIED (simulation) | 4 cache configs × latency 1/10/20/50 × 6 programs, all in lockstep; docs/caches_results.md, `results/2026-10-15/cache_sweep.jsonl` |
| Cache bug injection | VERIFIED | 9/9 hand-written cache mutants caught (`docs/evidence/2026-10-15/mutants.log`) |
| FENCE.I / self-modifying code | VERIFIED | with FENCE.I: lockstep-checked in cache_test and rv32ui-p-fence_i; without: stale or new depending on residency, documented and pinned by `make test-smc` |
| Single-cycle baseline | VERIFIED (simulation) | `rtl/rv_single.sv`: riscv-tests 64/64 + 2 xfail, 400 random programs, interrupts, Spike 400/400 (`docs/evidence/2026-10-15/test-single.log`); CoreMark 3.19/MHz |
| FPGA deployment | NOT IMPLEMENTED | no board run; steps in docs/OWNER_ACTIONS.md |
| Out-of-order extension | NOT IMPLEMENTED | out of time; optional in scope |
| Privilege modes U/S, MMU, PMP | NOT IMPLEMENTED | M-mode only |
| CI | VERIFIED | `.github/workflows/riscv-cpu.yml`: lint, riscv-tests, random, interrupts, predictor variants, cached configurations, single-cycle baseline, benchmarks, synthesis, and Spike comparison of idealised/cached/single-cycle builds. Latest run id in the final report / README |
