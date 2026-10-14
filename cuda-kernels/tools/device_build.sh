#!/bin/sh
# Compile all kernels as real CUDA device code and report ptxas resource
# usage. Needs clang (>= 17) and a CUDA toolkit root (headers, libdevice,
# ptxas): CUDA_ROOT=/usr/local/cuda, or one assembled from the PyPI wheels by
# tools/fetch_cuda.sh. Usage: tools/device_build.sh OUT_DIR [ARCH...]
set -eu
OUT=${1:-build/device}
shift || true
ARCHS=${*:-sm_80 sm_90}
CUDA_ROOT=${CUDA_ROOT:-build/cuda-root}
mkdir -p "$OUT"
for arch in $ARCHS; do
  clang++ -x cuda --cuda-path="$CUDA_ROOT" --cuda-gpu-arch="$arch" --cuda-device-only -S -O3 -std=c++17 \
    -Wno-unknown-cuda-version -Iinclude tools/all_kernels.cu -o "$OUT/kernels_$arch.ptx"
  "$CUDA_ROOT/bin/ptxas" -arch="$arch" -O3 -v "$OUT/kernels_$arch.ptx" -o "$OUT/kernels_$arch.cubin" 2> "$OUT/ptxas_$arch.txt"
  echo "$arch: $(grep -c 'Compiling entry' "$OUT/ptxas_$arch.txt") kernels compiled"
done
