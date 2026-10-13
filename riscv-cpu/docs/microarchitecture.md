# Microarchitecture

`rtl/rv_core.sv` (pipeline, CSRs, predictor), `rtl/rv_decode.sv` (decoder), `rtl/rv_alu.sv` (ALU, branch
comparator, multiplier, divider). About 860 lines of SystemVerilog in a conservative subset (no structs or
interfaces). Verilator 5.020 (lint-clean with `-Wall`) and Yosys 0.33 build it; Icarus Verilog 12 also compiles
it (with "constant select in always_*" sensitivity warnings) but is not used for simulation.

## ISA

RV32I + M + Zicsr + Zifencei + Zicntr, machine mode only.

| Item | Implementation |
|---|---|
| CSRs | `mstatus` (MIE, MPIE; MPP reads 3), `misa` (0x40001100), `mie`, `mip` (MSIP/MTIP/MEIP from input pins), `mtvec` (direct and vectored), `mscratch`, `mepc`, `mcause`, `mtval`, `mcycle[h]`, `minstret[h]`, `cycle[h]`, `instret[h]`, ID registers (zero). `mhpmcounter*`, `mhpmevent*`, PMP and trigger CSRs exist as read-only zero (zero PMP entries; zero triggers, `tinfo` = 1). `time` is not implemented (no CLINT). |
| Exceptions | instruction-address misaligned (on the jump/branch), illegal instruction (`mtval` = encoding), breakpoint, load/store address misaligned (`mtval` = address), environment call |
| Interrupts | machine software, timer and external, taken precisely at EX; priority MEI > MSI > MTI |
| Misaligned data | trap (no hardware support) |
| WFI | executes as a NOP |
| FENCE | NOP (single in-order hart, no caches); FENCE.I flushes the pipeline |

## Pipeline

```
 IF ──► ID ──► EX ──► MEM ──► WB
 │      │      │       │       └─ register write, retirement trace (RVFI-style), minstret
 │      │      │       └─ data memory read/write, load alignment and sign extension
 │      │      └─ ALU, MUL (1 cycle), DIV (33 cycles), branch resolution, CSR read/write,
 │      │         exception/interrupt commit point, predictor update
 │      └─ decode, register read (write-through bypass from WB), hazard detection
 └─ pc, BTB/BHT lookup
```

* **Forwarding:** EX takes operands from MEM (non-load results) and WB (all results).
* **Load-use:** one bubble when the instruction in ID reads the destination of a load in EX.
* **Control flow:** predicted in IF by a 64-entry direct-mapped BTB (tag, target, jump flag) with a 2-bit counter per entry; resolved in EX. A misprediction (wrong direction or target, or a BTB hit on a non-branch) costs 2 cycles. There is no return-address stack, so returns predict the last target seen at that `jalr`.
* **Divider:** radix-2 restoring, 32 iterations; EX stalls while it runs. Division by zero and signed overflow follow the ISA and finish after one cycle.
* **System instructions drain the pipeline:** CSR accesses, ECALL, EBREAK, MRET, WFI, FENCE.I and illegal instructions wait in ID until EX, MEM and WB are empty. With no older instruction in flight they can read `minstret` exactly and trap without any rollback. The cost is up to 3 cycles per system instruction, which are rare outside trap handlers.
* **Precise traps:** EX is the commit point. An excepting or interrupted instruction updates `mepc`/`mcause`/`mtval`/`mstatus`, flushes IF and ID, and continues to WB as a *trap record* with no side effects. Instructions older than it, in MEM and WB, complete normally.

## Memory interface

One combinational instruction read port and one combinational-read / synchronous-write data port onto the same address space. Stores are therefore visible to later fetches, after FENCE.I flushes anything fetched early. This models single-cycle on-chip SRAM. A real SRAM with registered reads would need an extra fetch stage, or a stall on every load. See [limitations.md](limitations.md).

## Trace port

Every instruction leaving WB produces one record: pc, encoding, next pc, rd and its value, memory address/masks/data, trap and interrupt flags and cause. The record is modelled on RVFI and consumed by the lockstep harness and by `tools/spike-compare.py`.
