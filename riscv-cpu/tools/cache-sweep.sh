#!/bin/sh
# cache-sweep.sh OUT.jsonl : run CoreMark and the riscv-tests benchmarks on the
# idealised-memory core and on several cache configurations x memory
# latencies, all with lockstep checking on. One JSON line per run (the
# harness's --stats output plus program/config fields). Needs `make bench`
# to have built build/bench/elf/*.elf.
set -e
OUT=${1:-build/cache_sweep.jsonl}
PROGS=${PROGS:-"coremark dhrystone qsort spmv towers memcpy"}
CONFIGS=${CONFIGS:-"16:1:16:1:4:4 64:1:64:1:4:4 32:2:32:2:4:4 256:1:256:1:4:4"}
LATS=${LATS:-"1 10 20 50"}
mkdir -p build/sweep
: > "$OUT"
run() {  # sim config latency prog [args]
  sim=$1; cfg=$2; lat=$3; p=$4; shift 4
  st=build/sweep/$p.json
  $sim --stats $st "$@" build/bench/elf/$p.elf > build/sweep/$p.out 2>build/sweep/$p.err || { cat build/sweep/$p.err; exit 1; }
  ticks=$(sed -n 's/.*Total ticks *: *\([0-9]*\).*/\1/p' build/sweep/$p.out | head -1)
  python3 -c "import json,sys; d=json.load(open('$st')); d.update(program='$p', config='$cfg', latency=$lat, coremark_ticks=int('${ticks:-0}') or None); print(json.dumps(d))" >> "$OUT"
  tail -1 build/sweep/$p.err
}
make -s obj_dir/Vrv_core obj_dir_single/Vrv_core
for p in $PROGS; do run obj_dir/Vrv_core ideal 0 $p; run obj_dir_single/Vrv_core single 0 $p; done
for c in $CONFIGS; do
  make -s CACHE=$c sim-cache
  sim=obj_dir_c$(echo $c | tr : _)/Vrv_top
  for l in $LATS; do for p in $PROGS; do run $sim $c $l $p --mem-latency $l; done; done
done
