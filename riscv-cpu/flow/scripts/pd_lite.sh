#!/usr/bin/env bash
# "PD-lite" flow on sky130hd, used because the ORFS Docker image (6.5 GB)
# did not fit on the shared disk. Steps:
#   1. Yosys 0.33 (host): synth -flatten, dfflibmap + abc -D <period> onto
#      sky130_fd_sc_hd__tt_025C_1v80 (dont-use cells removed, see
#      lib_dont_use.py)
#   2. OpenROAD (litex-hub conda build f12e2f4, 2022): floorplan at 40 %
#      utilisation, I/O pin placement, global placement, repair_design,
#      clock tree synthesis, repair_timing (setup and hold), detailed
#      placement, global routing; parasitics estimated from the global routes
#   3. OpenSTA (inside OpenROAD): setup/hold, Fmax, area, vectorless power
# NOT done: power grid, detailed routing, DRC/LVS, parasitic extraction.
# Usage: flow/scripts/pd_lite.sh <top> <period_ns> <outdir>
# Env: LIB (dont-use-free liberty), TLEF, LEF, SETRC (ORFS sky130hd platform
#      files), OPENROAD (binary). RESYNTH=1 forces a new synthesis.
set -euo pipefail
top=$1; period=$2; out=$3
root=$(cd "$(dirname "$0")/../.." && pwd)
for v in LIB TLEF LEF SETRC OPENROAD; do : "${!v:?set $v}"; done
mkdir -p "$out"
ps=$(python3 -c "print(int(float('$period')*1000))")
cat > "$out/synth.ys" <<YS
read_verilog -sv $root/rtl/rv_alu.sv $root/rtl/rv_decode.sv $root/rtl/rv_core.sv $root/rtl/rv_single.sv $root/rtl/rv_cache.sv $root/flow/src/rv_pd.sv
hierarchy -check -top $top
synth -top $top -flatten
dfflibmap -liberty $LIB
abc -D $ps -liberty $LIB
opt_clean -purge
hilomap -singleton -hicell sky130_fd_sc_hd__conb_1 HI -locell sky130_fd_sc_hd__conb_1 LO
setundef -zero
splitnets -ports
opt_clean -purge
tee -o $out/synth_area.txt stat -liberty $LIB
write_verilog -noattr -noexpr $out/netlist.v
YS
if [ ! -f "$out/netlist.v" ] || [ "${RESYNTH:-0}" = 1 ]; then
  yosys -q -l "$out/yosys.log" -s "$out/synth.ys" 2>/dev/null
fi
cat > "$out/pd.tcl" <<TCL
read_lef $TLEF
read_lef $LEF
read_liberty $LIB
read_verilog $out/netlist.v
link_design $top
# constraints: as designs/common/constraint.sdc (I/O delays 20 % of the period)
create_clock -name core_clock -period $period [get_ports clk]
set clk_idx [lsearch [all_inputs] [get_ports clk]]
set ins [lreplace [all_inputs] \$clk_idx \$clk_idx]
set_input_delay [expr {$period * 0.2}] -clock core_clock \$ins
set_output_delay [expr {$period * 0.2}] -clock core_clock [all_outputs]
set_driving_cell -lib_cell sky130_fd_sc_hd__buf_2 \$ins
set_load 0.005 [all_outputs]
source $SETRC
initialize_floorplan -utilization 40 -aspect_ratio 1 -core_space 5 -site unithd
make_tracks
place_pins -hor_layers met3 -ver_layers met2
global_placement -density 0.6
estimate_parasitics -placement
repair_design
detailed_placement
clock_tree_synthesis -root_buf sky130_fd_sc_hd__clkbuf_16 -buf_list {sky130_fd_sc_hd__clkbuf_4 sky130_fd_sc_hd__clkbuf_8}
set_propagated_clock [all_clocks]
estimate_parasitics -placement
repair_timing -setup
repair_timing -hold
detailed_placement
set_routing_layers -signal met1-met5 -clock met3-met5
global_route -congestion_iterations 50
estimate_parasitics -global_routing
puts "=== worst setup path"
report_checks -path_delay max -digits 3
puts "=== worst hold path"
report_checks -path_delay min -digits 3
puts "WNS_SETUP [sta::worst_slack -max]"
puts "WNS_HOLD [sta::worst_slack -min]"
puts "TNS_SETUP [sta::total_negative_slack -max]"
report_design_area
set_power_activity -input -activity 0.1
report_power
TCL
"$OPENROAD" -no_init -exit "$out/pd.tcl" > "$out/pd.log" 2>&1 || { tail -20 "$out/pd.log"; exit 1; }
python3 - "$out" "$top" "$period" <<'PY'
import json, re, sys
out, top, period = sys.argv[1], sys.argv[2], float(sys.argv[3])
log = open(f"{out}/pd.log").read(); sa = open(f"{out}/synth_area.txt").read()
g = lambda pat, s: (lambda m: float(m.group(1)) if m else None)(re.search(pat, s, re.M))
wns = g(r"^WNS_SETUP (\S+)", log)
d = dict(top=top, clock_ns=period, setup_wns_ns=wns, hold_wns_ns=g(r"^WNS_HOLD (\S+)", log),
         setup_tns_ns=g(r"^TNS_SETUP (\S+)", log),
         fmax_mhz=round(1000.0 / (period - min(wns, 0.0) - (wns if wns > 0 else 0) * 0), 1) if wns is not None else None,
         synth_cells=int(g(r"Number of cells:\s+(\d+)", sa) or 0),
         synth_cell_area_um2=g(r"Chip area for module '\S+': (\S+)", sa),
         flops=sum(int(n) for n in re.findall(r"sky130_fd_sc_hd__(?:df|edf|sdf)\w*\s+(\d+)", sa)),
         design_area_um2=g(r"Design area (\S+) u\^2", log), utilization=g(r"Design area \S+ u\^2 (\S+)%", log),
         power_total_w=g(r"^Total\s+\S+\s+\S+\s+\S+\s+(\S+)", log))
# Fmax: for a failing run, 1/(period - wns); for a passing run, the same
# formula gives a frequency the run did NOT demonstrate, so report only the
# met clock (timing_met) and leave the extrapolation in fmax_est_mhz.
d["timing_met"] = wns is not None and wns >= 0 and (d["hold_wns_ns"] or 0) >= 0
d["fmax_mhz"] = round(1000.0 / period, 1) if d["timing_met"] else None
d["fmax_est_mhz"] = round(1000.0 / (period - wns), 1) if wns is not None else None
print(json.dumps(d))
PY
