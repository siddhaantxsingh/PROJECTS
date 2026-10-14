# CUDA kernels (project 8)

This project has fp32 CUDA kernels written as optimisation progressions:

* reduction, transpose and SGEMM: from naive to register-blocked with conflict-free shared memory;
* online softmax and Welford LayerNorm;
* IO-aware ("flash") attention.

It also has a **CPU SIMT emulator** that runs the same source unchanged for testing and traces every memory access, and a toolchain that compiles the kernels for A100/H100 without a GPU.

**Status:**

* **Functionally verified** on the emulator.
* **Compiles as real CUDA** for sm_80 and sm_90, with no spills.
* **GPU performance is BLOCKED:** there is no GPU here, so no timings are claimed. See [limitations](docs/limitations.md).

## What the evidence shows

From [docs/analysis.md](docs/analysis.md). The memory figures come from modelled traces, not measurements.

| Kernel | Before → after |
|---|---|
| transpose: shared tile → padded | bank-conflict factor 16.5 → **1.0**, 100 % coalesced (naive: 22 %) |
| SGEMM: naive → 32×32 tiles → register-blocked | global bytes requested 69 MB → 4.5 MB → **1.3 MB** (256³) |
| SGEMM register-blocked: contiguous → split micro-tile | B-read conflicts 2-way → **none**; C stores 42 % → **100 %** coalesced |
| attention: unfused → flash | 142 MB → **2.4 MB** requested (N = 512, D = 64); 12.5 % → 100 % coalesced |
| ptxas, all 17 kernels, sm_80 + sm_90 | **0 bytes stack, 0 spills** (the first SGEMM and flash versions spilled 288 B and 640 B per thread to local memory until their loops were unrolled) |

## Usage

```sh
make test             # emulator: 20 tracer checks + 100 kernel checks vs double-precision references
make analyze          # traced memory behaviour per kernel (JSON)
tools/fetch_cuda.sh   # CUDA toolkit root from NVIDIA PyPI wheels (ptxas, headers, libdevice, cudart, libnvfatbin)
make ptx              # clang + ptxas for sm_80/sm_90 -> registers, smem, spills, occupancy
make gpu-bench        # links a real CUDA benchmark (needs a GPU to run)
python3 tools/report.py   # regenerate docs/analysis.md from results/
```

## Documentation

* [Design](docs/design.md): the kernels, the emulator and its access model, and the device toolchain.
* [Testing](docs/testing.md).
* [Analysis](docs/analysis.md).
* [Limitations](docs/limitations.md).
* [Requirements matrix](docs/requirements.md).
