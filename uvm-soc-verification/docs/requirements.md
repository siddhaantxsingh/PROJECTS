# Requirements matrix — Project 12 (UVM SoC verification)

| Requirement | Status | Evidence |
|---|---|---|
| UVM environment: agents, drivers, monitors, sequencers, sequences, scoreboard, phasing | VERIFIED | `tb/` (pyuvm 5.0); 7 tests pass on 10 seeds |
| Reference-model scoreboard | VERIFIED | lockstep cycle-accurate model; catches 12/12 injected bugs together with the other checks |
| Constrained-random stimulus with seeds | VERIFIED | `RANDOM_SEED`, `tools/regress.py --seeds` |
| Functional coverage and closure | VERIFIED | 82/82 bins merged over 10 seeds (`results/2026-10-14/regression.json`) |
| Bug injection / testbench effectiveness | VERIFIED | 12/12 caught; one escape found and fixed (README) |
| Regression automation | VERIFIED | `tools/regress.py`, CI workflow |
| SystemVerilog UVM on a commercial simulator | BLOCKED | no such simulator; Verilator 5.020 cannot run the UVM class library |
| SVA / formal properties | NOT IMPLEMENTED | — |
| CPU-level SoC (core + peripherals) verification | NOT IMPLEMENTED | — |
| CI | IMPLEMENTED BUT UNVERIFIED | `.github/workflows/uvm-soc-verification.yml` |
