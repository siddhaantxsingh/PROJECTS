# Benchmarks

Rendered by `python3 bench/report.py` from `bench/results/` and `synth/results/`. Reproduce with `make test test-n4 accuracy synth`.

## Throughput (cycle-exact RTL simulation; INT8 and FP8 identical)

Utilisation = M·N·K / (cycles × N²). The testbench asserts that the cycle counts equal the schedule (K + 3N + 3 per tile) exactly.

| array | M×N×K | cycles | MAC utilisation |
|---|---|---|---|
| 8×8 | 8×8×16 | 43 | 37.2 % |
| 8×8 | 8×8×256 | 283 | 90.5 % |
| 8×8 | 16×16×64 | 364 | 70.3 % |
| 8×8 | 64×64×256 | 18,112 | 90.5 % |
| 4×4 | 4×4×16 | 31 | 51.6 % |
| 4×4 | 4×4×256 | 271 | 94.5 % |
| 4×4 | 8×8×64 | 316 | 81.0 % |
| 4×4 | 64×64×256 | 69,376 | 94.5 % |

At a clock of f MHz, an 8×8 array delivers 64·f·utilisation MAC/s. There is no timing data, because no place-and-route was done.

## Accuracy of FP8 accumulation (32×256×32, Gaussian data quantised per tensor)

| format | accumulation | max error (ULP of FP32) | mean error (ULP) | max relative error |
|---|---|---|---|---|
| E4M3 | exact + one FP32 rounding (this design) | 0.5 | 0.010 | 5.94e-08 |
| E4M3 | sequential FP32 | 32.0 | 0.081 | 1.97e-06 |
| E4M3 | sequential FP16 | 2,307,072.0 | 53,437.401 | 2.58e-01 |
| E5M2 | exact + one FP32 rounding (this design) | 0.5 | 0.010 | 5.80e-08 |
| E5M2 | sequential FP32 | 8.0 | 0.037 | 5.82e-07 |
| E5M2 | sequential FP16 | overflow | overflow | overflow |

FP8 quantisation itself costs 3.6 % (E4M3) and 7.6 % (E5M2) mean error relative to the float64 GEMM. Accumulation error comes on top of that; with FP16 accumulation it dominates.

## Area (Yosys 0.33; no timing)

| block | generic cells | ECP5 LUT4 | ECP5 FF | ECP5 carry (CCU2C) | DSP |
|---|---|---|---|---|---|
| PE, INT8 only (32-bit accumulator) | 815 | 49 | 52 | 16 | 1 |
| PE, INT8 + FP8 (80-bit exact accumulator) | 2,358 | 1,031 | 100 | 90 | 1 |
| post unit (FP32 rounding + INT32 requantisation), per column | 12,478 | — | — | — | — |

Exact FP8 accumulation makes each PE about 21× larger in LUTs than an INT8-only PE. The 80-bit barrel shifter and adder dominate. The full `npu_top` was not synthesised: its 48 KiB of scratchpad are behavioural arrays and would need BRAM macros.

