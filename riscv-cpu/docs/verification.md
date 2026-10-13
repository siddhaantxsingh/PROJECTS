# Verification

Four independent checks, all automated (`make test`, `make test-spike`, CI):

## 1. Lockstep against a reference ISS (every run)

`tb/iss.cpp` is an instruction-set simulator written from the ISA manuals, separately from the RTL. The Verilator harness (`tb/sim_main.cpp`) steps it once for every RTL retirement and compares:

* pc and encoding;
* next pc;
* destination register and value;
* memory address, byte masks and store data;
* trap/interrupt status and cause.

Values read from `mcycle`/`cycle` are taken from the RTL, since cycle counts are timing-dependent. For an interrupt, the harness checks that the ISS also has that interrupt enabled, then makes it take the same interrupt. The first difference stops the run and is reported with both models' records.

## 2. The official riscv-tests (`make test-isa`)

The 66 rv32ui/rv32um/rv32mi `-p-` tests come from riscv-tests (pinned commit `7b68f7b`). Each runs in lockstep.

* **64 pass.**
* **2 are expected failures**, checked as such (they must fail cleanly, never with a mismatch or timeout):
  * `rv32ui-p-ma_data` requires hardware misaligned loads/stores; this core traps instead, as the ISA allows. The `*-misaligned` and `ma_addr` trap tests pass.
  * `rv32mi-p-pmpaddr` requires at least one PMP entry; this core implements zero.

## 3. Constrained-random programs (`make test-random`)

`tools/rvgen.py` generates self-contained programs covering:

* every RV32IM computational instruction, with corner-case operands;
* every load and store width, including misaligned accesses (which trap);
* forward branches, JAL, JALR (including misaligned targets, which trap);
* counted loops;
* CSR reads and writes;
* FENCE and FENCE.I;
* ECALL, EBREAK and illegal encodings, handled by a skip-and-continue trap handler.

Register choice is biased towards recently written registers, to stress forwarding and load-use hazards. Each program runs in lockstep. The suite also runs on two predictor configurations (`make test-variants`): a 4-entry BTB, which forces heavy aliasing and stale-entry hits on non-branches, and the predictor disabled.

Recorded on 2026-10-13: 250 programs (seeds 1 and 2), about 210,000 retired instructions and exceptions, all passing on all three configurations.

## 4. Cross-check against Spike (`make test-spike SPIKE=...`)

Checks 1–3 would miss a misreading of the spec shared by the RTL and the ISS (same author). `tools/spike-compare.py` therefore runs each program on the RTL, with lockstep off, and on Spike:

```
spike --isa=rv32im_zicsr_zifencei_zicntr --priv=m -l --log-commits
```

Spike is riscv-isa-sim, pinned at `f8a060b`. The script compares every retired instruction and every exception in order.

* **Random programs:** 250/250 match Spike, with 209,869 events compared. The generator zeroes `minstret` first (Spike's boot ROM retires 5 instructions) and routes cycle-counter reads to a scratch register that is never read.
* **riscv-tests:** 62/64 match. The two differences are implementation-defined:
  * `breakpoint`: Spike implements debug triggers, while this core reports none;
  * `mcsr`: Spike's `marchid` is 5, while this core's is 0.

## 5. Interrupts (`make test-irq`)

`tests/irq_test.S` runs a checksum loop (ALU, MUL/DIV, loads/stores, branches) with all three interrupt sources enabled:

- mtvec is in direct mode for the first half of the run and in vectored mode for the second;
- the harness raises the interrupt lines at random cycles;
- the handler acknowledges each interrupt through MMIO and counts it.

Over 8 seeds, each run takes about 12,000 interrupts, and every one is checked in lockstep (precise `mepc`, no lost or repeated instruction). At the end, the program checks that its own interrupt count equals the harness's count.

## 6. Benchmarks under lockstep

CoreMark and the riscv-tests benchmarks are self-checking. They run with lockstep on, both with and without the predictor, and also on the 4-entry-BTB build.

## Static checks

`make lint` runs `verilator --lint-only -Wall`, which is clean. `make synth` runs Yosys and must parse and map the design.

## Not done

- No formal verification: no SymbiYosys/riscv-formal flow was available offline.
- No coverage measurement (functional or code).
- No gate-level simulation.
