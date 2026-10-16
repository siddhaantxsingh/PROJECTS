# rv_core: a verified 5-stage RV32IM CPU

`rv_core` is a 5-stage in-order RISC-V core written from scratch in SystemVerilog. It implements RV32IM, Zicsr, Zifencei and Zicntr in machine mode:

- full forwarding;
- BTB + 2-bit branch prediction;
- single-cycle multiply and iterative divide;
- precise exceptions;
- vectored interrupts;
- optional I-cache and write-through D-cache with a write buffer, in front of a memory with configurable latency (`rv_top`);
- a single-cycle baseline core (`rv_single`) for IPC × Fmax comparison.

Verification combines four independent checks:

- every instruction compared live against an independent reference ISS;
- the official riscv-tests;
- constrained-random programs, also cross-checked against Spike's commit log;
- a random-interrupt stress test.

**Status:**
- **Done:**
  - verified in simulation, both with idealised memory and with caches;
  - sky130hd timing, area and power from a reduced open-source flow
    (synthesis, placement, CTS and global routing, then STA).
- **Not done:**
  - detailed routing and DRC;
  - gate-level simulation;
  - any FPGA or silicon run.

  See [limitations](docs/limitations.md) and [owner actions](docs/OWNER_ACTIONS.md).

## Results

| | |
|---|---|
| riscv-tests rv32ui/um/mi | **64/64 pass** in lockstep, plus 2 expected failures by design (no misaligned-access hardware, no PMP entries) |
| Random programs vs ISS | 250/250 pass on 3 predictor configurations |
| Random programs vs **Spike** | 400/400 identical on the idealised core (335,326 events); also 400/400 on two cached builds and the single-cycle baseline |
| Interrupt stress | 8 seeds × ~12,000 random interrupts, all precise |
| CoreMark (2K perf. run, CRCs validated) | **2.87 iterations/MHz** (2.43 without the branch predictor) |
| Dhrystone (riscv-tests build) | 1.22 DMIPS/MHz |
| CPI | 1.01–1.34 on the riscv-tests benchmarks; 1.11 on CoreMark |
| Area (Yosys `synth_ecp5`) | 6,348 LUT4 · 1,647 FF · 92 LUT-RAM16×4 · 4 DSP |
| Caches: riscv-tests + 400 random + directed cache test + interrupts | all pass in lockstep on 3 cache configurations (latency 1, 20, 50); Spike 400/400 on 2 of them |
| Cache bug injection | 9/9 injected bugs caught |
| CoreMark/MHz with caches, 20-cycle memory | 0.96 (256 B), 2.50 (1 KiB), 2.58 (1 KiB 2-way), 2.79 (4 KiB) |
| sky130hd, after placement + CTS + global route | `rv_core` meets 43.5 MHz (23 ns), 328,883 µm², ~50 mW vectorless; single-cycle meets 38.5 MHz |
| Single-cycle baseline | CPI 1.001, 3.19 CoreMark/MHz; 123 vs 125 CoreMark/s for the pipeline at each design's met clock (multiplier-bound) |

The CoreMark figure is iterations per million simulated cycles; the run is too short to be an official CoreMark score. Details are in [docs/benchmarks.md](docs/benchmarks.md), [docs/caches_results.md](docs/caches_results.md) and [docs/timing.md](docs/timing.md).

## Layout

```
rtl/       rv_core.sv (pipeline, CSRs, predictor), rv_decode.sv, rv_alu.sv (ALU, MUL, DIV),
           rv_cache.sv (I/D caches, write buffer, arbiter, rv_top), rv_single.sv (single-cycle baseline)
flow/      sky130hd reduced PD flow (pd_lite.sh), copied ORFS scripts (not run), results
tb/        Verilator harness (ELF loader, HTIF, MMIO, lockstep), reference ISS
tools/     random program generator, Spike comparison, test runner, benchmark/report scripts
tests/     directed tests (interrupt stress, cache corner cases, self-modifying code)
sw/        crt0, linker script, tiny runtime, CoreMark port
synth/     Yosys script
results/   recorded benchmark and synthesis outputs
docs/      microarchitecture, verification, benchmarks, limitations, requirements
```

## Running it

The tools needed are Verilator 5, `riscv64-unknown-elf-gcc` with `picolibc` (Ubuntu: `gcc-riscv64-unknown-elf picolibc-riscv64-unknown-elf`), Yosys and Python 3.

```bash
make fetch          # riscv-tests and CoreMark at pinned commits (not vendored)
make test           # riscv-tests + random + interrupts + predictor variants, all in lockstep
make test-spike SPIKE=/path/to/spike   # cross-check against Spike
make bench synth report                # benchmarks, synthesis, docs/benchmarks.md
make test-cache-all                    # cached builds (3 configurations), lockstep
make test-cache CACHE=32:2:32:2:4:4 MEMLAT=30   # any cache configuration and memory latency
make test-single                       # single-cycle baseline
tools/cache-sweep.sh out.jsonl         # CPI / hit rates vs cache size and latency
python3 tools/mutate-cache.py          # cache bug injection
flow/scripts/sweep_lite.sh rv_core_pd 23        # sky130hd timing (see flow/env.sh.example)
obj_dir/Vrv_core program.elf           # run any bare-metal rv32im ELF linked at 0x80000000
```

The harness's memory map, HTIF exit convention and options are documented at the top of `tb/sim_main.cpp`.

## Documentation

- [Microarchitecture](docs/microarchitecture.md)
- [Verification](docs/verification.md)
- [Benchmarks](docs/benchmarks.md)
- [Limitations](docs/limitations.md)
- [Caches](docs/caches.md) and [cache results](docs/caches_results.md)
- [Timing, area, power](docs/timing.md)
- [Requirements matrix](docs/requirements.md)
- [Owner actions (FPGA, full P&R)](docs/OWNER_ACTIONS.md)

## Scorecard

| score (0-10) | value | why not higher |
|---|---|---|
| Fresher-portfolio strength | 8 | Verification depth is strong: lockstep ISS, Spike, mutants. But there is no FPGA or silicon result, and the pipeline does not yet beat its own single-cycle baseline. |
| Engineering maturity | 7 | CI covers every configuration. But there is no formal verification, no coverage metric, and caches/memories are combinational flop arrays, not SRAM macros. |
| Deployment readiness | 3 | Simulation and a reduced PD flow only: no detailed routing, no FPGA bitstream, no gate-level simulation. |
| Completion of intended scope | 7 | Caches, the latency study, bug injection, the single-cycle comparison and timing are done. Full ORFS P&R was blocked by disk; GLS, FPGA and the optional OoO extension were not done. |

## Resume-safe facts

- "RV32IM 5-stage pipelined CPU in SystemVerilog." Verified against riscv-tests, Spike and a lockstep ISS.
- "Added parameterised I/D caches with a write buffer; measured CPI and CoreMark against cache size and memory latency." Plus 9/9 injected cache bugs caught.
- "Compared IPC × Fmax against a single-cycle baseline on sky130hd."
  - This was a reduced open flow (placement, CTS, global routing). Say so.
  - The result was near parity, because the critical path is the multiplier.
- Do **not** claim "deployed on FPGA hardware" until docs/OWNER_ACTIONS.md step 1 is done.
