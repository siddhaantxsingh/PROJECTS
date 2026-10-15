// Physical-design wrappers: fix parameters and tie off what the measured
// configuration does not use, so the synthesised netlist is the one that was
// benchmarked.
//   rv_core_pd    pipelined core, idealised memories (imem/dmem_ready = 1),
//                 memories outside the block (their ports are top-level I/O)
//   rv_single_pd  single-cycle baseline, same memory assumption
// The cached system is synthesised directly as rv_top (caches as flip-flops).
`default_nettype none
module rv_core_pd (
    input  wire clk, input wire rst_n,
    output logic [31:0] imem_addr, input wire [31:0] imem_rdata,
    output logic dmem_re, output logic dmem_we, output logic [31:0] dmem_addr,
    output logic [3:0] dmem_wstrb, output logic [31:0] dmem_wdata, input wire [31:0] dmem_rdata,
    input wire irq_software, input wire irq_timer, input wire irq_external,
    output logic rvfi_valid, output logic [31:0] rvfi_pc_rdata
);
    /* verilator lint_off PINCONNECTEMPTY */
    rv_core u (.clk, .rst_n, .imem_addr, .imem_rdata, .imem_ready(1'b1), .dmem_re, .dmem_we, .dmem_addr,
        .dmem_wstrb, .dmem_wdata, .dmem_rdata, .dmem_ready(1'b1), .fencei(), .irq_software, .irq_timer,
        .irq_external, .rvfi_valid, .rvfi_order(), .rvfi_insn(), .rvfi_pc_rdata, .rvfi_pc_wdata(),
        .rvfi_rd_addr(), .rvfi_rd_wdata(), .rvfi_trap(), .rvfi_intr(), .rvfi_cause(), .rvfi_mem_addr(),
        .rvfi_mem_rmask(), .rvfi_mem_wmask(), .rvfi_mem_wdata(), .rvfi_mem_rdata(), .perf_cycles(),
        .perf_instret(), .perf_branches(), .perf_mispredicts(), .perf_load_use_stalls(), .perf_div_stalls(),
        .perf_imem_stalls(), .perf_dmem_stalls(), .perf_fetches());
endmodule

module rv_single_pd (
    input  wire clk, input wire rst_n,
    output logic [31:0] imem_addr, input wire [31:0] imem_rdata,
    output logic dmem_re, output logic dmem_we, output logic [31:0] dmem_addr,
    output logic [3:0] dmem_wstrb, output logic [31:0] dmem_wdata, input wire [31:0] dmem_rdata,
    input wire irq_software, input wire irq_timer, input wire irq_external,
    output logic rvfi_valid, output logic [31:0] rvfi_pc_rdata
);
    rv_single u (.clk, .rst_n, .imem_addr, .imem_rdata, .imem_ready(1'b1), .dmem_re, .dmem_we, .dmem_addr,
        .dmem_wstrb, .dmem_wdata, .dmem_rdata, .dmem_ready(1'b1), .fencei(), .irq_software, .irq_timer,
        .irq_external, .rvfi_valid, .rvfi_order(), .rvfi_insn(), .rvfi_pc_rdata, .rvfi_pc_wdata(),
        .rvfi_rd_addr(), .rvfi_rd_wdata(), .rvfi_trap(), .rvfi_intr(), .rvfi_cause(), .rvfi_mem_addr(),
        .rvfi_mem_rmask(), .rvfi_mem_wmask(), .rvfi_mem_wdata(), .rvfi_mem_rdata(), .perf_cycles(),
        .perf_instret(), .perf_branches(), .perf_mispredicts(), .perf_load_use_stalls(), .perf_div_stalls(),
        .perf_imem_stalls(), .perf_dmem_stalls(), .perf_fetches());
    /* verilator lint_on PINCONNECTEMPTY */
endmodule
`default_nettype wire

// Cached system: 16-set direct-mapped I- and D-caches with 16-byte lines
// (256 B each) and a 4-entry write buffer, all as flip-flops. Only the memory
// bus, the interrupt lines and two trace bits are kept as ports.
`default_nettype none
module rv_top_pd (
    input  wire clk, input wire rst_n,
    output logic bus_req_valid, input wire bus_req_ready, output logic bus_req_we, output logic bus_req_line,
    output logic [31:0] bus_req_addr, output logic [31:0] bus_req_wdata, output logic [3:0] bus_req_wstrb,
    input wire bus_resp_valid, input wire [31:0] bus_resp_rdata,
    input wire [25:0] io_base, input wire snoop_inv, input wire [31:0] snoop_addr,
    input wire irq_software, input wire irq_timer, input wire irq_external,
    output logic rvfi_valid, output logic [31:0] rvfi_pc_rdata
);
    /* verilator lint_off PINCONNECTEMPTY */
    rv_top #(.I_SETS(16), .D_SETS(16), .LINE_WORDS(4), .WB_DEPTH(4)) u (
        .clk, .rst_n, .bus_req_valid, .bus_req_ready, .bus_req_we, .bus_req_line, .bus_req_ifetch(),
        .bus_req_addr, .bus_req_wdata, .bus_req_wstrb, .bus_resp_valid, .bus_resp_rdata, .io_base, .snoop_inv,
        .snoop_addr, .irq_software, .irq_timer, .irq_external, .rvfi_valid, .rvfi_order(), .rvfi_insn(),
        .rvfi_pc_rdata, .rvfi_pc_wdata(), .rvfi_rd_addr(), .rvfi_rd_wdata(), .rvfi_trap(), .rvfi_intr(),
        .rvfi_cause(), .rvfi_mem_addr(), .rvfi_mem_rmask(), .rvfi_mem_wmask(), .rvfi_mem_wdata(),
        .rvfi_mem_rdata(), .perf_cycles(), .perf_instret(), .perf_branches(), .perf_mispredicts(),
        .perf_load_use_stalls(), .perf_div_stalls(), .perf_imem_stalls(), .perf_dmem_stalls(), .perf_fetches(),
        .perf_dmem_accesses(), .perf_i_refills(), .perf_d_refills(), .perf_d_uncached(), .perf_wbuf_full());
    /* verilator lint_on PINCONNECTEMPTY */
endmodule
`default_nettype wire
