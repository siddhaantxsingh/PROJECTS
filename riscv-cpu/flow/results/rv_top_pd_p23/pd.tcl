read_lef /tmp/claude-0/-home-user/6fee72ad-dbc2-5262-982a-1a11b9d97e54/scratchpad/pdk/lef/sky130_fd_sc_hd.tlef
read_lef /tmp/claude-0/-home-user/6fee72ad-dbc2-5262-982a-1a11b9d97e54/scratchpad/pdk/lef/sky130_fd_sc_hd_merged.lef
read_liberty /tmp/claude-0/-home-user/6fee72ad-dbc2-5262-982a-1a11b9d97e54/scratchpad/pdk/sky130hd_tt_nodontuse.lib
read_verilog /home/user/PROJECTS/riscv-cpu/flow/build/rv_top_pd_p23/netlist.v
link_design rv_top_pd
# constraints: as designs/common/constraint.sdc (I/O delays 20 % of the period)
create_clock -name core_clock -period 23 [get_ports clk]
set clk_idx [lsearch [all_inputs] [get_ports clk]]
set ins [lreplace [all_inputs] $clk_idx $clk_idx]
set_input_delay [expr {23 * 0.2}] -clock core_clock $ins
set_output_delay [expr {23 * 0.2}] -clock core_clock [all_outputs]
set_driving_cell -lib_cell sky130_fd_sc_hd__buf_2 $ins
set_load 0.005 [all_outputs]
source /tmp/claude-0/-home-user/6fee72ad-dbc2-5262-982a-1a11b9d97e54/scratchpad/pdk/lef/setRC.tcl
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
