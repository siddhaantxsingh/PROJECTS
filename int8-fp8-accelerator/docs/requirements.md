# Requirements matrix — Project 13 (INT8/FP8 accelerator)

| Requirement | Status | Evidence |
|---|---|---|
| Systolic GEMM array (parameterisable N) | VERIFIED | N = 8 and N = 4 pass all tests |
| INT8 GEMM with INT32 accumulation | VERIFIED | bit-exact vs model, extremes, partial tiles |
| INT8 requantisation (TFLite/gemmlowp) | VERIFIED | bit-exact incl. ReLU, zero point, bias |
| FP8 E4M3 and E5M2 (OCP) | VERIFIED | bit-exact incl. subnormals, NaN/inf, ties |
| Accuracy analysis | VERIFIED | `bench/accuracy.py`: 0.5 ULP vs 32 ULP (FP32) / overflow (FP16) |
| Performance model + measurement | VERIFIED | cycle-exact schedule; 90.5 % utilisation at K = 256 |
| Synthesis / area | PARTIALLY IMPLEMENTED | PE + post unit only; no timing |
| Golden model | VERIFIED | `model/npu_ref.py` + its own tests |
| Host interface | VERIFIED (simple port) | no AXI/DMA |
| CI | VERIFIED | `.github/workflows/int8-fp8-accelerator.yml` (run 38518768091 green) |
