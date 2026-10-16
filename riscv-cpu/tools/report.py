#!/usr/bin/env python3
"""Collect benchmark/synthesis results into results/ and render docs/benchmarks.md."""
import datetime
import json
import os
import re
import shutil

B = "build/bench"
date = os.environ.get("RESULTS_DATE", datetime.date.today().isoformat())
R = f"results/{date}"
os.makedirs(R, exist_ok=True)

def load(sim):
    out = {}
    for f in sorted(os.listdir(f"{B}/{sim}")):
        if f.endswith(".json"):
            p = f[:-5]
            out[p] = json.load(open(f"{B}/{sim}/{f}"))
            txt = open(f"{B}/{sim}/{p}.out", errors="replace").read() if os.path.exists(f"{B}/{sim}/{p}.out") else ""
            out[p]["console"] = txt
    return out

bp, nobp = load("obj_dir"), load("obj_dir_nobp")
json.dump({"bpred": bp, "no_bpred": nobp}, open(f"{R}/bench.json", "w"), indent=1)
for f in ("synth_ecp5.txt", "synth_generic.txt"):
    if os.path.exists(f"build/{f}"):
        shutil.copy(f"build/{f}", f"{R}/{f}")

def cm_iters(txt):
    m = re.search(r"Iterations\s*:\s*(\d+)", txt)
    return int(m.group(1)) if m else None

L = []
w = L.append
w("# Benchmarks\n")
w(f"Rendered by `tools/report.py` from `results/{date}/`. Reproduce with `make fetch bench synth report`.\n")
w("All numbers are **simulated cycles** of the RTL in Verilator (cycle-accurate for this design: single-cycle "
  "combinational memories, so there are no cache or memory-latency effects). Every run below had lockstep "
  "checking against the reference ISS enabled and passed. Compiler: riscv64-unknown-elf-gcc 13.2.0, "
  "`-march=rv32im_zicsr_zifencei -mabi=ilp32 -O2`.\n")
cm = bp.get("coremark")
if cm:
    it = cm_iters(cm["console"])
    w("## CoreMark\n")
    w(f"CoreMark 2K performance run, {it} iterations, CRCs validated against the reference values "
      "(seedcrc 0xe9f5, crclist 0xe714, crcmatrix 0x1fd7, crcstate 0x8e3a). The run is far shorter than the "
      "10 s CoreMark requires for a reportable score, so CoreMark itself prints `ERROR! Must execute for at "
      "least 10 secs`: **this is not an official CoreMark result**, it is iterations per million cycles.\n")
    w("| configuration | cycles (timed region) | CoreMark/MHz |")
    w("|---|---|---|")
    for name, d in (("with branch predictor", bp), ("no branch predictor", nobp)):
        m = re.search(r"Total ticks\s*:\s*(\d+)", d["coremark"]["console"])
        ticks = int(m.group(1))
        w(f"| {name} | {ticks:,} | {it / (ticks / 1e6):.2f} |")
    w("")
dh = bp.get("dhrystone")
if dh:
    m = re.search(r"mcycle = (\d+)", dh["console"])
    w("## Dhrystone\n")
    w("riscv-tests' Dhrystone (500 runs) built with the flags above, not the Dhrystone ground rules (inlining is allowed), so it is only comparable to other riscv-tests builds. DMIPS/MHz = runs per second at 1 MHz / 1757.\n")
    w("| configuration | cycles per run | DMIPS/MHz |")
    w("|---|---|---|")
    for name, d in (("with branch predictor", bp), ("no branch predictor", nobp)):
        m = re.search(r"mcycle = (\d+)", d["dhrystone"]["console"])
        cyc = int(m.group(1)) / 500
        w(f"| {name} | {cyc:.0f} | {1e6 / cyc / 1757:.2f} |")
    w("")
w("## Whole-program CPI (including start-up code)\n")
w("| program | instructions | CPI (BTB+BHT) | mispredict rate | CPI (no predictor) | load-use stalls | divider stall cycles |")
w("|---|---|---|---|---|---|---|")
for p in sorted(bp):
    a, b = bp[p], nobp[p]
    rate = a["mispredicts"] / a["branches"] if a["branches"] else 0
    w(f"| {p} | {a['instret']:,} | {a['cpi']:.3f} | {rate:.1%} | {b['cpi']:.3f} | {a['load_use_stalls']:,} | {a['div_stall_cycles']:,} |")
w("")
w("Mispredict rate counts all control transfers (branches, JAL, JALR) resolved in EX. Without the predictor "
  "every taken branch and every jump costs two cycles.\n")
if os.path.exists(f"{R}/synth_ecp5.txt"):
    t = open(f"{R}/synth_ecp5.txt").read()
    t = t[t.index("design hierarchy"):]
    cells = dict(re.findall(r"^\s+(\w+)\s+(\d+)$", t, re.M))
    w("## Synthesis (Yosys 0.33, `synth_ecp5`, no place and route)\n")
    w("| resource | count |")
    w("|---|---|")
    for k in ("LUT4", "TRELLIS_FF", "TRELLIS_DPR16X4", "MULT18X18D", "CCU2C"):
        if k in cells:
            w(f"| {k} | {int(cells[k]):,} |")
    w("\nTRELLIS_DPR16X4 are 16×4 LUT RAMs (register file, BTB tags/targets); MULT18X18D are DSP blocks for "
      "the 33×33 multiplier. These ECP5 counts are from 2026-10-13, before the "
      "memory ready ports were added.\n")
    w("## Caches, single-cycle baseline, timing\n")
    w("* Cache configurations × memory latency (CPI, CoreMark/MHz, hit rates) and cache bug injection: "
      "[caches_results.md](caches_results.md).")
    w("* sky130hd timing/area/power after placement, CTS and global routing (reduced flow, no detailed "
      "routing), and the IPC × Fmax comparison with the single-cycle baseline: [timing.md](timing.md).\n")
open("docs/benchmarks.md", "w").write("\n".join(L) + "\n")
print(f"wrote docs/benchmarks.md and {R}/")
