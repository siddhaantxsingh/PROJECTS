#!/usr/bin/env bash
# Clock sweep with pd_lite.sh: one JSON line per (top, period) appended to
# flow/results/pd_lite_sky130hd.jsonl; the worst-path report and logs are
# copied to flow/results/<top>_p<period>/ (small text files only).
# Usage: flow/scripts/sweep_lite.sh <top> <period_ns>...
set -uo pipefail
top=$1; shift
flow=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$flow/results"
for p in "$@"; do
  d=$flow/build/${top}_p$p
  if line=$("$flow/scripts/pd_lite.sh" "$top" "$p" "$d" | tail -1); then
    echo "$line" | tee -a "$flow/results/pd_lite_sky130hd.jsonl"
    r=$flow/results/${top}_p$p; mkdir -p "$r"
    cp "$d/synth_area.txt" "$d/synth.ys" "$d/pd.tcl" "$r/"
    sed -n '/=== worst setup path/,/^Design area/p' "$d/pd.log" > "$r/timing_area.rpt"
    sed -n '/^Group  /,/^Total /p' "$d/pd.log" > "$r/power.rpt"
    rm -f "$d/netlist.v.gz"; gzip -kf "$d/netlist.v"
  else
    echo "pd_lite failed for $top $p" >&2
  fi
done
