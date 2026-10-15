# Caches and memory system

`rtl/rv_cache.sv` adds an instruction cache, a data cache with a write
buffer, and a bus arbiter in front of one external memory bus. The wrapper
`rv_top` holds `rv_core` plus all of these. The idealised-memory build
(`rv_core` alone, combinational memories) is still the default `make sim` /
`make test` configuration, so both can be compared.

## Design

| item | choice |
|---|---|
| I-cache | read-only, direct-mapped or 2-way set-associative (1-bit LRU), `I_SETS` lines per way |
| D-cache | write-through, **no write-allocate**, direct-mapped or 2-way (1-bit LRU), `D_SETS` lines per way |
| line size | `LINE_WORDS` words (default 4 = 16 bytes), shared by both caches |
| write buffer | FIFO of `WB_DEPTH` word writes (power of two, at least 2), no merging |
| lookup | combinational in the same cycle as the core's request (tag compare + data read), so a hit costs no cycle |
| miss | the core stalls (`imem_ready` / `dmem_ready` low); the whole line is fetched (one beat per cycle after the memory latency) and installed, then the access hits |
| uncached space | addresses with bit 31 = 0 (console, interrupt ack and counter) and one 128-byte window at `tohost` (the HTIF host interface); loads there are single-word bus reads |
| bus | one transaction at a time; priority: D-cache read > write buffer when it must drain > I-cache refill > write buffer otherwise |
| memory model | in the C++ harness: `--mem-latency L` cycles to the first beat of a read, then one word per cycle; each posted write keeps memory busy `--mem-write-cycles W` cycles (default 2) |

Parameters: `rv_top #(.I_SETS, .I_WAYS, .D_SETS, .D_WAYS, .LINE_WORDS, .WB_DEPTH)`.
In the Makefile they are given as `CACHE=I_SETS:I_WAYS:D_SETS:D_WAYS:LINE_WORDS:WB_DEPTH`,
for example `make test-cache CACHE=32:2:32:2:4:4 MEMLAT=30`.

### Why write-through with a write buffer, not write-back

* Memory holds every store as soon as the buffer drains. FENCE.I therefore
  only needs to drain the buffer and invalidate the I-cache; there is no
  dirty-line walk.
* The host side (HTIF syscalls in the riscv-tests benchmarks) reads memory
  directly. No flush protocol is needed.
* There is no dirty-eviction path, so the refill state machine stays short.
* The cost is one bus write per store. The buffer hides it unless stores
  arrive faster than memory accepts them. Those cycles are counted
  (`wbuf_full_cycles`).

A write-back cache would save bus bandwidth on store-heavy code. With one
core, one bus and no other masters, the simpler write-through design was the
better trade for this project.

### Ordering rules (what keeps it correct)

1. A D-cache read (line refill or uncached load) goes to the bus only when
   the write buffer is empty. A load can then never read memory that an
   older buffered store has not yet updated. Uncached device loads are
   therefore also ordered after all older stores.
2. A store that hits updates the cached line in the same cycle as it enters
   the buffer. Later loads that hit see it at once.
3. Store misses do not allocate. The following load misses, waits for the
   buffer (rule 1) and refills from memory, which by then holds the store.
4. **FENCE.I** (the core's `fencei` pulse when it commits):
   * every I-cache line is invalidated in that cycle;
   * a refill already in flight completes but is not marked valid, because it
     may hold pre-FENCE.I bytes;
   * new I-cache refills wait until the write buffer is empty.

   The redirected fetch after FENCE.I therefore sees every older store.
5. **Host writes.** When the harness's HTIF writes memory (a syscall reply,
   `tohost`, `fromhost`), it reports each written address on the snoop port.
   The D-cache then drops that line. The bus is held until the snoops are
   applied. `tohost`/`fromhost` themselves are in the uncached window, so
   the program's polling loop reads memory.

### Self-modifying code: what FENCE.I does and does not guarantee

The ISA guarantees that stores are visible to instruction fetch only after
FENCE.I. This design follows that rule:

* **With FENCE.I:** the new instruction is always fetched. Checked by
  `tests/cache_test.S` (part 5) in lockstep, and by `rv32ui-p-fence_i` on
  every cache configuration.
* **Without FENCE.I:** the result depends on whether the old line is still
  cached. `tests/smc_nofence.S` patches an instruction that was just
  executed, then runs it again with no FENCE.I:
  * on the idealised-memory core, the new instruction runs;
  * on the default cached configuration, the stale one runs;
  * on the 4-line configuration, the line had already been evicted, so the
    new one runs.

  `make test-smc` checks these outcomes. Both are legal. Software must use
  FENCE.I.

The I-cache does not snoop stores, and there is no hardware coherence beyond
rule 5.

## Verification

Every cached run uses the same lockstep ISS check as the idealised core:
every retired instruction's pc, encoding, register write, memory address,
mask and data. A stale or wrong cache line shows up as a mismatch at the
first instruction that uses it.

| check | command | configurations |
|---|---|---|
| riscv-tests rv32ui/um/mi (64 + 2 expected failures) | `make test-cache-all` | 1 KiB DM, latency 20; 256 B 2-way, WB 2, latency 1; 64 B DM, WB 2, latency 50 |
| 400 constrained-random programs + directed cache test | same | same |
| interrupt stress (4 seeds) | same | same |
| self-modifying code behaviour | `make test-smc` | each of the above |
| Spike commit-log comparison (lockstep off) | `tools/spike-compare.py --sim obj_dir_c…/Vrv_top` | 1 KiB DM and 64 B DM, latency 20 |
| benchmarks under lockstep | `tools/cache-sweep.sh` | 4 cache configurations × 4 latencies |
| bug injection | `tools/mutate-cache.py` | 2-way, 16 sets, WB 2, latency 10 |

The directed test `tests/cache_test.S` covers:

1. conflict evictions (16 lines 1 KiB apart, then ping-pong between two lines
   4 KiB apart);
2. store-then-load to the same line, with byte, half and word widths;
3. a store miss (no allocate) followed by a load of the same word;
4. a burst of 32 stores that fills the write buffer, then loads of them;
5. self-modifying code with FENCE.I, done twice;
6. an uncached MMIO load between cached loads.

### Interrupt livelock note

With the smallest caches (64 B) and 50-cycle memory, one interrupt (handler
plus return) takes longer than the harness's default mean of 64 cycles
between interrupts. The program then never leaves the handler. This is a
livelock caused by the test's interrupt rate, not a lost or wrong interrupt.
Lockstep stayed clean, but the run timed out. That configuration's
interrupt test therefore uses `--irq-period 2048`.

### Bug injection

`tools/mutate-cache.py` applies one-line bugs to a copy of `rv_cache.sv`,
rebuilds, and runs a short suite:

* the directed cache test;
* 60 random programs;
* the interrupt test;
* Dhrystone, which exercises the HTIF window and the snoop port.

Results are in [caches_results.md](caches_results.md).
