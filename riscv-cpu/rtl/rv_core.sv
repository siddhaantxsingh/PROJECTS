// rv_core: a 5-stage in-order RV32IM_Zicsr_Zifencei core (machine mode only).
//
//   IF  - fetch at `pc`; next pc from the branch predictor (BTB + 2-bit BHT)
//   ID  - decode, register read (write-through from WB), hazard detection
//   EX  - ALU / multiply / iterative divide, branch resolution, CSR access,
//         exception and interrupt commit point
//   MEM - data memory access
//   WB  - register write-back and retirement (trace port)
//
// Hazards: full forwarding from MEM and WB; one bubble on a load-use
// dependency; mispredicted control flow costs two cycles (resolved in EX).
// System instructions (CSR*, ECALL, EBREAK, MRET, WFI, FENCE.I, and illegal
// instructions) wait in ID until EX, MEM and WB are empty, so they execute
// with no older instruction in flight: CSR reads of minstret are exact and
// traps are precise without any rollback.
//
// Memory interface: one combinational read port for instructions and one
// combinational read / synchronous write port for data, over the same
// address space (stores are visible to later fetches after FENCE.I).
//
// Trace: one record per instruction that leaves WB, in program order,
// including instructions that trapped (rvfi_trap) and the instruction that
// was interrupted (rvfi_intr). Modelled on RVFI.
`default_nettype none

module rv_core #(
    parameter logic [31:0] RESET_PC = 32'h8000_0000,
    parameter int          BTB_BITS = 6,     // 2**BTB_BITS predictor entries
    parameter bit          BPRED_EN = 1'b1   // 0: always predict not-taken
) (
    input  wire         clk,
    input  wire         rst_n,

    output logic [31:0] imem_addr,
    input  wire  [31:0] imem_rdata,

    output logic        dmem_re,
    output logic        dmem_we,
    output logic [31:0] dmem_addr,   // byte address (word-aligned for words)
    output logic [3:0]  dmem_wstrb,
    output logic [31:0] dmem_wdata,  // already shifted into byte lanes
    input  wire  [31:0] dmem_rdata,  // the aligned word containing dmem_addr

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
    output logic [31:0] perf_div_stalls
);
    localparam int BTB_N = 1 << BTB_BITS;

    // ------------------------------------------------------------------ CSRs
    localparam logic [11:0] CSR_MSTATUS = 12'h300, CSR_MISA = 12'h301, CSR_MIE = 12'h304,
        CSR_MTVEC = 12'h305, CSR_MCOUNTEREN = 12'h306, CSR_MSTATUSH = 12'h310,
        CSR_MCOUNTINHIBIT = 12'h320, CSR_MSCRATCH = 12'h340, CSR_MEPC = 12'h341,
        CSR_MCAUSE = 12'h342, CSR_MTVAL = 12'h343, CSR_MIP = 12'h344,
        CSR_MCYCLE = 12'hB00, CSR_MINSTRET = 12'hB02, CSR_MCYCLEH = 12'hB80, CSR_MINSTRETH = 12'hB82,
        CSR_CYCLE = 12'hC00, CSR_INSTRET = 12'hC02, CSR_CYCLEH = 12'hC80, CSR_INSTRETH = 12'hC82,
        CSR_MVENDORID = 12'hF11, CSR_MARCHID = 12'hF12, CSR_MIMPID = 12'hF13, CSR_MHARTID = 12'hF14,
        CSR_MCONFIGPTR = 12'hF15;
    localparam logic [31:0] MISA_VAL = 32'h4000_1100;  // MXL=1 (32-bit), I, M

    logic        mstatus_mie, mstatus_mpie;
    logic [31:0] mie_r, mtvec_r, mscratch_r, mepc_r, mcause_r, mtval_r;
    logic [63:0] mcycle_r, minstret_r;
    wire  [31:0] mip_w = {20'b0, irq_external, 3'b0, irq_timer, 3'b0, irq_software, 3'b0};
    wire  [31:0] mstatus_w = {19'b0, 2'b11, 3'b0, mstatus_mpie, 3'b0, mstatus_mie, 3'b0};

    // ----------------------------------------------------- pipeline registers
    // IF/ID
    logic        d_valid;
    logic [31:0] d_pc, d_insn, d_pred_next;

    // ID/EX
    logic        e_valid;
    logic [31:0] e_pc, e_insn, e_pred_next, e_imm, e_rs1v, e_rs2v;
    logic [4:0]  e_rs1, e_rs2, e_rd;
    logic        e_rd_we, e_illegal, e_src_a_pc, e_src_a_zero, e_src_b_imm;
    logic [3:0]  e_alu_op;
    logic        e_is_load, e_is_store, e_mem_unsigned, e_is_branch, e_is_jal, e_is_jalr;
    logic [1:0]  e_mem_size;
    logic [2:0]  e_br_funct3, e_md_op;
    logic        e_is_muldiv, e_is_csr, e_csr_imm, e_is_ecall, e_is_ebreak, e_is_mret, e_is_wfi, e_is_fencei;
    logic [1:0]  e_csr_op;

    // EX/MEM
    logic        m_valid, m_trap, m_intr, m_rd_we, m_is_load, m_is_store, m_mem_unsigned, m_no_count;
    logic [31:0] m_pc, m_insn, m_result, m_addr, m_wdata, m_next_pc, m_cause;
    logic [4:0]  m_rd;
    logic [1:0]  m_mem_size;
    logic [3:0]  m_wstrb;

    // MEM/WB
    logic        w_valid, w_trap, w_intr, w_rd_we, w_no_count;
    logic [31:0] w_pc, w_insn, w_result, w_next_pc, w_cause, w_mem_addr, w_mem_wdata, w_mem_rdata;
    logic [4:0]  w_rd;
    logic [3:0]  w_rmask, w_wmask;

    // ------------------------------------------------------- register file
    logic [31:0] regs [1:31];
    wire         wb_write = w_valid && !w_trap && w_rd_we && (w_rd != 5'd0);

    // ------------------------------------------------------- branch predictor
    logic                  btb_valid  [BTB_N];
    logic [31-2-BTB_BITS:0] btb_tag   [BTB_N];
    logic [31:0]           btb_target [BTB_N];
    logic                  btb_jump   [BTB_N];  // unconditional (jal/jalr)
    logic [1:0]            bht        [BTB_N];

    logic [31:0] pc;
    wire [BTB_BITS-1:0] f_idx = pc[2 +: BTB_BITS];
    wire f_hit = BPRED_EN && btb_valid[f_idx] && btb_tag[f_idx] == pc[31:2+BTB_BITS];
    wire f_pred_taken = f_hit && (btb_jump[f_idx] || bht[f_idx][1]);
    wire [31:0] f_pred_next = f_pred_taken ? btb_target[f_idx] : pc + 32'd4;

    assign imem_addr = pc;

    // ------------------------------------------------------------- ID stage
    logic        id_illegal, id_uses_rs1, id_uses_rs2, id_rd_we, id_src_a_pc, id_src_a_zero, id_src_b_imm;
    logic [4:0]  id_rs1, id_rs2, id_rd;
    logic [31:0] id_imm;
    logic [3:0]  id_alu_op;
    logic        id_is_load, id_is_store, id_mem_unsigned, id_is_branch, id_is_jal, id_is_jalr;
    logic [1:0]  id_mem_size, id_csr_op;
    logic [2:0]  id_br_funct3, id_md_op;
    logic        id_is_muldiv, id_is_csr, id_csr_imm, id_is_ecall, id_is_ebreak, id_is_mret, id_is_wfi;
    logic        id_is_fencei, id_is_system;

    rv_decode u_dec (
        .insn(d_insn), .illegal(id_illegal), .rs1(id_rs1), .rs2(id_rs2), .rd(id_rd),
        .uses_rs1(id_uses_rs1), .uses_rs2(id_uses_rs2), .rd_we(id_rd_we), .imm(id_imm),
        .alu_op(id_alu_op), .src_a_pc(id_src_a_pc), .src_a_zero(id_src_a_zero), .src_b_imm(id_src_b_imm),
        .is_load(id_is_load), .is_store(id_is_store), .mem_size(id_mem_size), .mem_unsigned(id_mem_unsigned),
        .is_branch(id_is_branch), .br_funct3(id_br_funct3), .is_jal(id_is_jal), .is_jalr(id_is_jalr),
        .is_muldiv(id_is_muldiv), .md_op(id_md_op), .is_csr(id_is_csr), .csr_op(id_csr_op),
        .csr_imm(id_csr_imm), .is_ecall(id_is_ecall), .is_ebreak(id_is_ebreak), .is_mret(id_is_mret),
        .is_wfi(id_is_wfi), .is_fencei(id_is_fencei), .is_system(id_is_system)
    );

    wire [31:0] id_rs1v = (id_rs1 == 5'd0) ? 32'd0 : (wb_write && w_rd == id_rs1) ? w_result : regs[id_rs1];
    wire [31:0] id_rs2v = (id_rs2 == 5'd0) ? 32'd0 : (wb_write && w_rd == id_rs2) ? w_result : regs[id_rs2];

    wire load_use = d_valid && e_valid && e_is_load && e_rd != 5'd0 &&
                    ((id_uses_rs1 && id_rs1 == e_rd) || (id_uses_rs2 && id_rs2 == e_rd));
    wire sys_drain = d_valid && id_is_system && (e_valid || m_valid || w_valid);

    // ------------------------------------------------------------- EX stage
    wire m_fwd_ok = m_valid && !m_trap && m_rd_we && !m_is_load && m_rd != 5'd0;
    wire w_fwd_ok = wb_write;
    wire [31:0] ex_rs1v = (m_fwd_ok && m_rd == e_rs1) ? m_result : (w_fwd_ok && w_rd == e_rs1) ? w_result : e_rs1v;
    wire [31:0] ex_rs2v = (m_fwd_ok && m_rd == e_rs2) ? m_result : (w_fwd_ok && w_rd == e_rs2) ? w_result : e_rs2v;

    wire [31:0] alu_a = e_src_a_zero ? 32'd0 : e_src_a_pc ? e_pc : ex_rs1v;
    wire [31:0] alu_b = e_src_b_imm ? e_imm : ex_rs2v;
    logic [31:0] alu_y, mul_y, div_y;
    logic br_taken, div_busy, div_done;
    rv_alu    u_alu (.op(e_alu_op), .a(alu_a), .b(alu_b), .y(alu_y));
    rv_branch u_br  (.funct3(e_br_funct3), .a(ex_rs1v), .b(ex_rs2v), .taken(br_taken));
    rv_mul    u_mul (.op(e_md_op[1:0]), .a(ex_rs1v), .b(ex_rs2v), .y(mul_y));

    wire ex_is_div = e_valid && e_is_muldiv && e_md_op[2];
    logic div_started;
    logic ex_redirect;  // declared early: kills the divider
    rv_div u_div (
        .clk(clk), .rst_n(rst_n), .start(ex_is_div && !div_started && !div_busy), .kill(ex_redirect && !ex_is_div),
        .op(e_md_op[1:0]), .a(ex_rs1v), .b(ex_rs2v), .busy(div_busy), .done(div_done), .y(div_y)
    );
    wire ex_stall = ex_is_div && !(div_started && div_done);

    // control flow
    wire [31:0] pc_plus4 = e_pc + 32'd4;
    wire [31:0] br_target = e_pc + e_imm;
    wire [31:0] jalr_target = (ex_rs1v + e_imm) & ~32'd1;
    wire ctl_taken = e_is_jal || e_is_jalr || (e_is_branch && br_taken);
    wire [31:0] ctl_target = e_is_jalr ? jalr_target : br_target;
    wire [31:0] actual_next = ctl_taken ? ctl_target : pc_plus4;
    wire misaligned_target = ctl_taken && ctl_target[1];

    // memory address
    wire [31:0] mem_addr = ex_rs1v + e_imm;
    wire mem_misaligned = (e_mem_size == 2'd1 && mem_addr[0]) || (e_mem_size == 2'd2 && mem_addr[1:0] != 2'b00);

    // CSR read / legality / write data
    wire [11:0] csr_addr = e_insn[31:20];
    wire [31:0] csr_src = e_csr_imm ? {27'b0, e_rs1} : ex_rs1v;
    wire csr_writes = (e_csr_op == 2'd1) || (e_rs1 != 5'd0);  // csrrs/c with x0 / uimm=0 only read
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
    wire csr_illegal = e_is_csr && (!csr_exists || (csr_writes && csr_readonly));
    logic [31:0] csr_wdata;
    always_comb begin
        unique case (e_csr_op)
            2'd1: csr_wdata = csr_src;
            2'd2: csr_wdata = csr_rdata | csr_src;
            default: csr_wdata = csr_rdata & ~csr_src;
        endcase
    end

    // exceptions and interrupts (EX is the commit point)
    wire [31:0] irq_pending = mip_w & mie_r;
    wire take_irq = e_valid && !ex_stall && mstatus_mie && (irq_pending[11] || irq_pending[3] || irq_pending[7]);
    wire [31:0] irq_cause = irq_pending[11] ? 32'h8000_000B : irq_pending[3] ? 32'h8000_0003 : 32'h8000_0007;

    logic        exc;
    logic [31:0] exc_cause, exc_tval;
    always_comb begin
        exc = 1'b1; exc_cause = 32'd0; exc_tval = 32'd0;
        if (e_illegal || csr_illegal) begin exc_cause = 32'd2; exc_tval = e_insn; end
        else if (e_is_ecall)          begin exc_cause = 32'd11; end
        else if (e_is_ebreak)         begin exc_cause = 32'd3; exc_tval = e_pc; end
        else if (misaligned_target)   begin exc_cause = 32'd0; exc_tval = ctl_target; end
        else if (e_is_load && mem_misaligned)  begin exc_cause = 32'd4; exc_tval = mem_addr; end
        else if (e_is_store && mem_misaligned) begin exc_cause = 32'd6; exc_tval = mem_addr; end
        else exc = 1'b0;
    end
    wire ex_trap = e_valid && !ex_stall && (take_irq || exc);
    wire [31:0] trap_cause = take_irq ? irq_cause : exc_cause;
    wire [31:0] trap_vector = (mtvec_r[0] && take_irq) ? {mtvec_r[31:2], 2'b00} + {trap_cause[29:0], 2'b00}
                                                      : {mtvec_r[31:2], 2'b00};

    wire ex_mret = e_valid && !ex_trap && e_is_mret;
    wire ex_fencei = e_valid && !ex_trap && e_is_fencei;
    wire mispredict = e_valid && !ex_trap && !ex_stall && (actual_next != e_pred_next);
    assign ex_redirect = ex_trap || ex_mret || ex_fencei || mispredict;
    wire [31:0] redirect_pc = ex_trap ? trap_vector : ex_mret ? mepc_r : ex_fencei ? pc_plus4 : actual_next;

    logic [31:0] ex_result;
    always_comb begin
        if (e_is_jal || e_is_jalr) ex_result = pc_plus4;
        else if (e_is_csr) ex_result = csr_rdata;
        else if (e_is_muldiv) ex_result = e_md_op[2] ? div_y : mul_y;
        else ex_result = alu_y;
    end

    // store data / strobes
    logic [3:0]  st_strb;
    logic [31:0] st_data;
    always_comb begin
        unique case (e_mem_size)
            2'd0: begin st_strb = 4'b0001 << mem_addr[1:0]; st_data = {4{ex_rs2v[7:0]}}; end
            2'd1: begin st_strb = 4'b0011 << {mem_addr[1], 1'b0}; st_data = {2{ex_rs2v[15:0]}}; end
            default: begin st_strb = 4'b1111; st_data = ex_rs2v; end
        endcase
    end

    // ------------------------------------------------------------ MEM stage
    assign dmem_re    = m_valid && !m_trap && m_is_load;
    assign dmem_we    = m_valid && !m_trap && m_is_store;
    assign dmem_addr  = m_addr;
    assign dmem_wstrb = m_is_store ? m_wstrb : 4'b0;
    assign dmem_wdata = m_wdata;

    logic [31:0] ld_val;
    logic [3:0]  ld_mask;
    always_comb begin
        logic [31:0] sh;
        sh = dmem_rdata >> {m_addr[1:0], 3'b000};
        unique case (m_mem_size)
            2'd0: begin ld_val = m_mem_unsigned ? {24'b0, sh[7:0]} : {{24{sh[7]}}, sh[7:0]}; ld_mask = 4'b0001 << m_addr[1:0]; end
            2'd1: begin ld_val = m_mem_unsigned ? {16'b0, sh[15:0]} : {{16{sh[15]}}, sh[15:0]}; ld_mask = 4'b0011 << {m_addr[1], 1'b0}; end
            default: begin ld_val = dmem_rdata; ld_mask = 4'b1111; end
        endcase
    end

    // ---------------------------------------------------------- pipeline control
    wire id_stall = load_use || sys_drain;

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            pc <= RESET_PC;
            d_valid <= 1'b0; e_valid <= 1'b0; m_valid <= 1'b0; w_valid <= 1'b0;
            div_started <= 1'b0;
            d_pc <= '0; d_insn <= '0; d_pred_next <= '0;
        end else begin
            // ---- IF -> ID
            if (ex_redirect) begin
                pc <= redirect_pc;
                d_valid <= 1'b0;
            end else if (!(ex_stall || id_stall)) begin
                pc <= f_pred_next;
                d_valid <= 1'b1;
                d_pc <= pc;
                d_insn <= imem_rdata;
                d_pred_next <= f_pred_next;
            end

            // ---- ID -> EX
            if (ex_redirect) begin
                e_valid <= 1'b0;
            end else if (ex_stall) begin
                // hold
            end else if (id_stall || !d_valid) begin
                e_valid <= 1'b0;
            end else begin
                e_valid <= 1'b1;
            end

            // ---- divider handshake
            if (ex_stall) begin
                if (ex_is_div && !div_started && !div_busy) div_started <= 1'b1;
            end else begin
                div_started <= 1'b0;
            end

            // ---- EX -> MEM
            if (e_valid && !ex_stall) begin
                m_valid <= 1'b1;
            end else begin
                m_valid <= 1'b0;
            end

            // ---- MEM -> WB
            w_valid <= m_valid;
        end
    end

    // datapath registers (no reset needed; qualified by the valid bits)
    always_ff @(posedge clk) begin
        if (!ex_redirect && !ex_stall && !id_stall && d_valid) begin
            e_pc <= d_pc; e_insn <= d_insn; e_pred_next <= d_pred_next; e_imm <= id_imm;
            e_rs1v <= id_rs1v; e_rs2v <= id_rs2v; e_rs1 <= id_rs1; e_rs2 <= id_rs2; e_rd <= id_rd;
            e_rd_we <= id_rd_we; e_illegal <= id_illegal; e_src_a_pc <= id_src_a_pc;
            e_src_a_zero <= id_src_a_zero; e_src_b_imm <= id_src_b_imm; e_alu_op <= id_alu_op;
            e_is_load <= id_is_load; e_is_store <= id_is_store; e_mem_unsigned <= id_mem_unsigned;
            e_mem_size <= id_mem_size; e_is_branch <= id_is_branch; e_br_funct3 <= id_br_funct3;
            e_is_jal <= id_is_jal; e_is_jalr <= id_is_jalr; e_is_muldiv <= id_is_muldiv; e_md_op <= id_md_op;
            e_is_csr <= id_is_csr; e_csr_op <= id_csr_op; e_csr_imm <= id_csr_imm; e_is_ecall <= id_is_ecall;
            e_is_ebreak <= id_is_ebreak; e_is_mret <= id_is_mret; e_is_wfi <= id_is_wfi; e_is_fencei <= id_is_fencei;
        end else if (ex_stall) begin
            // While the divider runs, keep the EX operands current with the
            // forwarding network: the producers in MEM/WB drain meanwhile.
            e_rs1v <= ex_rs1v; e_rs2v <= ex_rs2v;
        end

        if (e_valid && !ex_stall) begin
            m_pc <= e_pc; m_insn <= e_insn; m_trap <= ex_trap; m_intr <= take_irq; m_cause <= trap_cause;
            m_rd <= e_rd; m_rd_we <= e_rd_we && !ex_trap; m_result <= ex_result;
            m_is_load <= e_is_load && !ex_trap; m_is_store <= e_is_store && !ex_trap;
            m_mem_size <= e_mem_size; m_mem_unsigned <= e_mem_unsigned;
            m_addr <= mem_addr; m_wdata <= st_data; m_wstrb <= st_strb;
            m_next_pc <= ex_trap ? trap_vector : ex_mret ? mepc_r : actual_next;
            m_no_count <= e_is_csr && csr_writes && (csr_addr == CSR_MINSTRET || csr_addr == CSR_MINSTRETH);
        end

        if (m_valid) begin
            w_pc <= m_pc; w_insn <= m_insn; w_trap <= m_trap; w_intr <= m_intr; w_cause <= m_cause;
            w_rd <= m_rd; w_rd_we <= m_rd_we; w_result <= m_is_load ? ld_val : m_result;
            w_next_pc <= m_next_pc; w_no_count <= m_no_count;
            w_mem_addr <= (m_is_load || m_is_store) ? m_addr : 32'd0;
            w_rmask <= m_is_load ? ld_mask : 4'b0;
            w_wmask <= m_is_store ? m_wstrb : 4'b0;
            w_mem_wdata <= m_is_store ? m_wdata : 32'd0;
            w_mem_rdata <= m_is_load ? dmem_rdata : 32'd0;
        end

        if (wb_write) regs[w_rd] <= w_result;
    end

    // ------------------------------------------------------------ CSR updates
    wire retire = w_valid && !w_trap;
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            mstatus_mie <= 1'b0; mstatus_mpie <= 1'b0;
            mie_r <= '0; mtvec_r <= '0; mscratch_r <= '0; mepc_r <= '0; mcause_r <= '0; mtval_r <= '0;
            mcycle_r <= '0; minstret_r <= '0;
        end else begin
            mcycle_r <= mcycle_r + 64'd1;
            if (retire && !w_no_count) minstret_r <= minstret_r + 64'd1;

            if (ex_trap) begin
                mepc_r <= e_pc;
                mcause_r <= trap_cause;
                mtval_r <= take_irq ? 32'd0 : exc_tval;
                mstatus_mpie <= mstatus_mie;
                mstatus_mie <= 1'b0;
            end else if (ex_mret) begin
                mstatus_mie <= mstatus_mpie;
                mstatus_mpie <= 1'b1;
            end else if (e_valid && e_is_csr && csr_writes) begin
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

    // ------------------------------------------------------ predictor update
    wire bp_update = e_valid && !ex_trap && !ex_stall && (e_is_branch || e_is_jal || e_is_jalr);
    wire [BTB_BITS-1:0] e_idx = e_pc[2 +: BTB_BITS];
    // valid bits and counters have a reset; tags/targets do not (so they can
    // map to distributed RAM)
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            for (int i = 0; i < BTB_N; i++) begin
                btb_valid[i] <= 1'b0;
                bht[i] <= 2'b01;
            end
        end else if (bp_update) begin
            btb_valid[e_idx] <= 1'b1;
            if (e_is_branch) begin
                if (br_taken && bht[e_idx] != 2'b11) bht[e_idx] <= bht[e_idx] + 2'b01;
                else if (!br_taken && bht[e_idx] != 2'b00) bht[e_idx] <= bht[e_idx] - 2'b01;
            end
        end else if (e_valid && !ex_trap && !ex_stall && mispredict) begin
            // a non-control instruction hit a stale BTB entry: drop it
            btb_valid[e_idx] <= 1'b0;
        end
    end
    always_ff @(posedge clk) begin
        if (bp_update) begin
            btb_tag[e_idx] <= e_pc[31:2+BTB_BITS];
            btb_jump[e_idx] <= !e_is_branch;
            if (ctl_taken) btb_target[e_idx] <= ctl_target;
        end
    end

    // ---------------------------------------------------------------- trace
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) rvfi_order <= '0;
        else if (w_valid) rvfi_order <= rvfi_order + 64'd1;
    end
    assign rvfi_valid     = w_valid;
    assign rvfi_insn      = w_insn;
    assign rvfi_pc_rdata  = w_pc;
    assign rvfi_pc_wdata  = w_next_pc;
    assign rvfi_rd_addr   = wb_write ? w_rd : 5'd0;
    assign rvfi_rd_wdata  = wb_write ? w_result : 32'd0;
    assign rvfi_trap      = w_valid && w_trap;
    assign rvfi_intr      = w_valid && w_intr;
    assign rvfi_cause     = w_cause;
    assign rvfi_mem_addr  = w_mem_addr;
    assign rvfi_mem_rmask = w_rmask;
    assign rvfi_mem_wmask = w_wmask;
    assign rvfi_mem_wdata = w_mem_wdata;
    assign rvfi_mem_rdata = w_mem_rdata;

    // ------------------------------------------------------- perf counters
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            perf_branches <= '0; perf_mispredicts <= '0; perf_load_use_stalls <= '0; perf_div_stalls <= '0;
        end else begin
            if (bp_update) perf_branches <= perf_branches + 32'd1;
            if (mispredict && (e_is_branch || e_is_jal || e_is_jalr)) perf_mispredicts <= perf_mispredicts + 32'd1;
            if (load_use && !ex_stall && !ex_redirect) perf_load_use_stalls <= perf_load_use_stalls + 32'd1;
            if (ex_stall) perf_div_stalls <= perf_div_stalls + 32'd1;
        end
    end
    assign perf_cycles = mcycle_r;
    assign perf_instret = minstret_r;
endmodule

`default_nettype wire
