#!/bin/sh
# bench.sh SIM_DIR... : build the riscv-tests benchmarks and CoreMark, run
# each on every simulator binary given (e.g. obj_dir and obj_dir_nobp), with
# lockstep checking on, and write build/bench/<sim>/<prog>.json.
set -e
CC=${RISCV_PREFIX:-riscv64-unknown-elf-}gcc
RT=${RVTESTS:-third_party/riscv-tests}/benchmarks
PICO=${PICOLIBC_INCLUDE:-/usr/lib/picolibc/riscv64-unknown-elf/include}
CM=${COREMARK:-third_party/coremark}
ITER=${COREMARK_ITERATIONS:-10}
OUT=build/bench
mkdir -p $OUT/elf
ARCH="-march=rv32im_zicsr_zifencei -mabi=ilp32"
CFLAGS="-O2 -static -mcmodel=medany -std=gnu99 -ffast-math -fno-common -fno-builtin-printf -fno-tree-loop-distribute-patterns -DPREALLOCATE=1"
for b in dhrystone median multiply qsort rsort spmv towers vvadd memcpy; do
  $CC $ARCH $CFLAGS -isystem $PICO -I$RT/../env -I$RT/common -I$RT/$b -o $OUT/elf/$b.elf $RT/$b/*.c $RT/common/syscalls.c $RT/common/crt.S \
      -nostdlib -nostartfiles -lgcc -T $RT/common/test.ld
done
$CC $ARCH -O2 -static -ffreestanding -fno-builtin -std=gnu99 -Isw/coremark -I$CM -DITERATIONS=$ITER -DPERFORMANCE_RUN=1 \
    -DFLAGS_STR="\"-O2 -march=rv32im\"" -o $OUT/elf/coremark.elf sw/crt0.S sw/lib/rt.c sw/coremark/core_portme.c \
    $CM/core_list_join.c $CM/core_main.c $CM/core_matrix.c $CM/core_state.c $CM/core_util.c \
    -nostdlib -nostartfiles -lgcc -T sw/link.ld
for sim in "$@"; do
  name=$(basename $(dirname $sim))
  mkdir -p $OUT/$name
  for elf in $OUT/elf/*.elf; do
    p=$(basename $elf .elf)
    $sim --stats $OUT/$name/$p.json $elf > $OUT/$name/$p.out 2>$OUT/$name/$p.err || { cat $OUT/$name/$p.err; exit 1; }
    tail -1 $OUT/$name/$p.err
  done
done
