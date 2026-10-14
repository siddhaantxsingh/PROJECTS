"""Regression: every test over several seeds, merged functional coverage,
and bug-injection runs (each injected RTL bug must make the suite fail).

usage: python3 tools/regress.py [--seeds N] [--scale X] [--bugs] [--out results/DIR]
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

TB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tb")
BUGS = ["BUG_UART_PARITY", "BUG_UART_FIFO_DEPTH", "BUG_RX_SAMPLE", "BUG_UART_W1C", "BUG_UART_NOWAIT", "BUG_UART_BAUD_CLAMP",
        "BUG_TIMER_RELOAD", "BUG_TIMER_PRESC", "BUG_GPIO_W1C", "BUG_GPIO_EDGE_LOST", "BUG_INTC_MASK", "BUG_DECODE"]


def run(seed, scale, build, defines="", cov_dir=None, testcase=None):
    env = dict(os.environ, RANDOM_SEED=str(seed), TEST_SCALE=str(scale))
    if cov_dir:
        os.makedirs(cov_dir, exist_ok=True)
        env["COV_DIR"] = os.path.abspath(cov_dir)
    args = ["make", "-s", "SIM=verilator", f"SIM_BUILD={build}", f"BUG_DEFINES={defines}", f"COCOTB_RESULTS_FILE=results_{seed}.xml"]
    if testcase:
        args.append(f"TESTCASE={testcase}")
    t0 = time.time()
    p = subprocess.run(args, cwd=TB, env=env, capture_output=True, text=True)
    res = {}
    xml = os.path.join(TB, f"results_{seed}.xml")
    if os.path.exists(xml):
        for tc in ET.parse(xml).getroot().iter("testcase"):
            res[tc.get("name")] = "fail" if tc.find("failure") is not None or tc.find("error") is not None else "pass"
        os.remove(xml)
    if not res:
        res["<build or run>"] = "fail"
        sys.stderr.write(p.stdout[-3000:] + p.stderr[-3000:])
    return res, time.time() - t0, p.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", type=int, default=5)
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--bugs", action="store_true")
    ap.add_argument("--bug-seeds", type=int, default=2)
    ap.add_argument("--out", default="results/latest")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    report = {"seeds": [], "coverage": None, "bugs": []}
    merged = None
    ok = True
    for seed in range(1, a.seeds + 1):
        cov_dir = os.path.join(a.out, f"cov_seed{seed}")
        res, secs, _ = run(seed, a.scale, "sim_build", cov_dir=cov_dir)
        fails = [t for t, r in res.items() if r != "pass"]
        ok &= not fails
        report["seeds"].append({"seed": seed, "tests": res, "seconds": round(secs, 1)})
        print(f"seed {seed}: {sum(r == 'pass' for r in res.values())}/{len(res)} passed in {secs:.0f} s {fails or ''}", flush=True)
        for f in sorted(os.listdir(cov_dir)):
            c = json.load(open(os.path.join(cov_dir, f)))
            if merged is None:
                merged = {k: 0 for k in c["hits"]}
            for k, v in c["hits"].items():
                merged[k] += v
    hit = sum(1 for v in merged.values() if v)
    report["coverage"] = {"bins": len(merged), "hit": hit, "percent": 100 * hit / len(merged), "holes": [k for k, v in merged.items() if not v],
                          "hits": merged}
    print(f"merged functional coverage: {hit}/{len(merged)} bins ({100 * hit / len(merged):.1f} %); holes: {report['coverage']['holes']}")
    if a.bugs:
        for bug in BUGS:
            caught, detail = False, {}
            for seed in range(1, a.bug_seeds + 1):
                res, secs, out = run(seed, a.scale, f"sim_build_{bug.lower()}", f"+define+{bug}")
                failed = [t for t, r in res.items() if r != "pass"]
                detail[seed] = failed
                if failed:
                    caught = True
                    break
            report["bugs"].append({"bug": bug, "caught": caught, "failing_tests": detail})
            print(f"{bug:22} {'caught by ' + ', '.join(detail[max(detail)]) if caught else 'MISSED'}", flush=True)
            shutil.rmtree(os.path.join(TB, f"sim_build_{bug.lower()}"), ignore_errors=True)
        missed = [b["bug"] for b in report["bugs"] if not b["caught"]]
        print(f"bugs caught: {len(BUGS) - len(missed)}/{len(BUGS)} {missed or ''}")
        ok &= not missed
    json.dump(report, open(os.path.join(a.out, "regression.json"), "w"), indent=1)
    sys.exit(0 if ok else 1)


main()
