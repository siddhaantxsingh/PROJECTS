# Requirements matrix — Project 8 (CUDA kernels)

| Requirement | Status | Evidence |
|---|---|---|
| Reduction, transpose, SGEMM, softmax, LayerNorm, attention kernels | VERIFIED (functional, on emulator) | `tests/test_kernels.cpp`: 100 checks vs double references, many shapes |
| Optimisation progressions (coalescing, shared memory, bank conflicts, register blocking, vectorisation, warp shuffles, online softmax, IO-aware attention) | VERIFIED (modelled) | `docs/analysis.md`: traced sectors / conflicts per variant |
| Kernels compile as real CUDA for current GPUs | VERIFIED | clang 18 + ptxas 12.9, sm_80 and sm_90, no spills (`make ptx`, CI) |
| Resource usage and occupancy | VERIFIED (ptxas + calculator) | `results/resources.json`, `docs/analysis.md` |
| GPU benchmark harness | IMPLEMENTED BUT UNVERIFIED | `bench/gpu_bench.cu` links; never run (no GPU) |
| Measured GPU performance / comparison with cuBLAS | BLOCKED | no GPU in the build environment |
| Tensor-core (fp16/bf16/FP8) kernels | NOT IMPLEMENTED | — |
| Backward passes | NOT IMPLEMENTED | — |
| CI | VERIFIED | `.github/workflows/cuda-kernels.yml` (run 38525669876 green) |
