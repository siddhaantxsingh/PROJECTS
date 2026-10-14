# int8-fp8-accelerator entry points (see README.md)
.PHONY: lint test test-n4 model-test accuracy synth
lint:
	verilator --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM \
	  rtl/npu_pe.sv rtl/npu_post.sv rtl/npu_top.sv --top-module npu_top
model-test:
	python3 -m pytest -q model/tests
test:
	NPU_PERF_OUT=$(CURDIR)/bench/results/perf_n8.txt $(MAKE) -C tb NPU_N=8
test-n4:
	NPU_PERF_OUT=$(CURDIR)/bench/results/perf_n4.txt $(MAKE) -C tb NPU_N=4
accuracy:
	python3 bench/accuracy.py
synth:
	mkdir -p synth/results && yosys -q -s synth/area.ys
