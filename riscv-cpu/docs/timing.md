# Timing, area and power (sky130hd, reduced flow)

**Status: PARTIAL.** These are numbers after placement, CTS and global
routing, from a reduced open-source flow. There was no detailed routing, no
power grid, no DRC/LVS and no RC extraction. The full ORFS flow from the
accelerator project could not run: its Docker image is 6.5 GB, and the shared
disk had 3-4 GB free. The ORFS scripts are copied into `flow/scripts/` with a
`config.mk` for `rv_core`, but they were **not run**. See
[OWNER_ACTIONS.md](OWNER_ACTIONS.md).

## Flow (`flow/scripts/pd_lite.sh`, sweep with `flow/scripts/sweep_lite.sh`)

1. **Yosys 0.33:** `synth -flatten`, `dfflibmap`, then `abc -D <period>` onto
   `sky130_fd_sc_hd__tt_025C_1v80`. The ORFS dont-use cells are removed first
   (`flow/scripts/lib_dont_use.py`).
2. **OpenROAD** (litex-hub conda build `f12e2f4`, 2022), in this order:
   * floorplan at 40 % utilisation, I/O pin placement;
   * global placement, `repair_design`;
   * CTS (`clkbuf_4/8/16`);
   * `repair_timing` (setup and hold);
   * detailed placement;
   * global routing;
   * parasitics estimated from the global routes, using ORFS `setRC.tcl`.
3. **OpenSTA** (inside OpenROAD), for the typical corner only:
   * setup and hold;
   * area;
   * vectorless power, with input activity 0.1.

The constraints match the accelerator's `constraint.sdc`: one clock, and
input/output delays of 20 % of the period on every I/O. The platform LEF and
`setRC.tcl` come from the ORFS GitHub repository (`master`, fetched
2026-10-15).

### What is in each block

| top | contents | memories |
|---|---|---|
| `rv_core_pd` | pipelined core, 64-entry BTB, register file as flops | instruction/data memory **outside** the block (its ports are I/O); ready inputs tied to 1 (the idealised configuration) |
| `rv_single_pd` | single-cycle baseline | same as above |
| `rv_top_pd` | core + 256 B I-cache + 256 B D-cache (16 sets, 16 B lines, direct-mapped) + 4-entry write buffer + arbiter | cache data/tags as **flip-flops**; main memory outside (bus ports are I/O) |

Trace and performance-counter outputs are left unconnected in the wrappers
(`flow/src/rv_pd.sv`), so that logic is removed by synthesis.

## Results (`flow/results/pd_lite_sky130hd.jsonl`)

| top | clock (ns) | setup WNS (ns) | hold WNS (ns) | met? | cells (synth) | flip-flops | placed area (µm²) | power (mW, vectorless) |
|---|---|---|---|---|---|---|---|---|
| rv_core_pd | 20.0 | −0.917 | 0.443 | no | 32,366 | 5,863 | 328,529 | 57.2 |
| rv_core_pd | 21.5 | −0.952 | 0.355 | no | 32,361 | 5,863 | 328,910 | 53.9 |
| rv_core_pd | **23.0** | +0.082 | 0.355 | **yes (43.5 MHz)** | 32,361 | 5,863 | 328,883 | 50.4 |
| rv_single_pd | 30.0 | +4.281 | 0.489 | yes | 15,684 | 1,491 | 152,845 | 11.8 |
| rv_single_pd | **26.0** | +1.081 | 0.489 | **yes (38.5 MHz)** | 15,684 | 1,491 | 152,845 | 13.6 |
| rv_top_pd | 20.0 | −1.180 | 0.464 | no | 52,250 | 11,143 | 590,906 | 61.4 |
| rv_top_pd | **23.0** | +1.391 | 0.464 | **yes (43.5 MHz)** | 52,250 | 11,143 | 590,858 | 53.4 |

"Placed area" is the standard-cell area after repair and CTS. The die is
about 2.1× that, at 47 % utilisation. Power is OpenSTA's estimate with
default activity, not a simulation trace.

The pipelined core has about 4× the flip-flops of the single-cycle one,
mostly the 64-entry BTB (tag, target and counter per entry) and the pipeline
registers. That accounts for most of its 2.2× area and 3.7× power.

### Critical paths

* **Pipelined core:** EX operand forwarding mux → 33×33 single-cycle
  multiplier → MEM register. The 21.5 ns run failing while 23 ns passes
  reflects run-to-run variation in repair. Treat Fmax as about 43-45 MHz.
* **Single-cycle core:** instruction-memory data input (arriving at 20 % of
  the period) → decode → register read → multiplier → register file.
* The cached system has the same EX/multiplier path, so the caches (as
  flops, 256 B each) do not limit the clock.

## Single-cycle versus pipelined: IPC × Fmax

| design | Fmax met (MHz) | CoreMark/MHz (idealised memory) | CoreMark iterations/s at Fmax |
|---|---|---|---|
| pipelined `rv_core` (BTB+BHT) | 43.5 | 2.87 (CPI 1.114) | 125 |
| single-cycle `rv_single` | 38.5 | 3.19 (CPI 1.001) | 123 |

**Honest reading:** in this implementation, pipelining buys almost nothing.

* The single-cycle 33×33 multiplier sits in EX, and its path is the
  critical path in both designs.
* The pipelined core's extra stages remove decode and memory time from the
  cycle, but the multiplier alone is close to the whole period.
* The pipeline also adds CPI from mispredictions and load-use bubbles.

Two caveats:

* The single-cycle design's two memory accesses are modelled only by the
  20 % I/O budgets (about 5 ns each at 26 ns), not by a real SRAM.
* Neither design has had detailed routing.

The obvious next step is a 2-stage multiplier, or a multi-cycle MUL that
stalls like DIV. That should let the pipelined core's clock rise well above
the single-cycle one. It is not done.

## Not done

* Detailed routing, DRC/LVS, RC extraction, multi-corner analysis.
* SRAM macros: caches are flip-flops, and main memory is outside the block.
* Gate-level simulation of the netlist. Not attempted, for lack of time;
  the netlists were deleted with the run directories.
