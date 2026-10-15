# ORFS design config for the pipelined core with idealised (external)
# memories. NOT RUN: the ORFS image did not fit on the build machine's disk
# (see docs/OWNER_ACTIONS.md). Results in flow/results/ come from pd_lite.sh.
export DESIGN_NAME     = rv_core_pd
export DESIGN_NICKNAME = rv_core
export PLATFORM        = sky130hd
export VERILOG_FILES = /work/build/rv_core_pd.v
export SDC_FILE      = /work/designs/common/constraint.sdc
export CORE_UTILIZATION ?= 40
export PLACE_DENSITY    ?= 0.6
export TNS_END_PERCENT  = 100
