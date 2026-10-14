# Testing

`make test` builds and runs two programs in under 20 s on 4 vCPUs.

## `tests/test_tracer.cpp` (20 checks)

These pin the access model to patterns with known answers:

* global memory:
  * consecutive floats need 4 sectors;
  * stride 2 is 50 % efficient;
  * stride 32 needs one sector per lane;
  * a 4-byte misalignment needs 5 sectors;
  * float4 accesses need 16 sectors;
  * a broadcast needs 1 sector;
  * a predicated access forms one request per warp;
* shared memory:
  * strides 1 and 33 are conflict-free;
  * stride 2 is a 2-way conflict and stride 32 a 32-way conflict;
  * a broadcast is conflict-free;
  * consecutive float4s take 4 wavefronts, and float4s at stride 2 are a 2-way conflict.

## `tests/test_kernels.cpp` (100 checks)

Every kernel runs on the emulator and is compared with a double-precision reference.

| Kernel | Shapes | Tolerance |
|---|---|---|
| reductions | n = 1 … 2²⁰, odd sizes | fp32 error bound (serial terms + tree depth) × Σ\|x\|; the two-pass variant is bit-identical across runs |
| transpose (3 variants) | 1×1, 32×32, 33×65, 100×7, 256×512 | exact |
| SGEMM (4 variants) | 1×1×1, 128×128×8, 129×130×17, 64×300×64 (α = 0.5, β = 2), 256³ (β = 1), 5×257×3 (α = −1) | per element: (K + 2) · 6·10⁻⁸ · Σ\|aᵢₖbₖⱼ\| |
| softmax | 1×1 … 64×4096, values σ = 10, one 3·10³⁸, one −inf, all −inf row → NaN | 10⁻⁵ relative |
| LayerNorm | 1×1 … 8×768, 4×4096, mean 10⁴ | 10⁻⁴ + input-rounding term |
| attention D = 32, 64 | N = 1, 37, 130, 200; causal and not; BH up to 3 | 2·10⁻⁵ absolute |

The suite also checks two things about the naive LayerNorm (E[x²] − E[x]²): it must break down at mean 10⁴ (error more than 100× the bound), and it must pass on centred data. This shows why the Welford version exists.

**Mutation checks**, done by hand. Each of these changes makes the suite fail:

* removing the online-softmax rescale in attention or in softmax;
* breaking the Welford merge;
* removing a `__syncthreads()` from the tiled SGEMM (a race the emulator exposes);
* an off-by-one column in the split SGEMM epilogue;
* the causal key range in flash attention;
* dropping a tail element in the vectorised reduction.

## Device compilation (`make ptx`)

All 17 kernel instantiations compile with clang 18 and ptxas 12.9 for sm_80 and sm_90, with zero stack and zero spills. CI runs this too.

## Not tested

* **Execution on a GPU.** There is no GPU here. `bench/gpu_bench.cu` checks GEMM results against the CPU when run on one, but it has never run; see [limitations](limitations.md).
* **Divergent warps and memory ordering.** The emulator runs each thread to completion between barriers. It does not reproduce warp-divergent scheduling or the GPU's weaker memory ordering outside barriers. The kernels here only communicate through barriers and shuffles.
