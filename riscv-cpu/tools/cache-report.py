#!/usr/bin/env python3
"""Render the cache sweep (results/<date>/cache_sweep.jsonl) as Markdown tables.
Usage: cache-report.py results/2026-10-15/cache_sweep.jsonl > docs/caches_results.md"""
import json, sys
from collections import defaultdict

rows = [json.loads(l) for l in open(sys.argv[1])]
ITER = 10
by = defaultdict(dict)
for r in rows:
    by[(r["config"], r["latency"])][r["program"]] = r
configs = []
for r in rows:
    k = (r["config"], r["latency"])
    if k not in configs:
        configs.append(k)


def label(cfg):
    if cfg in ("ideal", "single"):
        return {"ideal": "idealised memory (pipelined)", "single": "single-cycle baseline (idealised memory)"}[cfg]
    i_s, i_w, d_s, d_w, lw, wb = map(int, cfg.split(":"))
    size = lambda s, w: s * w * lw * 4
    return f"I {size(i_s, i_w)} B {i_w}-way / D {size(d_s, d_w)} B {d_w}-way, {lw * 4} B lines, WB {wb}"


print("| configuration | memory latency | CoreMark/MHz | CoreMark CPI | I refills / 1k fetches | D refills / 1k accesses "
      "| imem stall % | dmem stall % | Dhrystone CPI | qsort CPI | spmv CPI |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
for cfg, lat in configs:
    d = by[(cfg, lat)]
    cm = d.get("coremark")
    if not cm:
        continue
    cmm = f"{ITER / (cm['coremark_ticks'] / 1e6):.2f}" if cm.get("coremark_ticks") else "-"
    cached = cm.get("cached")
    ir = f"{1000 * cm['i_refills'] / cm['fetches']:.1f}" if cached else "-"
    dr = f"{1000 * cm['d_refills'] / max(cm['dmem_accesses'], 1):.1f}" if cached else "-"
    ist = f"{100 * cm['imem_stall_cycles'] / cm['cycles']:.1f}" if cached else "-"
    dst = f"{100 * cm['dmem_stall_cycles'] / cm['cycles']:.1f}" if cached else "-"
    cpi = lambda p: f"{d[p]['cpi']:.3f}" if p in d else "-"
    print(f"| {label(cfg)} | {lat if cached else '0 (combinational)'} | {cmm} | {cpi('coremark')} | {ir} | {dr} | {ist} | {dst} "
          f"| {cpi('dhrystone')} | {cpi('qsort')} | {cpi('spmv')} |")
