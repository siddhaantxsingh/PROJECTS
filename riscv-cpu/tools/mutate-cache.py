#!/usr/bin/env python3
"""Bug injection for the caches: does the test suite notice?

Each mutant is a one-line change to rtl/rv_cache.sv (applied to a copy under
build/mut/, the source is not touched). Each is built as the cached
simulator (2-way, 16 sets, 2-entry write buffer, 10-cycle memory) and run on
a short suite: the directed cache test, 60 random programs, the interrupt test
and Dhrystone (which uses HTIF syscalls, i.e. the uncached window and the
snoop port). A mutant is "killed" if any program fails, mismatches the ISS,
or times out. Usage: tools/mutate-cache.py [--out FILE]
"""
import glob, json, os, subprocess, sys

MUTANTS = [
    ("store hit does not update the D-cache line",
     "        if (store_ok && cacheable && hit)\n", "        if (1'b0 && store_ok && cacheable && hit)\n"),
    ("D-cache refill does not wait for the write buffer",
     "wire start_rd  = load_miss && wb_empty && !snoop_inv;", "wire start_rd  = load_miss && !snoop_inv;"),
    ("FENCE.I does not invalidate the I-cache",
     "            if (flush) begin\n", "            if (1'b0) begin\n"),
    ("D-cache tag compare uses only 4 tag bits",
     "            if (valid[w*SETS + 32'(idx)] && tags[w][idx] == tag) begin\n                hit = 1'b1; hit_way = w[0]; hit_data",
     "            if (valid[w*SETS + 32'(idx)] && tags[w][idx][3:0] == tag[3:0]) begin\n                hit = 1'b1; hit_way = w[0]; hit_data"),
    ("write buffer head never advances (pops lost)",
     "            if (pop)  q_head <= q_head + 1'b1;", "            if (1'b0)  q_head <= q_head + 1'b1;"),
    ("HTIF window cached (only addr[31] decides)",
     "wire cacheable = addr[31] && addr[31:6] != io_base && addr[31:6] != io_base + 26'd1;", "wire cacheable = addr[31];"),
    ("I-cache victim stays valid while it is refilled",
     "                    valid[victim*SETS + 32'(idx)] <= 1'b0;  // its data is overwritten beat by beat\n", "\n"),
    ("D-cache refill installs the line one beat early",
     "                        if (beat == WB'(LINE_WORDS - 1)) begin\n                            state <= IDLE;\n                            if (!cancel && !(snoop_inv",
     "                        if (beat == WB'(LINE_WORDS - 2)) begin\n                            state <= IDLE;\n                            if (!cancel && !(snoop_inv"),
    ("snoop invalidation ignored",
     "            if (snoop_inv) begin\n                for", "            if (1'b0) begin\n                for"),
]
CFG = dict(I_SETS=16, I_WAYS=2, D_SETS=16, D_WAYS=2, LINE_WORDS=4, WB_DEPTH=2)


def build(src_dir, mdir):
    A = os.path.abspath
    rtl = [A("rtl/rv_alu.sv"), A("rtl/rv_decode.sv"), A("rtl/rv_core.sv"), A(os.path.join(src_dir, "rv_cache.sv"))]
    g = [f"-G{k}={v}" for k, v in CFG.items()]
    d = " ".join(f"-D{k}_SIM={v}" for k, v in CFG.items())
    cmd = ["verilator", "--cc", "--exe", "--build", "-j", "4", "-O3", "--x-assign", "fast", "--x-initial", "fast",
           "--Mdir", mdir, "-Wno-fatal", "-Wno-lint", "-Wno-style", "--assert", *g, "-CFLAGS", f"-O2 -std=c++17 -DCACHED {d}",
           "--top-module", "rv_top", *rtl, A("tb/sim_main.cpp"), A("tb/iss.cpp")]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("build failed:\n" + r.stdout[-2000:] + r.stderr[-2000:])
    return os.path.join(mdir, "Vrv_top")


def suite(sim):
    progs = [("build/cache/cache_test.elf", [])] + [(p, []) for p in sorted(glob.glob("build/rand/*.elf"))[:60]]
    progs += [("build/irq/irq_test.elf", ["--irq", "1", "--irq-period", "256"]),
              ("build/bench/elf/dhrystone.elf", [])]
    for elf, extra in progs:
        r = subprocess.run([sim, "--quiet", "--mem-latency", "10", "--max-cycles", "20000000", *extra, elf],
                           capture_output=True, text=True, timeout=900)
        if r.returncode != 0:
            return os.path.basename(elf), r.stderr.strip().splitlines()[-1][:160]
    return None, None


def main():
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else "build/mutants.json"
    src = open("rtl/rv_cache.sv").read()
    os.makedirs("build/mut", exist_ok=True)
    results = []
    sim = build("rtl", "build/mut/obj_orig")
    first, why = suite(sim)
    print(f"unmutated: {'PASS' if first is None else 'FAIL ' + first + ' ' + why}", flush=True)
    if first is not None:
        sys.exit(1)
    for i, (name, a, b) in enumerate(MUTANTS):
        assert src.count(a) == 1, name
        d = f"build/mut/m{i}"
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, "rv_cache.sv"), "w").write(src.replace(a, b))
        sim = build(d, os.path.join(d, "obj"))
        first, why = suite(sim)
        res = dict(mutant=name, killed=first is not None, first_failing=first, message=why)
        results.append(res)
        print(json.dumps(res), flush=True)
        subprocess.run(["rm", "-rf", os.path.join(d, "obj")])
    killed = sum(r["killed"] for r in results)
    print(f"killed {killed}/{len(results)} mutants")
    json.dump(results, open(out, "w"), indent=1)


if __name__ == "__main__":
    main()
