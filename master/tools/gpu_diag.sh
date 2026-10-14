#!/usr/bin/env bash
# One-command GPU/toolchain diagnostics for the owner's machine (WSL2 Ubuntu or native Linux).
# Usage: bash master/tools/gpu_diag.sh            -> writes gpu_diag_<date>.txt in the current directory
set -u
out="gpu_diag_$(date +%Y%m%d_%H%M%S).txt"
run() { echo "### $*"; "$@" 2>&1 || echo "(exit $?)"; echo; }
{
  echo "# GPU diagnostics $(date -Is)"
  run uname -a
  run cat /etc/os-release
  run grep -i microsoft /proc/version
  run nproc
  run free -h
  run df -h .
  run nvidia-smi
  run nvidia-smi --query-gpu=name,driver_version,compute_cap,memory.total,power.limit,temperature.gpu,clocks.max.sm --format=csv
  run nvcc --version
  run bash -c 'ls -d /usr/local/cuda*'
  run docker version --format '{{.Server.Version}}'
  run docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi -L
  run go version
  run cargo --version
  run python3 --version
  run node --version
} > "$out"
echo "wrote $out"
grep -A3 "query-gpu" "$out" | tail -2
