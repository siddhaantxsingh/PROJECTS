# rv_core build and test entry points. See README.md.
RISCV_PREFIX ?= riscv64-unknown-elf-
CC        = $(RISCV_PREFIX)gcc
OBJDUMP   = $(RISCV_PREFIX)objdump
ARCH      = -march=rv32im_zicsr_zifencei -mabi=ilp32
RVTESTS  ?= third_party/riscv-tests
BUILD     = build
SIM       = obj_dir/Vrv_core
RTL       = rtl/rv_alu.sv rtl/rv_decode.sv rtl/rv_core.sv
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

# cross-check against Spike (SPIKE=/path/to/spike)
SPIKE ?= spike
test-spike: $(SIM) random
	python3 tools/spike-compare.py --spike $(SPIKE) --sim $(SIM) $(BUILD)/rand/*.elf

test: test-isa test-random test-irq test-variants

clean:
	rm -rf obj_dir obj_dir_nobp obj_dir_btb2 $(BUILD)
