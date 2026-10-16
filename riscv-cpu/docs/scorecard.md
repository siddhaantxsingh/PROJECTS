# Scorecard

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
- Do **not** claim "deployed on FPGA hardware" until [OWNER_ACTIONS.md](OWNER_ACTIONS.md) step 1 is done.
