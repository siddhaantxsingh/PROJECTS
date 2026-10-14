# Limitations

* **pyuvm, not SystemVerilog UVM.**
  * The UVM structure and phasing are the standard ones, but there is no SystemVerilog UVM library, SVA, or SystemVerilog constraint solver.
  * Constrained-random stimulus uses Python's `random` with explicit constraints in the sequences.
  * Functional coverage is a Python bin collector, not SystemVerilog covergroups.
* **The DUT is a peripheral subsystem, not a CPU SoC.** The testbench is the APB master, standing in for the CPU. The RISC-V core from `riscv-cpu/` is not integrated.
* **The reference model and the RTL share an author and a specification.** A misreading common to both would pass the lockstep check. The model-independent checks (TX decoder, sequence assertions) reduce that risk but do not remove it.
* **No formal verification and no code coverage.** Verilator line and toggle coverage was not collected; only functional coverage was.
* **Clean-run regression uses 10 seeds.** Bins are counted as hit or not; there are no per-bin hit targets beyond 1.
