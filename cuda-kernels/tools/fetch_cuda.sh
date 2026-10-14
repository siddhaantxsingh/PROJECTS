#!/bin/sh
# Assemble a minimal CUDA toolkit root (headers, libdevice, ptxas, cudart)
# from NVIDIA's PyPI wheels into build/cuda-root. No GPU or driver needed.
set -eu
V=${CUDA_WHEEL_VERSION:-12.9}
D=build/cuda-wheels
R=build/cuda-root
rm -rf "$D" "$R"
mkdir -p "$D" "$R/include" "$R/lib64"
python3 -m pip download --no-deps -d "$D" "nvidia-cuda-nvcc-cu12==$V.*" "nvidia-cuda-runtime-cu12==$V.*" \
  "nvidia-cuda-cccl-cu12==$V.*" "nvidia-nvfatbin-cu12==$V.*" "nvidia-curand-cu12" >/dev/null
for w in "$D"/*.whl; do python3 -m zipfile -e "$w" "$D/x"; done
for p in cuda_runtime cuda_nvcc cuda_cccl curand; do cp -r "$D/x/nvidia/$p/include/." "$R/include/"; done
cp -r "$D/x/nvidia/cuda_nvcc/bin" "$D/x/nvidia/cuda_nvcc/nvvm" "$R/"
cp "$D/x/nvidia/cuda_runtime/lib/"* "$D/x/nvidia/nvfatbin/lib/"* "$R/lib64/"
cp "$D/x/nvidia/nvfatbin/include/"* "$R/include/"
chmod +x "$R/bin/"*
# fatbinary is not in the wheels: build a stand-in on libnvfatbin
cc -O2 -I"$R/include" tools/fatbinary_shim.c -o "$R/bin/fatbinary" -L"$R/lib64" -l:libnvfatbin.so.12 -Wl,-rpath,"$(cd "$R/lib64" && pwd)"
echo "CUDA Version $V" > "$R/version.txt"
rm -rf "$D"
"$R/bin/ptxas" --version | tail -1
