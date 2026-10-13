# rv_core: a verified 5-stage RV32IM CPU

`rv_core` is a 5-stage in-order RISC-V core written from scratch in SystemVerilog. It implements RV32IM, Zicsr, Zifencei and Zicntr in machine mode:

- full forwarding;
- BTB + 2-bit branch prediction;
- single-cycle multiply and iterative divide;
- precise exceptions;
- vectored interrupts.

Verification combines four independent checks:

- every instruction compared live against an independent reference ISS;
- the official riscv-tests;
- constrained-random programs, also cross-checked against Spike's commit log;
- a random-interrupt stress test.

**Status:**
- **Done:** verified in simulation and synthesized with Yosys.
- **Not done:** no place and route, no timing, no silicon or FPGA run. See [limitations](docs/limitations.md).

## Results

| | |
|---|---|
| riscv-tests rv32ui/um/mi | **64/64 pass** in lockstep, plus 2 expected failures by design (no misaligned-access hardware, no PMP entries) |
| Random programs vs ISS | 250/250 pass on 3 predictor configurations |
| Random programs vs **Spike** | 250/250 identical (209,869 retired instructions and exceptions compared) |
| Interrupt stress | 8 seeds × ~12,000 random interrupts, all precise |
| CoreMark (2K perf. run, CRCs validated) | **2.87 iterations/MHz** (2.43 without the branch predictor) |
| Dhrystone (riscv-tests build) | 1.22 DMIPS/MHz |
| CPI | 1.01–1.34 on the riscv-tests benchmarks; 1.11 on CoreMark |
| Area (Yosys `synth_ecp5`) | 6,348 LUT4 · 1,647 FF · 92 LUT-RAM16×4 · 4 DSP |

The CoreMark figure is iterations per million simulated cycles; the run is too short to be an official CoreMark score. Details are in [docs/benchmarks.md](docs/benchmarks.md).

## Layout

```
rtl/       rv_core.sv (pipeline, CSRs, predictor), rv_decode.sv, rv_alu.sv (ALU, MUL, DIV)
tb/        Verilator harness (ELF loader, HTIF, MMIO, lockstep), reference ISS
tools/     random program generator, Spike comparison, test runner, benchmark/report scripts
tests/     directed tests (interrupt stress)
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
obj_dir/Vrv_core program.elf           # run any bare-metal rv32im ELF linked at 0x80000000
```

The harness's memory map, HTIF exit convention and options are documented at the top of `tb/sim_main.cpp`.

## Documentation

- [Microarchitecture](docs/microarchitecture.md)
- [Verification](docs/verification.md)
- [Benchmarks](docs/benchmarks.md)
- [Limitations](docs/limitations.md)
- [Requirements matrix](docs/requirements.md)
