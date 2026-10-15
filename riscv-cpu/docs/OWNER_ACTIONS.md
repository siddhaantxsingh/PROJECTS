# Owner actions

These steps need hardware, a vendor tool licence or more disk than this
build machine had. None of them has been done. Do not claim any of them
until you have run them and saved the outputs listed below.

## 1. FPGA bring-up (Basys 3 or Arty A7-35T, Vivado)

Status: **NOT DONE.** No FPGA run exists. The resume line "deployed on FPGA
hardware" is not supported by anything in this repository until you finish
this section.

The core's idealised ports read memory combinationally. Xilinx block RAM has
a registered read, so the cleanest FPGA build is `rv_top`:

* the caches and write buffer are small arrays (LUT RAM or flip-flops);
* the bus to memory tolerates any latency of 1 or more, so a block RAM sits
  behind it directly.

The files below do not exist yet. They are what you would write.

1. **Board top `fpga/fpga_top.sv`:**
   * instantiate `rv_top` with small caches, e.g.
     `#(.I_SETS(64), .D_SETS(64), .LINE_WORDS(4), .WB_DEPTH(4))`;
   * add a 64 KiB block-RAM memory at `0x8000_0000` that answers `bus_req_*`
     with `bus_resp_valid` one cycle after a read request, and one beat per
     cycle for line reads;
   * add a UART transmitter at `0x1000_0000` in place of the simulation
     console. Store a byte, then busy-wait on a status bit; the current
     harness has no status register;
   * tie `io_base` to the `tohost` page (or to 0) and `snoop_inv` to 0;
   * tie the interrupt inputs to 0;
   * use a 50 MHz clock from an MMCM. Board clocks are 100 MHz.
2. **Program image:** initialise the block RAM from a hex file made with
   `riscv64-unknown-elf-objcopy -O verilog --verilog-data-width 4 prog.elf prog.hex`
   (CoreMark built as in `tools/bench.sh`, printing through the UART).
3. **Simulate the board top before building:** run it in Verilator with the
   same program. Check that the UART output matches the
   `obj_dir_c64_1_64_1_4_4/Vrv_top` run.
4. **Vivado (batch):**
   ```
   vivado -mode batch -source fpga/build.tcl   # synth_design -top fpga_top -part xc7a35tcpg236-1 (Basys 3)
                                               #   or xc7a35ticsg324-1L (Arty A7-35T)
                                               # opt_design; place_design; route_design;
                                               # report_timing_summary; report_utilization; write_bitstream
   ```
   Expected: timing met at 50 MHz (the sky130 numbers in
   [timing.md](timing.md) do not transfer to FPGA, so this is a guess).
5. **Program the board** (`open_hw_manager`, `program_hw_devices`). Open a
   terminal at 115200 baud and read CoreMark's report.
6. **Save** into `docs/evidence/<date>-fpga/`:
   * `report_timing_summary` and `report_utilization` output;
   * the UART log (CoreMark ticks and CRCs);
   * a photo of the board.

   Then update `docs/requirements.md` (FPGA row) and the README.

To compare against the single-cycle baseline on FPGA, repeat with
`rv_single`. It needs combinational memory, so use distributed (LUT) RAM,
for example 16 KiB on the A7-35T.

## 2. Full open-PDK place and route (ORFS)

Status: **BLOCKED here (disk).** The ORFS image needs about 6.5 GB, and the
shared disk had 3-4 GB free. The numbers in [timing.md](timing.md) come from
a reduced flow: Yosys, then OpenROAD placement, CTS and global route, then
STA. That flow has no power grid, no detailed routing and no DRC.

To run the full flow on a machine with at least 15 GB free:

```bash
docker pull openroad/orfs@sha256:26aa080a55be1fe9ad5befeaa01d0ff6d72c24bdd6c949986ba4448543453dd5
flow/scripts/sv2v.sh rv_core_pd flow/build/rv_core_pd.v flow/src/rv_pd.sv
flow/scripts/sweep.sh sky130hd rv_core 20 15 12 10    # uses flow/designs/sky130hd/rv_core/config.mk
docker rmi openroad/orfs@sha256:26aa080a55be1fe9ad5befeaa01d0ff6d72c24bdd6c949986ba4448543453dd5
rm -rf flow/runs
```

Expected outputs: `flow/results/sweep_sky130hd_rv_core.jsonl` with routed
setup/hold WNS and DRC counts. Expect Fmax somewhat below the reduced-flow
estimate, because detailed-route parasitics are worse than global-route
estimates.

## 3. Official CoreMark score

The CoreMark numbers are from 10-iteration runs (iterations per million
simulated cycles). An official score needs a run of at least 10 seconds on
real hardware (step 1) with the ground rules followed.
