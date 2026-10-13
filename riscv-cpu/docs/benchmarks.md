# Benchmarks

Rendered by `tools/report.py` from `results/2026-10-13/`. Reproduce with `make fetch bench synth report`.

All numbers are **simulated cycles** of the RTL in Verilator (cycle-accurate for this design: single-cycle combinational memories, so there are no cache or memory-latency effects). Every run below had lockstep checking against the reference ISS enabled and passed. Compiler: riscv64-unknown-elf-gcc 13.2.0, `-march=rv32im_zicsr_zifencei -mabi=ilp32 -O2`.

## CoreMark

CoreMark 2K performance run, 10 iterations, CRCs validated against the reference values (seedcrc 0xe9f5, crclist 0xe714, crcmatrix 0x1fd7, crcstate 0x8e3a). The run is far shorter than the 10 s CoreMark requires for a reportable score, so CoreMark itself prints `ERROR! Must execute for at least 10 secs`: **this is not an official CoreMark result**, it is iterations per million cycles.

| configuration | cycles (timed region) | CoreMark/MHz |
|---|---|---|
| with branch predictor | 3,488,920 | 2.87 |
| no branch predictor | 4,117,260 | 2.43 |

## Dhrystone

riscv-tests' Dhrystone (500 runs) built with the flags above, not the Dhrystone ground rules (inlining is allowed), so it is only comparable to other riscv-tests builds. DMIPS/MHz = runs per second at 1 MHz / 1757.

| configuration | cycles per run | DMIPS/MHz |
|---|---|---|
| with branch predictor | 468 | 1.22 |
| no branch predictor | 540 | 1.05 |

## Whole-program CPI (including start-up code)

| program | instructions | CPI (BTB+BHT) | mispredict rate | CPI (no predictor) | load-use stalls | divider stall cycles |
|---|---|---|---|---|---|---|
| coremark | 3,153,833 | 1.114 | 11.8% | 1.315 | 203,615 | 1,914 |
| dhrystone | 201,241 | 1.236 | 25.3% | 1.420 | 3,828 | 21,054 |
| median | 12,984 | 1.339 | 30.6% | 1.486 | 117 | 1,848 |
| memcpy | 33,836 | 1.082 | 1.7% | 1.270 | 123 | 2,376 |
| multiply | 45,105 | 1.120 | 10.3% | 1.614 | 123 | 2,376 |
| qsort | 229,561 | 1.143 | 17.0% | 1.429 | 6,561 | 2,904 |
| rsort | 367,904 | 1.009 | 1.0% | 1.082 | 129 | 2,904 |
| spmv | 1,605,596 | 1.057 | 22.6% | 1.142 | 2,129 | 2,904 |
| towers | 10,266 | 1.272 | 37.1% | 1.339 | 198 | 1,848 |
| vvadd | 8,821 | 1.254 | 8.7% | 1.456 | 117 | 1,848 |

Mispredict rate counts all control transfers (branches, JAL, JALR) resolved in EX. Without the predictor every taken branch and every jump costs two cycles.

## Synthesis (Yosys 0.33, `synth_ecp5`, no place and route)

| resource | count |
|---|---|
| LUT4 | 6,348 |
| TRELLIS_FF | 1,647 |
| TRELLIS_DPR16X4 | 92 |
| MULT18X18D | 4 |
| CCU2C | 465 |

TRELLIS_DPR16X4 are 16×4 LUT RAMs (register file, BTB tags/targets); MULT18X18D are DSP blocks for the 33×33 multiplier. No timing numbers: no place-and-route or static timing tool was available, so Fmax is unknown. The single-cycle 33×33 multiplier and the combinational memory reads are the likely critical paths.

