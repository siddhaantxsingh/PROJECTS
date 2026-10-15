// rv_single: a single-cycle (non-pipelined) RV32IM_Zicsr_Zifencei baseline.
//
// Every instruction is fetched, decoded, executed, accesses data memory and
// writes back in one clock cycle, so the critical path runs from the pc
// register through instruction memory, decode, register read, the ALU (or
// the 33x33 multiplier), data memory and back to the register file. The only
// multi-cycle instructions are DIV/DIVU/REM/REMU, which reuse rv_core's
// iterative divider (33 cycles).
//
// It exists to give an honest IPC x Fmax comparison point for rv_core: it
// shares rv_core's decoder, ALU, multiplier, divider, CSR set, trap and
// interrupt behaviour and its exact port list (so the same Verilator harness,
// lockstep ISS and Spike comparison run on it). It has no branch prediction
// (nothing to predict), and no caches (imem/dmem must answer in the same
// cycle; imem_ready/dmem_ready stall the whole core if low).
//
// The trace (rvfi_*) is registered: a record appears the cycle after the
// instruction executes. Those registers are verification-only.
`default_nettype none

module rv_single #(
    parameter logic [31:0] RESET_PC = 32'h8000_0000,
    parameter int          BTB_BITS = 6,     // unused (same parameters as rv_core)
    parameter bit          BPRED_EN = 1'b1   // unused
) (
    input  wire         clk,
    input  wire         rst_n,

    output logic [31:0] imem_addr,
    input  wire  [31:0] imem_rdata,
    input  wire         imem_ready,

    output logic        dmem_re,
    output logic        dmem_we,
    output logic [31:0] dmem_addr,   // byte address (word-aligned for words)
    output logic [3:0]  dmem_wstrb,
    output logic [31:0] dmem_wdata,  // already shifted into byte lanes
    input  wire  [31:0] dmem_rdata,  // the aligned word containing dmem_addr
    input  wire         dmem_ready,
    output logic        fencei,

    input  wire         irq_software,
    input  wire         irq_timer,
    input  wire         irq_external,

    output logic        rvfi_valid,
    output logic [63:0] rvfi_order,
    output logic [31:0] rvfi_insn,
    output logic [31:0] rvfi_pc_rdata,
    output logic [31:0] rvfi_pc_wdata,
    output logic [4:0]  rvfi_rd_addr,
    output logic [31:0] rvfi_rd_wdata,
    output logic        rvfi_trap,
    output logic        rvfi_intr,
    output logic [31:0] rvfi_cause,
    output logic [31:0] rvfi_mem_addr,
    output logic [3:0]  rvfi_mem_rmask,
    output logic [3:0]  rvfi_mem_wmask,
    output logic [31:0] rvfi_mem_wdata,
    output logic [31:0] rvfi_mem_rdata,

    // performance counters for the testbench
    output logic [63:0] perf_cycles,
    output logic [63:0] perf_instret,
    output logic [31:0] perf_branches,
    output logic [31:0] perf_mispredicts,
    output logic [31:0] perf_load_use_stalls,
    output logic [31:0] perf_div_stalls,
    output logic [31:0] perf_imem_stalls,   // cycles IF waited for imem_ready
    output logic [31:0] perf_dmem_stalls,   // cycles MEM waited for dmem_ready
    output logic [31:0] perf_fetches        // instructions taken from imem by IF
);

    localparam logic [11:0] CSR_MSTATUS = 12'h300, CSR_MISA = 12'h301, CSR_MIE = 12'h304,
        CSR_MTVEC = 12'h305, CSR_MCOUNTEREN = 12'h306, CSR_MSTATUSH = 12'h310,
        CSR_MCOUNTINHIBIT = 12'h320, CSR_MSCRATCH = 12'h340, CSR_MEPC = 12'h341,
        CSR_MCAUSE = 12'h342, CSR_MTVAL = 12'h343, CSR_MIP = 12'h344,
        CSR_MCYCLE = 12'hB00, CSR_MINSTRET = 12'hB02, CSR_MCYCLEH = 12'hB80, CSR_MINSTRETH = 12'hB82,
        CSR_CYCLE = 12'hC00, CSR_INSTRET = 12'hC02, CSR_CYCLEH = 12'hC80, CSR_INSTRETH = 12'hC82,
        CSR_MVENDORID = 12'hF11, CSR_MARCHID = 12'hF12, CSR_MIMPID = 12'hF13, CSR_MHARTID = 12'hF14,
        CSR_MCONFIGPTR = 12'hF15;
    localparam logic [31:0] MISA_VAL = 32'h4000_1100;

    logic        mstatus_mie, mstatus_mpie;
    logic [31:0] mie_r, mtvec_r, mscratch_r, mepc_r, mcause_r, mtval_r;
    logic [63:0] mcycle_r, minstret_r;
    wire  [31:0] mip_w = {20'b0, irq_external, 3'b0, irq_timer, 3'b0, irq_software, 3'b0};
    wire  [31:0] mstatus_w = {19'b0, 2'b11, 3'b0, mstatus_mpie, 3'b0, mstatus_mie, 3'b0};

    logic [31:0] pc;
    logic [31:0] regs [1:31];
    wire  [31:0] insn = imem_rdata;
    assign imem_addr = pc;

    logic        illegal, uses_rs1, uses_rs2, rd_we, src_a_pc, src_a_zero, src_b_imm;
    logic [4:0]  rs1, rs2, rd;
    logic [31:0] imm;
    logic [3:0]  alu_op;
    logic        is_load, is_store, mem_unsigned, is_branch, is_jal, is_jalr;
    logic [1:0]  mem_size, csr_op;
    logic [2:0]  br_funct3, md_op;
    logic        is_muldiv, is_csr, csr_imm, is_ecall, is_ebreak, is_mret, is_wfi, is_fencei, is_system;
    rv_decode u_dec (
        .insn, .illegal, .rs1, .rs2, .rd, .uses_rs1, .uses_rs2, .rd_we, .imm, .alu_op, .src_a_pc,
        .src_a_zero, .src_b_imm, .is_load, .is_store, .mem_size, .mem_unsigned, .is_branch, .br_funct3,
        .is_jal, .is_jalr, .is_muldiv, .md_op, .is_csr, .csr_op, .csr_imm, .is_ecall, .is_ebreak,
        .is_mret, .is_wfi, .is_fencei, .is_system
    );

    wire [31:0] rs1v = (rs1 == 5'd0) ? 32'd0 : regs[rs1];
    wire [31:0] rs2v = (rs2 == 5'd0) ? 32'd0 : regs[rs2];
    wire [31:0] alu_a = src_a_zero ? 32'd0 : src_a_pc ? pc : rs1v;
    wire [31:0] alu_b = src_b_imm ? imm : rs2v;
    logic [31:0] alu_y, mul_y, div_y;
    logic br_taken, div_busy, div_done, div_started;
    rv_alu    u_alu (.op(alu_op), .a(alu_a), .b(alu_b), .y(alu_y));
    rv_branch u_br  (.funct3(br_funct3), .a(rs1v), .b(rs2v), .taken(br_taken));
    rv_mul    u_mul (.op(md_op[1:0]), .a(rs1v), .b(rs2v), .y(mul_y));

    logic valid;  // low only in the first cycle after reset
    wire is_div = valid && is_muldiv && md_op[2];
    rv_div u_div (
        .clk, .rst_n, .start(is_div && !div_started && !div_busy), .kill(1'b0),
        .op(md_op[1:0]), .a(rs1v), .b(rs2v), .busy(div_busy), .done(div_done), .y(div_y)
    );
    wire div_stall = is_div && !(div_started && div_done);

    wire [31:0] pc_plus4 = pc + 32'd4;
    wire [31:0] br_target = pc + imm;
    wire [31:0] jalr_target = (rs1v + imm) & ~32'd1;
    wire ctl_taken = is_jal || is_jalr || (is_branch && br_taken);
    wire [31:0] ctl_target = is_jalr ? jalr_target : br_target;
    wire [31:0] actual_next = ctl_taken ? ctl_target : pc_plus4;
    wire misaligned_target = ctl_taken && ctl_target[1];

    wire [31:0] mem_addr = rs1v + imm;
    wire mem_misaligned = (mem_size == 2'd1 && mem_addr[0]) || (mem_size == 2'd2 && mem_addr[1:0] != 2'b00);

    wire [11:0] csr_addr = insn[31:20];
    wire [31:0] csr_src = csr_imm ? {27'b0, rs1} : rs1v;
    wire csr_writes = (csr_op == 2'd1) || (rs1 != 5'd0);
    logic [31:0] csr_rdata;
    wire [4:0] csr_lo = csr_addr[4:0];
    wire csr_hpm_idx = csr_lo >= 5'd3;  // counters/events 3..31
    wire csr_hpm = csr_hpm_idx && (csr_addr[11:5] == 7'b1011_000 || csr_addr[11:5] == 7'b1011_100 ||
                                   csr_addr[11:5] == 7'b1100_000 || csr_addr[11:5] == 7'b1100_100 ||
                                   csr_addr[11:5] == 7'b0011_001);
    logic csr_exists;
    always_comb begin
        csr_exists = 1'b1;
        csr_rdata = 32'd0;
        unique case (csr_addr)
            CSR_MSTATUS:     csr_rdata = mstatus_w;
            CSR_MISA:        csr_rdata = MISA_VAL;
            CSR_MIE:         csr_rdata = mie_r;
            CSR_MTVEC:       csr_rdata = mtvec_r;
            CSR_MCOUNTEREN:  csr_rdata = 32'd0;
            CSR_MSTATUSH:    csr_rdata = 32'd0;
            CSR_MCOUNTINHIBIT: csr_rdata = 32'd0;
            CSR_MSCRATCH:    csr_rdata = mscratch_r;
            CSR_MEPC:        csr_rdata = mepc_r;
            CSR_MCAUSE:      csr_rdata = mcause_r;
            CSR_MTVAL:       csr_rdata = mtval_r;
            CSR_MIP:         csr_rdata = mip_w;
            CSR_MCYCLE, CSR_CYCLE:       csr_rdata = mcycle_r[31:0];
            CSR_MCYCLEH, CSR_CYCLEH:     csr_rdata = mcycle_r[63:32];
            CSR_MINSTRET, CSR_INSTRET:   csr_rdata = minstret_r[31:0];
            CSR_MINSTRETH, CSR_INSTRETH: csr_rdata = minstret_r[63:32];
            CSR_MVENDORID, CSR_MARCHID, CSR_MIMPID, CSR_MHARTID, CSR_MCONFIGPTR: csr_rdata = 32'd0;
            default: begin
                // mhpmcounter3..31(h), hpmcounter3..31(h), mhpmevent3..31: hardwired zero
                // Sdtrig with zero triggers: tselect/tdata1-3 read zero, tinfo
                // reports "no trigger" (type 0). PMP with zero entries:
                // pmpcfg0-15 and pmpaddr0-63 are read-only zero (WARL).
                if (csr_hpm || csr_addr == 12'h7A0 || csr_addr == 12'h7A1 || csr_addr == 12'h7A2 ||
                    csr_addr == 12'h7A3 || (csr_addr >= 12'h3A0 && csr_addr <= 12'h3EF)) csr_rdata = 32'd0;
                else if (csr_addr == 12'h7A4) csr_rdata = 32'd1;
                else csr_exists = 1'b0;
            end
        endcase
    end
    wire csr_readonly = csr_addr[11:10] == 2'b11;
    wire csr_illegal = is_csr && (!csr_exists || (csr_writes && csr_readonly));
    logic [31:0] csr_wdata;
    always_comb begin
        unique case (csr_op)
            2'd1: csr_wdata = csr_src;
            2'd2: csr_wdata = csr_rdata | csr_src;
            default: csr_wdata = csr_rdata & ~csr_src;
        endcase
    end

    wire mem_stall = valid && (is_load || is_store) && !mem_misaligned && !dmem_ready;
    wire stall = div_stall || mem_stall || !imem_ready;

    wire [31:0] irq_pending = mip_w & mie_r;
    wire take_irq = valid && !stall && mstatus_mie && (irq_pending[11] || irq_pending[3] || irq_pending[7]);
    wire [31:0] irq_cause = irq_pending[11] ? 32'h8000_000B : irq_pending[3] ? 32'h8000_0003 : 32'h8000_0007;
    logic        exc;
    logic [31:0] exc_cause, exc_tval;
    always_comb begin
        exc = 1'b1; exc_cause = 32'd0; exc_tval = 32'd0;
        if (illegal || csr_illegal)      begin exc_cause = 32'd2; exc_tval = insn; end
        else if (is_ecall)               begin exc_cause = 32'd11; end
        else if (is_ebreak)              begin exc_cause = 32'd3; exc_tval = pc; end
        else if (misaligned_target)      begin exc_cause = 32'd0; exc_tval = ctl_target; end
        else if (is_load && mem_misaligned)  begin exc_cause = 32'd4; exc_tval = mem_addr; end
        else if (is_store && mem_misaligned) begin exc_cause = 32'd6; exc_tval = mem_addr; end
        else exc = 1'b0;
    end
    wire trap = valid && !stall && (take_irq || exc);
    wire [31:0] trap_cause = take_irq ? irq_cause : exc_cause;
    wire [31:0] trap_vector = (mtvec_r[0] && take_irq) ? {mtvec_r[31:2], 2'b00} + {trap_cause[29:0], 2'b00}
                                                      : {mtvec_r[31:2], 2'b00};
    wire fire = valid && !stall;            // the instruction completes this cycle
    wire do_mret = fire && !trap && is_mret;
    wire [31:0] next_pc = trap ? trap_vector : do_mret ? mepc_r : actual_next;

    // data memory
    logic [3:0]  st_strb;
    logic [31:0] st_data;
    always_comb begin
        unique case (mem_size)
            2'd0: begin st_strb = 4'b0001 << mem_addr[1:0]; st_data = {4{rs2v[7:0]}}; end
            2'd1: begin st_strb = 4'b0011 << {mem_addr[1], 1'b0}; st_data = {2{rs2v[15:0]}}; end
            default: begin st_strb = 4'b1111; st_data = rs2v; end
        endcase
    end
    // Accesses are qualified by the exception check but not by the interrupt:
    // an interrupt taken on a load/store cancels it (dmem_we is gated below).
    wire mem_ok = valid && !exc && !(mstatus_mie && |irq_pending);
    assign dmem_re    = mem_ok && is_load;
    assign dmem_we    = mem_ok && is_store && !stall;
    assign dmem_addr  = mem_addr;
    assign dmem_wstrb = is_store ? st_strb : 4'b0;
    assign dmem_wdata = st_data;
    assign fencei     = fire && !trap && is_fencei;

    logic [31:0] ld_val;
    logic [3:0]  ld_mask;
    always_comb begin
        logic [31:0] sh;
        sh = dmem_rdata >> {mem_addr[1:0], 3'b000};
        unique case (mem_size)
            2'd0: begin ld_val = mem_unsigned ? {24'b0, sh[7:0]} : {{24{sh[7]}}, sh[7:0]}; ld_mask = 4'b0001 << mem_addr[1:0]; end
            2'd1: begin ld_val = mem_unsigned ? {16'b0, sh[15:0]} : {{16{sh[15]}}, sh[15:0]}; ld_mask = 4'b0011 << {mem_addr[1], 1'b0}; end
            default: begin ld_val = dmem_rdata; ld_mask = 4'b1111; end
        endcase
    end

    logic [31:0] result;
    always_comb begin
        if (is_jal || is_jalr) result = pc_plus4;
        else if (is_csr) result = csr_rdata;
        else if (is_load) result = ld_val;
        else if (is_muldiv) result = md_op[2] ? div_y : mul_y;
        else result = alu_y;
    end
    wire wb = fire && !trap && rd_we && rd != 5'd0;
    wire no_count = is_csr && csr_writes && (csr_addr == CSR_MINSTRET || csr_addr == CSR_MINSTRETH);

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            pc <= RESET_PC; valid <= 1'b0; div_started <= 1'b0;
        end else begin
            valid <= 1'b1;
            if (fire) pc <= next_pc;
            if (div_stall) begin
                if (is_div && !div_started && !div_busy) div_started <= 1'b1;
            end else div_started <= 1'b0;
        end
    end
    always_ff @(posedge clk) if (wb) regs[rd] <= result;

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            mstatus_mie <= 1'b0; mstatus_mpie <= 1'b0;
            mie_r <= '0; mtvec_r <= '0; mscratch_r <= '0; mepc_r <= '0; mcause_r <= '0; mtval_r <= '0;
            mcycle_r <= '0; minstret_r <= '0;
        end else begin
            mcycle_r <= mcycle_r + 64'd1;
            if (fire && !trap && !no_count) minstret_r <= minstret_r + 64'd1;
            if (trap) begin
                mepc_r <= pc;
                mcause_r <= trap_cause;
                mtval_r <= take_irq ? 32'd0 : exc_tval;
                mstatus_mpie <= mstatus_mie;
                mstatus_mie <= 1'b0;
            end else if (do_mret) begin
                mstatus_mie <= mstatus_mpie;
                mstatus_mpie <= 1'b1;
            end else if (fire && is_csr && csr_writes) begin
                unique case (csr_addr)
                    CSR_MSTATUS: begin mstatus_mie <= csr_wdata[3]; mstatus_mpie <= csr_wdata[7]; end
                    CSR_MIE:      mie_r <= csr_wdata & 32'h0000_0888;
                    CSR_MTVEC:    mtvec_r <= {csr_wdata[31:2], 1'b0, csr_wdata[0]};
                    CSR_MSCRATCH: mscratch_r <= csr_wdata;
                    CSR_MEPC:     mepc_r <= {csr_wdata[31:2], 2'b00};
                    CSR_MCAUSE:   mcause_r <= csr_wdata;
                    CSR_MTVAL:    mtval_r <= csr_wdata;
                    CSR_MCYCLE:   mcycle_r[31:0] <= csr_wdata;
                    CSR_MCYCLEH:  mcycle_r[63:32] <= csr_wdata;
                    CSR_MINSTRET: minstret_r[31:0] <= csr_wdata;
                    CSR_MINSTRETH: minstret_r[63:32] <= csr_wdata;
                    default: ;
                endcase
            end
        end
    end

    // ---------------------------------------------------- trace (registered)
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            rvfi_valid <= 1'b0; rvfi_order <= '0;
        end else begin
            rvfi_valid <= fire;
            if (rvfi_valid) rvfi_order <= rvfi_order + 64'd1;
        end
    end
    always_ff @(posedge clk) begin
        if (fire) begin
            rvfi_insn <= insn; rvfi_pc_rdata <= pc; rvfi_pc_wdata <= next_pc;
            rvfi_rd_addr <= wb ? rd : 5'd0; rvfi_rd_wdata <= wb ? result : 32'd0;
            rvfi_trap <= trap; rvfi_intr <= take_irq; rvfi_cause <= trap_cause;
            rvfi_mem_addr <= (!trap && (is_load || is_store)) ? mem_addr : 32'd0;
            rvfi_mem_rmask <= (!trap && is_load) ? ld_mask : 4'b0;
            rvfi_mem_wmask <= (!trap && is_store) ? st_strb : 4'b0;
            rvfi_mem_wdata <= (!trap && is_store) ? st_data : 32'd0;
            rvfi_mem_rdata <= (!trap && is_load) ? dmem_rdata : 32'd0;
        end
    end

    // ------------------------------------------------------- perf counters
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            perf_branches <= '0; perf_div_stalls <= '0; perf_imem_stalls <= '0; perf_dmem_stalls <= '0;
            perf_fetches <= '0;
        end else begin
            if (fire && !trap && (is_branch || is_jal || is_jalr)) perf_branches <= perf_branches + 32'd1;
            if (div_stall) perf_div_stalls <= perf_div_stalls + 32'd1;
            if (valid && !imem_ready) perf_imem_stalls <= perf_imem_stalls + 32'd1;
            if (mem_stall) perf_dmem_stalls <= perf_dmem_stalls + 32'd1;
            if (fire) perf_fetches <= perf_fetches + 32'd1;
        end
    end
    assign perf_mispredicts = '0;
    assign perf_load_use_stalls = '0;
    assign perf_cycles = mcycle_r;
    assign perf_instret = minstret_r;
endmodule

`default_nettype wire
