#!/usr/bin/env python3
"""Compare an rv_core retirement trace with a Spike commit log.

    spike-compare.py --spike PATH --sim obj_dir/Vrv_core prog.elf [prog.elf ...]

For each program: run the RTL simulator with --trace (lockstep off, so this
check is independent of the in-house ISS), run Spike with
--isa=rv32im_zicsr_zifencei_zicntr --priv=m -l --log-commits, and compare, in
order, every retired instruction (pc, encoding, destination register and
value, store address and data, load address) and every exception (epc and
cause). Values read from cycle counters are not compared.
"""
import argparse
import re
import subprocess
import sys
import tempfile

CAUSES = {
    "trap_instruction_address_misaligned": 0, "trap_illegal_instruction": 2, "trap_breakpoint": 3,
    "trap_load_address_misaligned": 4, "trap_store_address_misaligned": 6, "trap_machine_ecall": 11,
}
COMMIT = re.compile(r"core\s+0: 3 0x([0-9a-f]+) \(0x([0-9a-f]+)\)(.*)")
EXC = re.compile(r"core\s+0: exception (\S+), epc 0x([0-9a-f]+)")
CYCLE_CSRS = {0xB00, 0xC00, 0xB80, 0xC80}


def spike_events(spike, elf):
    p = subprocess.run([spike, "--isa=rv32im_zicsr_zifencei_zicntr", "--priv=m", "-l", "--log-commits", elf],
                       capture_output=True, text=True, timeout=600)
    ev = []
    for line in p.stderr.splitlines():
        m = EXC.match(line)
        if m:
            pc = int(m.group(2), 16)
            if pc >= 0x80000000:
                ev.append(("trap", pc, None, CAUSES.get(m.group(1), m.group(1))))
            continue
        m = COMMIT.match(line)
        if not m:
            continue
        pc, insn = int(m.group(1), 16), int(m.group(2), 16)
        if pc < 0x80000000:
            continue  # boot ROM
        toks = m.group(3).split()
        rd = rdv = st = ld = None
        i = 0
        while i < len(toks):
            t = toks[i]
            if re.fullmatch(r"x\d+", t):
                if int(t[1:]) != 0:
                    rd, rdv = int(t[1:]), int(toks[i + 1], 16)
                i += 2
            elif t == "mem":
                addr = int(toks[i + 1], 16)
                if i + 2 < len(toks) and toks[i + 2].startswith("0x"):
                    st = (addr, int(toks[i + 2], 16), (len(toks[i + 2]) - 2) // 2)
                    i += 3
                else:
                    ld = addr
                    i += 2
            else:
                i += 2  # CSR write: cNNN_name 0xVAL
        ev.append(("ret", pc, insn, (rd, rdv, st, ld)))
    return ev


def rtl_events(sim, elf):
    with tempfile.NamedTemporaryFile("r", suffix=".trace") as tf:
        subprocess.run([sim, "--quiet", "--no-lockstep", "--trace", tf.name, elf], capture_output=True, timeout=600)
        ev = []
        for line in open(tf.name):
            t = line.split()
            pc, insn = int(t[0], 16), int(t[1], 16)
            if len(t) > 2 and t[2] == "trap":
                ev.append(("trap", pc, None, int(t[3], 16)))
                continue
            rd = rdv = st = ld = None
            i = 2
            while i < len(t):
                if t[i].startswith("x"):
                    rd, rdv = int(t[i][1:]), int(t[i + 1], 16)
                    i += 2
                elif t[i] == "st":
                    addr, mask, data = int(t[i + 1], 16), int(t[i + 2], 16), int(t[i + 3], 16)
                    size = bin(mask).count("1")
                    shift = (addr & 3) * 8
                    st = (addr, (data >> shift) & ((1 << (8 * size)) - 1), size)
                    i += 4
                elif t[i] == "ld":
                    ld = int(t[i + 1], 16)
                    i += 3
                else:
                    i += 1
            ev.append(("ret", pc, insn, (rd, rdv, st, ld)))
        return ev


def compare(a, b):
    """Return (matched count, first mismatch description or None)."""
    n = min(len(a), len(b))
    for i in range(n):
        x, y = a[i], b[i]
        if x[0] != y[0] or x[1] != y[1] or x[2] != y[2]:
            return i, f"#{i}: rtl {x} vs spike {y}"
        if x[0] == "trap":
            if x[3] != y[3]:
                return i, f"#{i}: trap cause rtl {x[3]} vs spike {y[3]}"
            continue
        (rd, rdv, st, ld), (srd, srdv, sst, sld) = x[3], y[3]
        is_cycle_read = (x[2] & 0x7f) == 0x73 and (x[2] >> 20) in CYCLE_CSRS
        if rd != srd or (rdv != srdv and not is_cycle_read):
            return i, f"#{i} pc={x[1]:08x}: rd rtl x{rd}={rdv} vs spike x{srd}={srdv}"
        if st != sst or ld != sld:
            return i, f"#{i} pc={x[1]:08x}: mem rtl st={st} ld={ld} vs spike st={sst} ld={sld}"
    return n, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spike", required=True)
    ap.add_argument("--sim", required=True)
    ap.add_argument("elfs", nargs="+")
    a = ap.parse_args()
    bad = 0
    total = 0
    for elf in a.elfs:
        s = spike_events(a.spike, elf)
        r = rtl_events(a.sim, elf)
        n, err = compare(r, s)
        total += n
        # The harness stops at the final tohost store; Spike keeps running its
        # spin loop until HTIF polls (thousands of instructions). Every RTL
        # event must match; the RTL trace may lack the last few before the
        # store had retired.
        if err or n < len(r) or len(s) < len(r):
            bad += 1
            print(f"MISMATCH {elf}: {err or f'length rtl {len(r)} vs spike {len(s)}'}")
    print(f"spike-compare: {len(a.elfs) - bad}/{len(a.elfs)} programs match ({total} events compared)")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
