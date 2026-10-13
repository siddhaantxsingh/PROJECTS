#!/usr/bin/env python3
"""Constrained-random RV32IM test generator.

    rvgen.py --count N --seed S --out DIR [--length L]

Writes N self-contained assembly programs. Each program:

* initialises all registers with random values (biased towards corner
  cases: 0, -1, INT_MIN, INT_MAX, small numbers);
* runs a random instruction stream: every RV32IM ALU, shift, multiply and
  divide instruction; LUI/AUIPC; loads and stores of every width (mostly
  aligned, some misaligned, which trap); forward branches, JAL and JALR;
  counted backward loops; CSR reads and writes (mscratch, mstatus.MIE-free
  bits, minstret, mcycle); FENCE, FENCE.I; and occasional ECALL, EBREAK and
  illegal encodings;
* uses a trap handler that skips the faulting instruction, so every
  exception path is exercised and execution continues;
* ends by writing 1 to tohost (pass).

Correctness is not checked by the program itself: the harness compares
every retired instruction against the reference ISS (and, offline, Spike).
Destinations are biased towards recently used registers to stress
forwarding and load-use hazards. x27 (loop counter) and x28 (data base) are
never written by the random stream; x26 is a scratch register for
address materialisation and cycle-counter reads and is never a random source.
"""
import argparse
import os
import random

REGS = [r for r in range(1, 32) if r not in (26, 27, 28)]
CORNER = [0, 1, 2, 0xFFFFFFFF, 0x80000000, 0x7FFFFFFF, 0xFFFFFFFE, 0x80000001, 31, 32, 0x55555555, 0xAAAAAAAA]

R_OPS = ["add", "sub", "sll", "slt", "sltu", "xor", "srl", "sra", "or", "and",
         "mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu"]
I_OPS = ["addi", "slti", "sltiu", "xori", "ori", "andi"]
SH_OPS = ["slli", "srli", "srai"]
LOADS = [("lb", 1), ("lbu", 1), ("lh", 2), ("lhu", 2), ("lw", 4)]
STORES = [("sb", 1), ("sh", 2), ("sw", 4)]
BRANCHES = ["beq", "bne", "blt", "bge", "bltu", "bgeu"]
ILLEGAL = [0x00000000, 0xFFFFFFFF, 0x0000707F, 0x02005033, 0x4000_1013, 0x00003003, 0x0000300F, 0x30001073]
DATA_BYTES = 2048


class Gen:
    def __init__(self, rng: random.Random):
        self.r = rng
        self.lines = []
        self.recent = []
        self.label = 0

    def reg(self, src=False):
        if self.recent and self.r.random() < 0.5:
            return self.r.choice(self.recent[-4:])
        if src and self.r.random() < 0.1:
            return 0
        return self.r.choice(REGS)

    def dst(self):
        d = self.reg()
        self.recent.append(d)
        return d

    def val(self):
        if self.r.random() < 0.4:
            return self.r.choice(CORNER)
        return self.r.getrandbits(32)

    def new_label(self):
        self.label += 1
        return f"L{self.label}"

    def emit(self, s):
        self.lines.append("    " + s)

    def simple(self):
        """One instruction that does not change control flow."""
        k = self.r.random()
        if k < 0.30:
            op = self.r.choice(R_OPS)
            self.emit(f"{op} x{self.dst()}, x{self.reg(True)}, x{self.reg(True)}")
        elif k < 0.45:
            op = self.r.choice(I_OPS)
            self.emit(f"{op} x{self.dst()}, x{self.reg(True)}, {self.r.randint(-2048, 2047)}")
        elif k < 0.52:
            op = self.r.choice(SH_OPS)
            self.emit(f"{op} x{self.dst()}, x{self.reg(True)}, {self.r.randint(0, 31)}")
        elif k < 0.56:
            self.emit(f"lui x{self.dst()}, {self.r.getrandbits(20)}")
        elif k < 0.58:
            self.emit(f"auipc x{self.dst()}, {self.r.getrandbits(20)}")
        elif k < 0.73:
            op, size = self.r.choice(LOADS)
            off = self.r.randrange(0, DATA_BYTES - 4, size)
            if self.r.random() < 0.04 and size > 1:
                off += 1  # misaligned: traps
            self.emit(f"{op} x{self.dst()}, {off}(x28)")
        elif k < 0.86:
            op, size = self.r.choice(STORES)
            off = self.r.randrange(0, DATA_BYTES - 4, size)
            if self.r.random() < 0.04 and size > 1:
                off += 1
            self.emit(f"{op} x{self.reg(True)}, {off}(x28)")
        elif k < 0.92:
            c = self.r.random()
            if c < 0.3:
                self.emit(f"csrrw x{self.dst()}, mscratch, x{self.reg(True)}")
            elif c < 0.5:
                self.emit(f"csrrs x{self.dst()}, mscratch, x{self.reg(True)}")
            elif c < 0.6:
                self.emit(f"csrrci x{self.dst()}, mscratch, {self.r.randint(0, 31)}")
            elif c < 0.75:
                self.emit(f"csrr x{self.dst()}, minstret")
            elif c < 0.85:
                # cycle counts differ between models: read into the scratch
                # register x26, which no random instruction reads
                self.emit(f"csrr x26, {self.r.choice(['mcycle', 'cycle', 'mcycleh'])}")
            elif c < 0.92:
                self.emit(f"csrr x{self.dst()}, mstatus")
            else:
                self.emit(f"csrr x{self.dst()}, misa")
        elif k < 0.95:
            self.emit(self.r.choice(["fence", "fence.i", "fence rw, rw", "nop"]))
        else:
            t = self.r.random()
            if t < 0.35:
                self.emit("ecall")
            elif t < 0.6:
                self.emit("ebreak")
            else:
                self.emit(f".word 0x{self.r.choice(ILLEGAL):08x}")

    def block(self, n, allow_control=True):
        i = 0
        while i < n:
            k = self.r.random()
            if allow_control and k < 0.08:
                # forward conditional branch over 1..6 instructions
                lab = self.new_label()
                op = self.r.choice(BRANCHES)
                self.emit(f"{op} x{self.reg(True)}, x{self.reg(True)}, {lab}")
                for _ in range(self.r.randint(1, 6)):
                    self.simple()
                self.lines.append(f"{lab}:")
                i += 3
            elif allow_control and k < 0.10:
                lab = self.new_label()
                self.emit(f"jal x{self.dst()}, {lab}")
                for _ in range(self.r.randint(0, 3)):
                    self.simple()
                self.lines.append(f"{lab}:")
                i += 2
            elif allow_control and k < 0.115:
                # jalr to a forward label through a register (x26 as temp)
                lab = self.new_label()
                self.emit(f"la x26, {lab}")
                if self.r.random() < 0.1:
                    self.emit("addi x26, x26, 2")  # misaligned target: traps
                self.emit(f"jalr x{self.dst()}, 0(x26)")
                for _ in range(self.r.randint(0, 3)):
                    self.simple()
                self.lines.append(f"{lab}:")
                i += 3
            elif allow_control and k < 0.13:
                # counted loop with a random body (only forward branches inside)
                lab = self.new_label()
                self.emit(f"li x27, {self.r.randint(2, 12)}")
                self.lines.append(f"{lab}:")
                self.block(self.r.randint(2, 8), allow_control=False)
                if self.r.random() < 0.5:
                    inner = self.new_label()
                    self.emit(f"{self.r.choice(BRANCHES)} x{self.reg(True)}, x{self.reg(True)}, {inner}")
                    self.simple()
                    self.lines.append(f"{inner}:")
                self.emit("addi x27, x27, -1")
                self.emit(f"bnez x27, {lab}")
                i += 6
            else:
                self.simple()
                i += 1


def program(seed: int, length: int) -> str:
    rng = random.Random(seed)
    g = Gen(rng)
    out = [f"# rvgen seed={seed} length={length}",
           ".section .text.init", ".globl _start", "_start:",
           "    la x28, data",
           "    la x26, trap_handler",
           "    csrw mtvec, x26",
           # start instret at 0 so reads compare equal with Spike (whose boot
           # ROM retires 5 instructions first); also exercises minstret writes
           "    csrw minstret, x0",
           "    csrw minstreth, x0"]
    for r in REGS:
        out.append(f"    li x{r}, 0x{g.val():08x}")
    g.block(length)
    out += g.lines
    out += [
        "done:",
        "    li x26, 1",
        "    la x27, tohost",
        "    sw x26, 0(x27)",
        "1:  j 1b",
        "",
        "# skip the faulting instruction (all instructions are 4 bytes)",
        ".align 2",
        "trap_handler:",
        "    csrrw x29, mscratch, x29",
        "    csrr x29, mepc",
        "    addi x29, x29, 4",
        "    csrw mepc, x29",
        "    csrrw x29, mscratch, x29",
        "    mret",
        "",
        ".section .tohost, \"aw\", @progbits",
        ".align 6", ".globl tohost", "tohost: .dword 0",
        ".align 6", ".globl fromhost", "fromhost: .dword 0",
        ".data", ".align 4", "data:",
    ]
    words = [g.val() for _ in range(DATA_BYTES // 4)]
    for i in range(0, len(words), 8):
        out.append("    .word " + ", ".join(f"0x{w:08x}" for w in words[i:i + 8]))
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=100)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--length", type=int, default=400)
    ap.add_argument("--out", default="build/rand")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for i in range(a.count):
        s = a.seed * 100000 + i
        with open(os.path.join(a.out, f"rand_{s}.S"), "w") as f:
            f.write(program(s, a.length))


if __name__ == "__main__":
    main()
