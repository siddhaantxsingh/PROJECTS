# Scorecard (2026-10-15, self-assessed)

| Score | Value | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 8/10 | The main UVM environment is pyuvm, and the SV-UVM testbench is small and has run only on Verilator, not on an industry simulator. |
| Engineering maturity | 7/10 | Failing-first evidence for every testable check and CI, but the SVA are not formally proven, there is no code coverage, and the reference model shares an author with the RTL. |
| Deployment readiness | 6/10 | Reproducible regression in CI; no sign-off flow (formal, code coverage, commercial simulator) and the DUT is a small peripheral subsystem. |
| Completion of intended scope | 8/10 | Agents, scoreboards, RAL, SVA, coverage and protocol errors are done; the no-X properties and a commercial-simulator run are still open. |
