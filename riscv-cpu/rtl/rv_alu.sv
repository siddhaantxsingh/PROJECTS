// Integer ALU, branch comparator, single-cycle multiplier and iterative divider.
`default_nettype none

module rv_alu (
    input  wire [3:0]  op,
    input  wire [31:0] a,
    input  wire [31:0] b,
    output logic [31:0] y
);
    always_comb begin
        unique case (op)
            4'd0: y = a + b;
            4'd1: y = a - b;
            4'd2: y = a << b[4:0];
            4'd3: y = {31'b0, $signed(a) < $signed(b)};
            4'd4: y = {31'b0, a < b};
            4'd5: y = a ^ b;
            4'd6: y = a >> b[4:0];
            4'd7: y = $unsigned($signed(a) >>> b[4:0]);
            4'd8: y = a | b;
            default: y = a & b;
        endcase
    end
endmodule

module rv_branch (
    input  wire [2:0]  funct3,
    input  wire [31:0] a,
    input  wire [31:0] b,
    output logic       taken
);
    always_comb begin
        unique case (funct3)
            3'b000: taken = a == b;
            3'b001: taken = a != b;
            3'b100: taken = $signed(a) < $signed(b);
            3'b101: taken = $signed(a) >= $signed(b);
            3'b110: taken = a < b;
            default: taken = a >= b;  // 3'b111 (010/011 are illegal, decoded away)
        endcase
    end
endmodule

// MUL, MULH, MULHSU, MULHU in one cycle (a 33x33 signed product).
module rv_mul (
    input  wire [1:0]  op,  // funct3[1:0]: 0 mul, 1 mulh, 2 mulhsu, 3 mulhu
    input  wire [31:0] a,
    input  wire [31:0] b,
    output logic [31:0] y
);
    wire a_signed = (op == 2'd1) || (op == 2'd2);
    wire b_signed = (op == 2'd1);
    wire signed [32:0] sa = {a_signed & a[31], a};
    wire signed [32:0] sb = {b_signed & b[31], b};
    wire signed [65:0] p = sa * sb;
    assign y = (op == 2'd0) ? p[31:0] : p[63:32];
endmodule

// DIV, DIVU, REM, REMU: radix-2 restoring division, one quotient bit per
// cycle (32 cycles + 1 to load). Division by zero and signed overflow follow
// the ISA (no trap) and finish in the load cycle.
module rv_div (
    input  wire        clk,
    input  wire        rst_n,
    input  wire        start,   // pulse with a new operation (only while !busy)
    input  wire        kill,    // abandon the current operation
    input  wire [1:0]  op,      // funct3[1:0]: 0 div, 1 divu, 2 rem, 3 remu
    input  wire [31:0] a,
    input  wire [31:0] b,
    output logic       busy,
    output logic       done,    // one-cycle pulse; `y` valid in the same cycle
    output logic [31:0] y
);
    logic [5:0]  count;
    logic [31:0] quo, divisor;
    logic [32:0] rem;
    logic        neg_q, neg_r, want_rem;
    logic        fin;
    logic [31:0] fin_val;

    wire is_signed = !op[0];
    wire a_neg = is_signed && a[31];
    wire b_neg = is_signed && b[31];
    wire [31:0] a_abs = a_neg ? -a : a;
    wire [31:0] b_abs = b_neg ? -b : b;

    wire [32:0] trial = {rem[31:0], quo[31]} - {1'b0, divisor};

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            busy <= 1'b0; count <= '0; fin <= 1'b0;
            quo <= '0; rem <= '0; divisor <= '0; neg_q <= 1'b0; neg_r <= 1'b0; want_rem <= 1'b0;
            fin_val <= '0;
        end else if (kill) begin
            busy <= 1'b0; fin <= 1'b0;
        end else if (start && !busy) begin
            fin <= 1'b0;
            if (b == 32'd0) begin
                fin <= 1'b1;
                fin_val <= op[1] ? a : 32'hFFFF_FFFF;
            end else if (is_signed && a == 32'h8000_0000 && b == 32'hFFFF_FFFF) begin
                fin <= 1'b1;
                fin_val <= op[1] ? 32'd0 : 32'h8000_0000;
            end else begin
                busy <= 1'b1;
                count <= 6'd32;
                quo <= a_abs;
                rem <= '0;
                divisor <= b_abs;
                neg_q <= a_neg ^ b_neg;
                neg_r <= a_neg;
                want_rem <= op[1];
            end
        end else if (busy) begin
            if (!trial[32]) begin
                rem <= trial;
                quo <= {quo[30:0], 1'b1};
            end else begin
                rem <= {rem[31:0], quo[31]};
                quo <= {quo[30:0], 1'b0};
            end
            count <= count - 6'd1;
            if (count == 6'd1) busy <= 1'b0;
        end else begin
            fin <= 1'b0;
        end
    end

    // `done` is asserted in the cycle after the last iteration (or after a
    // special-case load), with the result combinationally available.
    logic was_busy;
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) was_busy <= 1'b0;
        else if (kill) was_busy <= 1'b0;
        else was_busy <= busy;
    end
    wire iter_done = was_busy && !busy;
    wire [31:0] q_res = neg_q ? -quo : quo;
    wire [31:0] r_res = neg_r ? -rem[31:0] : rem[31:0];
    assign done = fin || iter_done;
    assign y = fin ? fin_val : (want_rem ? r_res : q_res);
endmodule

`default_nettype wire
