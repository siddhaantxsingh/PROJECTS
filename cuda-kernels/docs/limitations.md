# Limitations

* **No GPU: performance is BLOCKED.**
  * Nothing here was timed on a GPU, and no GFLOP/s or GB/s figures are claimed.
  * `bench/gpu_bench.cu` is the harness that would produce them. It builds and links (`make gpu-bench`) but has never run.
  * [analysis.md](analysis.md) has compiler resource reports and modelled memory behaviour instead.
* **The emulated metrics are a model.**
  * Sector counts and bank-conflict wavefronts follow the documented hardware rules. They ignore caches, replays, the L1/L2 hierarchy, instruction issue and scheduling.
  * They show *why* one variant should be faster, not *how much*.
* **fp32 only.** There are no fp16/bf16/FP8 kernels and no tensor cores (WMMA/MMA/WGMMA). A production GEMM or attention kernel on A100/H100 would use tensor cores and be many times faster than anything fp32 here.
* **The flash kernel is FlashAttention-1-style:**
  * one thread per query row;
  * 245 registers per thread, so 12.5 % occupancy;
  * no split-K / sequence parallelism and no backward pass.
* **The register-blocked SGEMM** has no double buffering (cp.async), no warp tiling and no autotuning of tile sizes.
* **The emulator** runs blocks sequentially and threads with OS barriers. It cannot reveal inter-block races, warp-divergence effects, or memory-ordering bugs outside barriers.
* **The toolchain is clang plus NVIDIA's ptxas and libnvfatbin** from PyPI, not nvcc. nvcc may make different register and unrolling decisions.
