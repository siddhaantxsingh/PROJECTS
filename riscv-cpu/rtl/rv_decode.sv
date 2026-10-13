// RV32IM + Zicsr + Zifencei instruction decoder (purely combinational).
//
// Every encoding that is not a legal RV32IM/Zicsr/Zifencei/M-mode-system
// instruction sets `illegal`. CSR-address legality is checked later, in the
// CSR file, because it depends on whether the instruction writes.
`default_nettype none

module rv_decode (
    input  wire [31:0] insn,
    output logic       illegal,
    output logic [4:0] rs1,
    output logic [4:0] rs2,
    output logic [4:0] rd,
    output logic       uses_rs1,
    output logic       uses_rs2,
    output logic       rd_we,
    output logic [31:0] imm,
    output logic [3:0] alu_op,      // see rv_alu
    output logic       src_a_pc,    // operand A = pc (AUIPC)
    output logic       src_a_zero,  // operand A = 0 (LUI)
    output logic       src_b_imm,   // operand B = imm
    output logic       is_load,
    output logic       is_store,
    output logic [1:0] mem_size,    // 0 byte, 1 half, 2 word
    output logic       mem_unsigned,
    output logic       is_branch,
    output logic [2:0] br_funct3,
    output logic       is_jal,
    output logic       is_jalr,
    output logic       is_muldiv,
    output logic [2:0] md_op,       // funct3 of the M instruction
    output logic       is_csr,
    output logic [1:0] csr_op,      // 1 write, 2 set, 3 clear
    output logic       csr_imm,     // rs1 field is a 5-bit immediate
    output logic       is_ecall,
    output logic       is_ebreak,
    output logic       is_mret,
    output logic       is_wfi,
    output logic       is_fencei,
    output logic       is_system    // must execute with an empty pipeline behind it
);
    localparam logic [6:0] OP_LUI = 7'b0110111, OP_AUIPC = 7'b0010111, OP_JAL = 7'b1101111,
        OP_JALR = 7'b1100111, OP_BRANCH = 7'b1100011, OP_LOAD = 7'b0000011, OP_STORE = 7'b0100011,
        OP_IMM = 7'b0010011, OP_OP = 7'b0110011, OP_FENCE = 7'b0001111, OP_SYSTEM = 7'b1110011;

    // ALU operations
    localparam logic [3:0] A_ADD = 4'd0, A_SUB = 4'd1, A_SLL = 4'd2, A_SLT = 4'd3, A_SLTU = 4'd4,
        A_XOR = 4'd5, A_SRL = 4'd6, A_SRA = 4'd7, A_OR = 4'd8, A_AND = 4'd9;

    wire [6:0] opcode = insn[6:0];
    wire [2:0] f3 = insn[14:12];
    wire [6:0] f7 = insn[31:25];

    wire [31:0] imm_i = {{20{insn[31]}}, insn[31:20]};
    wire [31:0] imm_s = {{20{insn[31]}}, insn[31:25], insn[11:7]};
    wire [31:0] imm_b = {{19{insn[31]}}, insn[31], insn[7], insn[30:25], insn[11:8], 1'b0};
    wire [31:0] imm_u = {insn[31:12], 12'b0};
    wire [31:0] imm_j = {{11{insn[31]}}, insn[31], insn[19:12], insn[20], insn[30:21], 1'b0};

    always_comb begin
        illegal = 1'b0;
        rs1 = insn[19:15];
        rs2 = insn[24:20];
        rd = insn[11:7];
        uses_rs1 = 1'b0;
        uses_rs2 = 1'b0;
        rd_we = 1'b0;
        imm = imm_i;
        alu_op = A_ADD;
        src_a_pc = 1'b0;
        src_a_zero = 1'b0;
        src_b_imm = 1'b1;
        is_load = 1'b0;
        is_store = 1'b0;
        mem_size = f3[1:0];
        mem_unsigned = f3[2];
        is_branch = 1'b0;
        br_funct3 = f3;
        is_jal = 1'b0;
        is_jalr = 1'b0;
        is_muldiv = 1'b0;
        md_op = f3;
        is_csr = 1'b0;
        csr_op = f3[1:0];
        csr_imm = f3[2];
        is_ecall = 1'b0;
        is_ebreak = 1'b0;
        is_mret = 1'b0;
        is_wfi = 1'b0;
        is_fencei = 1'b0;

        if (insn[1:0] != 2'b11) begin
            illegal = 1'b1;  // compressed / reserved: no C extension
        end else begin
            unique case (opcode)
                OP_LUI: begin
                    rd_we = 1'b1; imm = imm_u; src_a_zero = 1'b1;
                end
                OP_AUIPC: begin
                    rd_we = 1'b1; imm = imm_u; src_a_pc = 1'b1;
                end
                OP_JAL: begin
                    rd_we = 1'b1; imm = imm_j; is_jal = 1'b1;
                end
                OP_JALR: begin
                    if (f3 != 3'b000) illegal = 1'b1;
                    rd_we = 1'b1; uses_rs1 = 1'b1; imm = imm_i; is_jalr = 1'b1;
                end
                OP_BRANCH: begin
                    if (f3 == 3'b010 || f3 == 3'b011) illegal = 1'b1;
                    uses_rs1 = 1'b1; uses_rs2 = 1'b1; imm = imm_b; is_branch = 1'b1;
                end
                OP_LOAD: begin
                    // lb lh lw lbu lhu
                    if (f3 == 3'b011 || f3 == 3'b110 || f3 == 3'b111) illegal = 1'b1;
                    rd_we = 1'b1; uses_rs1 = 1'b1; is_load = 1'b1;
                end
                OP_STORE: begin
                    if (f3 > 3'b010) illegal = 1'b1;
                    uses_rs1 = 1'b1; uses_rs2 = 1'b1; imm = imm_s; is_store = 1'b1;
                end
                OP_IMM: begin
                    rd_we = 1'b1; uses_rs1 = 1'b1;
                    unique case (f3)
                        3'b000: alu_op = A_ADD;
                        3'b010: alu_op = A_SLT;
                        3'b011: alu_op = A_SLTU;
                        3'b100: alu_op = A_XOR;
                        3'b110: alu_op = A_OR;
                        3'b111: alu_op = A_AND;
                        3'b001: begin
                            alu_op = A_SLL;
                            if (f7 != 7'b0000000) illegal = 1'b1;
                        end
                        default: begin  // 3'b101
                            if (f7 == 7'b0000000) alu_op = A_SRL;
                            else if (f7 == 7'b0100000) alu_op = A_SRA;
                            else illegal = 1'b1;
                        end
                    endcase
                end
                OP_OP: begin
                    rd_we = 1'b1; uses_rs1 = 1'b1; uses_rs2 = 1'b1; src_b_imm = 1'b0;
                    if (f7 == 7'b0000001) begin
                        is_muldiv = 1'b1;
                    end else if (f7 == 7'b0000000) begin
                        unique case (f3)
                            3'b000: alu_op = A_ADD;
                            3'b001: alu_op = A_SLL;
                            3'b010: alu_op = A_SLT;
                            3'b011: alu_op = A_SLTU;
                            3'b100: alu_op = A_XOR;
                            3'b101: alu_op = A_SRL;
                            3'b110: alu_op = A_OR;
                            default: alu_op = A_AND;
                        endcase
                    end else if (f7 == 7'b0100000 && (f3 == 3'b000 || f3 == 3'b101)) begin
                        alu_op = (f3 == 3'b000) ? A_SUB : A_SRA;
                    end else begin
                        illegal = 1'b1;
                    end
                end
                OP_FENCE: begin
                    // FENCE (any pred/succ/fm, as the spec requires implementations
                    // to treat unknown fm values as plain fences) and FENCE.I.
                    if (f3 == 3'b001) is_fencei = 1'b1;
                    else if (f3 != 3'b000) illegal = 1'b1;
                end
                OP_SYSTEM: begin
                    if (f3 == 3'b000) begin
                        if (insn == 32'h0000_0073) is_ecall = 1'b1;
                        else if (insn == 32'h0010_0073) is_ebreak = 1'b1;
                        else if (insn == 32'h3020_0073) is_mret = 1'b1;
                        else if (insn == 32'h1050_0073) is_wfi = 1'b1;
                        else illegal = 1'b1;
                    end else if (f3 == 3'b100) begin
                        illegal = 1'b1;
                    end else begin
                        is_csr = 1'b1; rd_we = 1'b1; uses_rs1 = !f3[2];
                    end
                end
                default: illegal = 1'b1;
            endcase
        end
        if (illegal) begin
            rd_we = 1'b0; uses_rs1 = 1'b0; uses_rs2 = 1'b0;
            is_load = 1'b0; is_store = 1'b0; is_branch = 1'b0; is_jal = 1'b0; is_jalr = 1'b0;
            is_muldiv = 1'b0; is_csr = 1'b0; is_ecall = 1'b0; is_ebreak = 1'b0; is_mret = 1'b0;
            is_wfi = 1'b0; is_fencei = 1'b0;
        end
    end

    assign is_system = is_csr | is_ecall | is_ebreak | is_mret | is_wfi | is_fencei | illegal;
endmodule

`default_nettype wire
