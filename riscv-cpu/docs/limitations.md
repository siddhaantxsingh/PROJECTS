# Limitations

* **Idealised memory.** Instruction and data reads are combinational (single-cycle SRAM / LUT RAM). Real block RAM has registered reads; supporting it needs an extra fetch stage and a load-to-use of 2. There are no caches, no bus protocol (AXI/Wishbone) and no wait states, so the CPI figures are upper bounds on what a memory system allows.
* **No timing closure.** Yosys reports resources only. There was no place-and-route or static timing tool, so Fmax is unknown. The single-cycle 33×33 multiplier and the combinational memory paths are the likely critical paths.
* **Machine mode only.** No U/S modes, no virtual memory, no PMP entries, no debug module or triggers, no `time` CSR (no CLINT; the timer interrupt is an input pin).
* **Misaligned loads/stores trap** (allowed by the ISA; `ma_data` fails by design).
* **No return-address stack.** Function returns are predicted from the BTB's last target, which explains most of Dhrystone's 25 % mispredict rate.
* **System instructions drain the pipeline** (up to 3 cycles each).
* **Benchmarks are short.** The CoreMark result is iterations per million simulated cycles, not an official (≥ 10 s) score. Dhrystone uses riscv-tests' build flags, not the ground rules.
* **No formal verification or coverage measurement** (see [verification.md](verification.md)).
* **Spike is not built in CI by default.** The Spike cross-check runs in a separate CI job that builds Spike from source, with caching.
