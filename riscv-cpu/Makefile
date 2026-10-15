# rv_core build and test entry points. See README.md.
RISCV_PREFIX ?= riscv64-unknown-elf-
CC        = $(RISCV_PREFIX)gcc
OBJDUMP   = $(RISCV_PREFIX)objdump
ARCH      = -march=rv32im_zicsr_zifencei -mabi=ilp32
RVTESTS  ?= third_party/riscv-tests
BUILD     = build
SIM       = obj_dir/Vrv_core
RTL       = rtl/rv_alu.sv rtl/rv_decode.sv rtl/rv_core.sv
RTL_TOP   = $(RTL) rtl/rv_cache.sv
JOBS     ?= 4

.PHONY: all sim lint isa test-isa random test-random irq test-irq bench test clean synth fetch test-variants test-spike report

all: sim

$(SIM): $(RTL) tb/sim_main.cpp tb/iss.cpp tb/iss.h
	verilator --cc --exe --build -j $(JOBS) -O3 --x-assign fast --x-initial fast \
	  -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL --assert \
	  -CFLAGS "-O2 -std=c++17" --top-module rv_core $(RTL) tb/sim_main.cpp tb/iss.cpp
sim: $(SIM)

lint:
	verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL --top-module rv_core $(RTL)
	verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL --top-module rv_top $(RTL_TOP)
	verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM --top-module rv_single $(RTL_SINGLE)
	verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL --top-module rv_top -GI_WAYS=2 -GD_WAYS=2 -GLINE_WORDS=8 $(RTL_TOP)

fetch:
	tools/fetch-riscv-tests.sh $(RVTESTS)
	tools/fetch-coremark.sh third_party/coremark

# ---------------------------------------------------------------- riscv-tests
ISA_SUITES = rv32ui rv32um rv32mi
ISA_SRCS   = $(foreach s,$(ISA_SUITES),$(wildcard $(RVTESTS)/isa/$(s)/*.S))
ISA_ELFS   = $(foreach f,$(ISA_SRCS),$(BUILD)/isa/$(notdir $(patsubst %/,%,$(dir $(f))))-p-$(basename $(notdir $(f))).elf)

define isa_rule
$(BUILD)/isa/$(1)-p-%.elf: $(RVTESTS)/isa/$(1)/%.S
	@mkdir -p $$(@D)
	$(CC) $(ARCH) -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles \
	  -I$(RVTESTS)/env/p -I$(RVTESTS)/isa/macros/scalar -T$(RVTESTS)/env/p/link.ld $$< -o $$@
endef
$(foreach s,$(ISA_SUITES),$(eval $(call isa_rule,$(s))))

isa: $(ISA_ELFS)
# Tests that cannot pass on this design by construction (docs/verification.md):
#   rv32ui-p-ma_data   needs hardware misaligned loads/stores (this core traps)
#   rv32mi-p-pmpaddr   needs at least one PMP entry (this core has none)
ISA_XFAIL = rv32ui-p-ma_data rv32mi-p-pmpaddr
test-isa: $(SIM) isa
	tools/run-tests.sh $(SIM) $(filter-out $(foreach t,$(ISA_XFAIL),$(BUILD)/isa/$(t).elf),$(ISA_ELFS))
	tools/run-tests.sh --expect-fail $(SIM) $(foreach t,$(ISA_XFAIL),$(BUILD)/isa/$(t).elf)

# ------------------------------------------------------- random instruction tests
RAND_N    ?= 200
RAND_SEED ?= 1
random: tools/rvgen.py sw/link.ld
	python3 tools/rvgen.py --count $(RAND_N) --seed $(RAND_SEED) --out $(BUILD)/rand
	$(MAKE) --no-print-directory rand-elfs
RAND_SRCS = $(wildcard $(BUILD)/rand/*.S)
rand-elfs: $(RAND_SRCS:.S=.elf)
$(BUILD)/rand/%.elf: $(BUILD)/rand/%.S sw/link.ld
	$(CC) $(ARCH) -static -nostdlib -nostartfiles -T sw/link.ld $< -o $@
test-random: $(SIM) random
	tools/run-tests.sh $(SIM) $(BUILD)/rand/*.elf

# -------------------------------------------------------------- interrupts
$(BUILD)/irq/irq_test.elf: tests/irq_test.S sw/link.ld
	@mkdir -p $(@D)
	$(CC) $(ARCH) -static -nostdlib -nostartfiles -T sw/link.ld $< -o $@
test-irq: $(SIM) $(BUILD)/irq/irq_test.elf
	for s in 1 2 3 4 5 6 7 8; do $(SIM) --quiet --irq $$s $(BUILD)/irq/irq_test.elf || exit 1; done

# ---------------------------------------------------------------- benchmarks
SIM_NOBP = obj_dir_nobp/Vrv_core
$(SIM_NOBP): $(RTL) tb/sim_main.cpp tb/iss.cpp tb/iss.h
	verilator --cc --exe --build -j $(JOBS) -O3 --x-assign fast --x-initial fast --Mdir obj_dir_nobp \
	  -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL --assert -GBPRED_EN=0 \
	  -CFLAGS "-O2 -std=c++17" --top-module rv_core $(RTL) tb/sim_main.cpp tb/iss.cpp

# riscv-tests benchmarks + CoreMark, with and without the branch predictor
bench: $(SIM) $(SIM_NOBP)
	tools/bench.sh $(SIM) $(SIM_NOBP)

synth:
	@mkdir -p $(BUILD)
	yosys -q -s synth/synth.ys -l $(BUILD)/synth.log

# copy benchmark + synthesis results into results/ and render docs/benchmarks.md
report:
	python3 tools/report.py

# predictor corner cases: a 4-entry BTB (heavy aliasing) and no predictor
SIM_BTB2 = obj_dir_btb2/Vrv_core
$(SIM_BTB2): $(RTL) tb/sim_main.cpp tb/iss.cpp tb/iss.h
	verilator --cc --exe --build -j $(JOBS) -O3 --x-assign fast --x-initial fast --Mdir obj_dir_btb2 \
	  -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL --assert -GBTB_BITS=2 \
	  -CFLAGS "-O2 -std=c++17" --top-module rv_core $(RTL) tb/sim_main.cpp tb/iss.cpp
test-variants: $(SIM_BTB2) $(SIM_NOBP) random
	tools/run-tests.sh $(SIM_BTB2) $(BUILD)/rand/*.elf
	tools/run-tests.sh $(SIM_NOBP) $(BUILD)/rand/*.elf

# single-cycle baseline (rtl/rv_single.sv), same harness and lockstep ISS
SIM_SINGLE = obj_dir_single/Vrv_core
RTL_SINGLE = rtl/rv_alu.sv rtl/rv_decode.sv rtl/rv_single.sv
$(SIM_SINGLE): $(RTL_SINGLE) tb/sim_main.cpp tb/iss.cpp tb/iss.h
	verilator --cc --exe --build -j $(JOBS) -O3 --x-assign fast --x-initial fast --Mdir obj_dir_single \
	  -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM --assert --prefix Vrv_core \
	  -CFLAGS "-O2 -std=c++17" --top-module rv_single $(RTL_SINGLE) tb/sim_main.cpp tb/iss.cpp
test-single: $(SIM_SINGLE) isa random $(BUILD)/irq/irq_test.elf $(BUILD)/cache/cache_test.elf
	tools/run-tests.sh $(SIM_SINGLE) $(filter-out $(foreach t,$(ISA_XFAIL),$(BUILD)/isa/$(t).elf),$(ISA_ELFS))
	tools/run-tests.sh --expect-fail $(SIM_SINGLE) $(foreach t,$(ISA_XFAIL),$(BUILD)/isa/$(t).elf)
	tools/run-tests.sh $(SIM_SINGLE) $(BUILD)/rand/*.elf $(BUILD)/cache/cache_test.elf
	for s in 1 2 3 4; do $(SIM_SINGLE) --quiet --irq $$s $(BUILD)/irq/irq_test.elf || exit 1; done

# cross-check against Spike (SPIKE=/path/to/spike)
SPIKE ?= spike
test-spike: $(SIM) random
	python3 tools/spike-compare.py --spike $(SPIKE) --sim $(SIM) $(BUILD)/rand/*.elf

# ------------------------------------------------------------ cached builds
# CACHE=I_SETS:I_WAYS:D_SETS:D_WAYS:LINE_WORDS:WB_DEPTH ; one obj dir per configuration
CACHE ?= 64:1:64:1:4:4
cache_dir = obj_dir_c$(subst :,_,$(1))
cache_sim = $(call cache_dir,$(1))/Vrv_top
define cache_rule
$(call cache_sim,$(1)): $(RTL_TOP) tb/sim_main.cpp tb/iss.cpp tb/iss.h
	verilator --cc --exe --build -j $(JOBS) -O3 --x-assign fast --x-initial fast --Mdir $(call cache_dir,$(1)) \
	  -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL --assert \
	  $$(foreach kv,$$(join I_SETS: I_WAYS: D_SETS: D_WAYS: LINE_WORDS: WB_DEPTH:,$$(subst :, ,$(1))),-G$$(subst :,=,$$(kv))) \
	  -CFLAGS "-O2 -std=c++17 -DCACHED $$(foreach kv,$$(join I_SETS: I_WAYS: D_SETS: D_WAYS: LINE_WORDS: WB_DEPTH:,$$(subst :, ,$(1))),-D$$(subst :,_SIM=,$$(kv)))" \
	  --top-module rv_top $(RTL_TOP) tb/sim_main.cpp tb/iss.cpp
endef
CACHE_CONFIGS = 64:1:64:1:4:4 16:2:16:2:4:2 4:1:4:1:4:2 $(CACHE)
$(foreach c,$(sort $(CACHE_CONFIGS)),$(eval $(call cache_rule,$(c))))
SIMC = $(call cache_sim,$(CACHE))
MEMLAT ?= 20
IRQ_PERIOD ?= 64
sim-cache: $(SIMC)

# riscv-tests, random programs and interrupts on the cached system (lockstep)
test-cache: $(SIMC) isa random $(BUILD)/irq/irq_test.elf $(BUILD)/cache/cache_test.elf test-smc
	SIM_ARGS="--mem-latency $(MEMLAT)" tools/run-tests.sh $(SIMC) $(filter-out $(foreach t,$(ISA_XFAIL),$(BUILD)/isa/$(t).elf),$(ISA_ELFS))
	SIM_ARGS="--mem-latency $(MEMLAT)" tools/run-tests.sh --expect-fail $(SIMC) $(foreach t,$(ISA_XFAIL),$(BUILD)/isa/$(t).elf)
	SIM_ARGS="--mem-latency $(MEMLAT)" tools/run-tests.sh $(SIMC) $(BUILD)/rand/*.elf $(BUILD)/cache/cache_test.elf
	for s in 1 2 3 4; do $(SIMC) --quiet --mem-latency $(MEMLAT) --irq-period $(IRQ_PERIOD) --irq $$s $(BUILD)/irq/irq_test.elf || exit 1; done

# directed cache corner cases (conflicts, store-then-load, FENCE.I)
$(BUILD)/cache/%.elf: tests/%.S sw/link.ld
	@mkdir -p $(@D)
	$(CC) $(ARCH) -static -nostdlib -nostartfiles -T sw/link.ld $< -o $@

# self-modifying code without FENCE.I: the ISA allows either outcome. With
# idealised memory the new instruction runs (exit 0). With the I-cache the
# stale one runs (exit code 2) if the line is still cached, which it is in the
# default configuration (checked); tiny caches may have evicted it already.
test-smc: $(SIM) $(SIMC) $(BUILD)/cache/smc_nofence.elf
	$(SIM) --quiet --no-lockstep $(BUILD)/cache/smc_nofence.elf
	$(SIMC) --quiet --no-lockstep $(BUILD)/cache/smc_nofence.elf 2>&1 | tee $(BUILD)/cache/smc.log | grep -qE "PASS|exit=2"
	@if [ "$(CACHE)" = "64:1:64:1:4:4" ]; then grep -q "exit=2" $(BUILD)/cache/smc.log; fi
	@grep -q "exit=2" $(BUILD)/cache/smc.log && echo "smc without FENCE.I: stale instruction executed" || echo "smc without FENCE.I: new instruction executed"

# the small configurations stress evictions; latency 1 and 50 stress timing.
# With 4-line caches and 50-cycle memory, one interrupt (handler + return)
# takes longer than 64 cycles, so at the default interrupt rate the program
# never leaves the handler (livelock, not a fault): that run uses a lower rate.
test-cache-all:
	$(MAKE) --no-print-directory test-cache CACHE=64:1:64:1:4:4 MEMLAT=20
	$(MAKE) --no-print-directory test-cache CACHE=16:2:16:2:4:2 MEMLAT=1
	$(MAKE) --no-print-directory test-cache CACHE=4:1:4:1:4:2 MEMLAT=50 IRQ_PERIOD=2048

test: test-isa test-random test-irq test-variants

clean:
	rm -rf obj_dir obj_dir_nobp obj_dir_btb2 obj_dir_c* obj_dir_single $(BUILD)
