# Limitations

* **Combinational cache and memory reads.** The core's ports read in the same cycle as the request. In the idealised configuration they read memory directly. In the cached configuration they read the cache arrays (tag compare + data in one cycle). Real SRAM macros have registered reads, which would need an extra fetch stage and a load-to-use of 2. The caches are flip-flop arrays in synthesis.
* **Simple memory system.** The bus is custom: one transaction at a time, with no AXI or Wishbone. The memory model is a fixed-latency C++ model with 2-cycle posted writes. There is no critical-word-first fill, no non-blocking cache, and the write buffer does not merge. The D-cache is write-through and no-write-allocate, so stores always use bus bandwidth.
* **Coherence is minimal.** The I-cache only sees stores after FENCE.I. The D-cache only drops lines that the harness announces on the snoop port (HTIF host writes). There is no multi-master coherence.
* **Timing is from a reduced flow.** Placement, CTS and global routing were done with an old (2022) conda OpenROAD build, followed by OpenSTA, at the typical corner only. There was no detailed routing, power grid, DRC/LVS or RC extraction. The full ORFS flow could not run, because the Docker image did not fit on the shared disk. Fmax is limited by the single-cycle 33×33 multiplier in EX ([timing.md](timing.md)).
* **The pipeline barely beats the single-cycle baseline** (125 vs 123 CoreMark iterations/s at each design's met clock). This holds while the multiplier stays single-cycle.
* **No FPGA run and no gate-level simulation.** See [OWNER_ACTIONS.md](OWNER_ACTIONS.md).
* **Machine mode only.** No U/S modes, no virtual memory, no PMP entries, no debug module or triggers, no `time` CSR (no CLINT; the timer interrupt is an input pin).
* **Misaligned loads/stores trap** (allowed by the ISA; `ma_data` fails by design).
* **No return-address stack.** Function returns are predicted from the BTB's last target, which explains most of Dhrystone's 25 % mispredict rate.
* **System instructions drain the pipeline** (up to 3 cycles each).
* **Benchmarks are short.** The CoreMark result is iterations per million simulated cycles, not an official (≥ 10 s) score. Dhrystone uses riscv-tests' build flags, not the ground rules.
* **No formal verification or coverage measurement** (see [verification.md](verification.md)).
* **Spike is not built in CI by default.** The Spike cross-check runs in a separate CI job that builds Spike from source, with caching.
* **Directed cache test is not compared with Spike.** It reads an MMIO counter that Spike does not model; it is checked by lockstep against the in-house ISS.
* **Interrupt livelock at extreme settings.** With 64-byte caches and 50-cycle memory, the default interrupt rate livelocks the handler; that test uses a lower rate (docs/caches.md).
