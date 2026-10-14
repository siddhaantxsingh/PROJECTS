# UVM SoC verification (project 12)

A UVM testbench for an APB peripheral subsystem: address decoder, UART, timer, GPIO and interrupt controller, about 450 lines of SystemVerilog in `rtl/`.

It is written with **pyuvm** (IEEE 1800.2 UVM in Python) on cocotb 1.9 and Verilator 5.020. No SystemVerilog simulator able to run the UVM class library is available here: Verilator cannot, and no commercial simulator is installed. pyuvm keeps the UVM structure (agents, drivers, monitors, sequencers, sequences, analysis ports, scoreboards, phasing, ConfigDB) on a free simulator.

**Status: done.** Over 10 seeds × 7 tests, all 70 runs pass, merged functional coverage is **82 / 82 bins (100 %)**, and **12 / 12 injected RTL bugs are caught**. The latest regression is `results/2026-10-14/regression.json`.

## Testbench

```
            ┌────────────── SocEnv ─────────────────────────────────────────────┐
 sequences ─┤ ApbAgent (driver, monitor, sequencer) ──► coverage                 │
            │ UartAgent (RX-line driver, independent TX decoder) ──► TX checker │
            │ GpioAgent (input driver)                                          │
            │ LockstepScoreboard: cycle-accurate reference model vs DUT, every  │
            │   output on every cycle                                           │
            └───────────────────────────────────────────────────────────────────┘
```

* **Lockstep scoreboard** (`tb/refmodel.py`). A cycle-accurate Python model of the whole subsystem, written from the register specification. Every cycle it compares PREADY, PSLVERR, PRDATA (on reads), the UART TX line, the GPIO outputs and the IRQ pin. On the first mismatch the test stops.
* **Model-independent checks.**
  * The TX line is decoded by its own monitor. Every accepted byte must appear, in order, with correct framing.
  * Sequences assert specification behaviour directly: RX data integrity, error flags, overrun keeping the first 8 bytes, glitch rejection, W1C per bit, one-shot vs auto-reload, interrupt routing at the pin.
* **Coverage.** 82 named bins:
  * every register in both directions;
  * slave errors, wait states and back-to-back transfers;
  * UART parity × baud-class crosses, FIFO full and overflow, every RX error, every interrupt cause;
  * timer modes, GPIO edges and W1C races;
  * interrupt-controller routing.
* **Tests.** `RegisterTest`, `UartTxTest`, `UartRxTest`, `TimerTest`, `GpioTest`, `IntcTest` and `StressTest` (all blocks and both UART directions at once). They are constrained-random, with the seed taken from cocotb's `RANDOM_SEED`.

## What bug injection found

`tools/regress.py --bugs` rebuilds the DUT with each of 12 bugs behind `ifdef`s and runs the suite.

**The first run caught 11 of 12.** `BUG_UART_W1C` survived: writing bit 2 of `IRQ_ST` cleared the parity-error flag instead of the overrun flag.

The tests only ever cleared all error flags at once, and the random register test never wrote `IRQ_ST` while a flag was set. The lockstep model could not see a difference that the stimulus never produced. Adding per-bit W1C stimulus with two flags set closed the hole.

The same rerun also closed the last four coverage holes: two UART configuration crosses, plus interrupt routing of the UART and timer while enabled. The fixes were a deterministic sweep of the parity × baud crosses and a directed `IntcTest`.

| Bug | Caught by |
|---|---|
| UART parity inverted | UartTxTest, StressTest |
| TX FIFO one entry short | UartTxTest, StressTest |
| RX samples at the bit edge instead of the middle | UartRxTest, StressTest |
| W1C clears the wrong flag | UartRxTest (after the fix above) |
| UART skips its wait state | RegisterTest, UartTxTest, UartRxTest, IntcTest, StressTest |
| baud divider clamped at 2 instead of 4 | UartTxTest |
| timer reloads to 1 | TimerTest |
| prescaler off by one | IntcTest |
| GPIO W1C clears every flag | GpioTest |
| GPIO edge lost during a W1C | GpioTest |
| interrupt controller ignores the GPIO mask | GpioTest, IntcTest, StressTest |
| decoder aliases address bits 11:8 | RegisterTest |

## Running

```sh
python3 -m venv venv && venv/bin/pip install -r tb/requirements.txt    # plus verilator >= 5.020
cd tb && PATH=../venv/bin:$PATH make SIM=verilator                    # all tests, random seed
PATH=venv/bin:$PATH python3 tools/regress.py --seeds 10 --bugs        # regression + coverage + bug injection
```

## Documentation

* [Verification plan](docs/verification_plan.md): features → stimulus → checks → coverage bins.
* [Limitations](docs/limitations.md).
* [Requirements matrix](docs/requirements.md).
