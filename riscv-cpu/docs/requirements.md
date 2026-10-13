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
| Spike comparison | VERIFIED | 250/250 random programs, 209,869 events; 62/64 riscv-tests (2 implementation-defined CSR differences) |
| Benchmarks (CoreMark, Dhrystone, riscv-tests) | VERIFIED (simulation) | docs/benchmarks.md; CoreMark CRCs validated; not an official CoreMark score (run < 10 s) |
| Synthesis | VERIFIED (resources only) | Yosys synth_ecp5: 6,348 LUT4, 1,647 FF, 92 LUT-RAM, 4 DSP |
| Timing / Fmax | NOT IMPLEMENTED | no P&R or STA tool |
| Formal verification | NOT IMPLEMENTED | no riscv-formal/SymbiYosys available |
| Coverage measurement | NOT IMPLEMENTED | — |
| Caches / bus interface | NOT IMPLEMENTED | idealised single-cycle memories |
| Privilege modes U/S, MMU, PMP | NOT IMPLEMENTED | M-mode only |
| CI | VERIFIED | `.github/workflows/riscv-cpu.yml` green on GitHub Actions (run 38482941592), including the Spike cross-check job |
