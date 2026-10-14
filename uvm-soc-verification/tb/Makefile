SIM ?= verilator
TOPLEVEL_LANG ?= verilog
RTL := $(abspath ../rtl)
VERILOG_SOURCES ?= $(RTL)/periph_soc.sv $(RTL)/apb_uart.sv $(RTL)/apb_timer.sv $(RTL)/apb_gpio.sv
TOPLEVEL := periph_soc
MODULE := test_soc
EXTRA_ARGS += -Wno-fatal $(BUG_DEFINES)
include $(shell cocotb-config --makefiles)/Makefile.sim
