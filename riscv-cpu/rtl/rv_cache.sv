// Caches and memory system for rv_core.
//
//   rv_icache  read-only instruction cache, direct-mapped or 2-way (LRU)
//   rv_dcache  write-through, no-write-allocate data cache with a FIFO write
//              buffer; direct-mapped or 2-way (LRU); uncached I/O space
//              (addr[31] = 0) plus one 128-byte window for the host interface
//   rv_top     rv_core + both caches + a bus arbiter, with one external
//              memory bus (one transaction at a time, burst line reads)
//
// Why write-through with a write buffer (and not write-back):
//   * memory always holds every store once the buffer has drained, so
//     FENCE.I only has to drain the buffer and invalidate the I-cache (no
//     dirty-line write-back walk), and the host / DMA side (HTIF here) can
//     read memory directly;
//   * no dirty-eviction path, which keeps the refill state machine short;
//   * the cost is one bus write per store. The buffer hides it unless stores
//     come faster than memory accepts them (counted as wbuf_full stalls).
//
// Lookups are combinational (tag compare and data read in the same cycle as
// the core's request), matching the core's idealised memory ports. The
// arrays are plain registers; a real implementation would use SRAM macros
// with a registered read, which needs an extra pipeline stage in the core.
//
// Bus protocol (to the harness memory model):
//   bus_req_valid/ready handshake. A read (we=0) of `line`=1 returns
//   LINE_WORDS beats (from the line-aligned address upward), of `line`=0 one
//   beat, on bus_resp_valid, in order, after the memory's latency. A write
//   (we=1) is one word with byte strobes and has no response (posted). Only
//   one read is outstanding at a time.
`default_nettype none

module rv_icache #(
    parameter int SETS       = 64,   // lines per way
    parameter int LINE_WORDS = 4,
    parameter int WAYS       = 1     // 1 or 2
) (
    input  wire         clk,
    input  wire         rst_n,
    input  wire  [31:0] addr,
    output logic [31:0] rdata,
    output logic        ready,
    input  wire         flush,        // FENCE.I: invalidate everything now
    input  wire         hold_refill,  // do not start a refill (buffered stores pending)
    output logic        req_valid,
    output logic [31:0] req_addr,
    input  wire         req_grant,
    input  wire         resp_valid,
    input  wire  [31:0] resp_data,
    output logic [31:0] perf_refills
);
    localparam int OFF_BITS = $clog2(LINE_WORDS) + 2;
    localparam int IDX_BITS = $clog2(SETS);
    localparam int TAG_BITS = 32 - OFF_BITS - IDX_BITS;
    localparam int WB = $clog2(LINE_WORDS) > 0 ? $clog2(LINE_WORDS) : 1;

    logic [WAYS*SETS-1:0] valid;        // bit w*SETS+set; one vector so reset/flush clear it at once
    logic [TAG_BITS-1:0] tags  [WAYS][SETS];
    logic [31:0]         data  [WAYS][SETS*LINE_WORDS];
    logic [SETS-1:0]     lru;            // way to replace next (2-way only)

    wire [IDX_BITS-1:0] idx = addr[OFF_BITS +: IDX_BITS];
    wire [TAG_BITS-1:0] tag = addr[31 -: TAG_BITS];
    wire [IDX_BITS+WB-1:0] widx = {idx, addr[2 +: WB]};

    logic hit, hit_way;
    always_comb begin
        hit = 1'b0; hit_way = 1'b0; rdata = data[0][widx];
        for (int w = 0; w < WAYS; w++)
            if (valid[w*SETS + 32'(idx)] && tags[w][idx] == tag) begin
                hit = 1'b1; hit_way = w[0]; rdata = data[w][widx];
            end
    end
    assign ready = hit;

    // refill state
    typedef enum logic [1:0] {IDLE, REQ, FILL} state_t;
    state_t state;
    logic [31:0] fill_addr;
    logic [IDX_BITS-1:0] fill_idx;
    logic fill_way, cancel;
    logic [WB-1:0] beat;

    assign req_valid = state == REQ;
    assign req_addr  = fill_addr;

    wire victim = (WAYS == 1) ? 1'b0 : (!valid[32'(idx)] ? 1'b0 : !valid[(WAYS-1)*SETS + 32'(idx)] ? 1'b1 : lru[idx]);

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state <= IDLE; cancel <= 1'b0; beat <= '0; perf_refills <= '0;
            fill_addr <= '0; fill_idx <= '0; fill_way <= 1'b0;
            valid <= '0;
            lru <= '0;
        end else begin
            if (hit && WAYS > 1) lru[idx] <= !hit_way;
            unique case (state)
                IDLE: if (!hit && !flush && !hold_refill) begin
                    state <= REQ;
                    fill_addr <= {addr[31:OFF_BITS], {OFF_BITS{1'b0}}};
                    fill_idx <= idx; fill_way <= victim;
                    valid[victim*SETS + 32'(idx)] <= 1'b0;  // its data is overwritten beat by beat
                    cancel <= 1'b0;
                    perf_refills <= perf_refills + 32'd1;
                end
                REQ: if (req_grant) begin state <= FILL; beat <= '0; end
                FILL: if (resp_valid) begin
                    beat <= beat + 1'b1;
                    if (beat == WB'(LINE_WORDS - 1)) begin
                        state <= IDLE;
                        if (!cancel && !flush) begin
                            valid[fill_way*SETS + 32'(fill_idx)] <= 1'b1;
                            if (WAYS > 1) lru[fill_idx] <= !fill_way;
                        end
                    end
                end
                default: state <= IDLE;
            endcase
            // FENCE.I: invalidate all lines; a refill in flight may hold
            // pre-FENCE.I data, so it completes without being marked valid.
            if (flush) begin
                valid <= '0;
                if (state != IDLE) cancel <= 1'b1;
            end
        end
    end

    always_ff @(posedge clk) begin
        if (state == IDLE && !hit && !flush && !hold_refill) tags[victim][idx] <= tag;
        if (state == FILL && resp_valid) data[fill_way][{fill_idx, beat}] <= resp_data;
    end
endmodule

module rv_dcache #(
    parameter int SETS       = 64,
    parameter int LINE_WORDS = 4,
    parameter int WAYS       = 1,
    parameter int WB_DEPTH   = 4     // write-buffer entries (power of two, >= 2)
) (
    input  wire         clk,
    input  wire         rst_n,
    input  wire         re,
    input  wire         we,
    input  wire  [31:0] addr,
    input  wire  [3:0]  wstrb,
    input  wire  [31:0] wdata,
    output logic [31:0] rdata,
    output logic        ready,
    input  wire  [25:0] io_base,     // addr[31:6] of an extra uncached 128-byte window (HTIF tohost/fromhost)
    input  wire         snoop_inv,   // another agent wrote memory: drop this line
    input  wire  [31:0] snoop_addr,
    // reads (line refill or one uncached word)
    output logic        rd_req,
    output logic        rd_line,
    output logic [31:0] rd_addr,
    input  wire         rd_grant,
    input  wire         resp_valid,
    input  wire  [31:0] resp_data,
    // write buffer head
    output logic        wb_req,
    output logic [31:0] wb_addr,
    output logic [31:0] wb_data,
    output logic [3:0]  wb_strb,
    input  wire         wb_grant,
    output logic        wb_empty,
    output logic        wb_full,
    output logic        drain_req,   // a read is waiting for the buffer to empty
    output logic [31:0] perf_refills,
    output logic [31:0] perf_uncached,
    output logic [31:0] perf_wbuf_full
);
    localparam int OFF_BITS = $clog2(LINE_WORDS) + 2;
    localparam int IDX_BITS = $clog2(SETS);
    localparam int TAG_BITS = 32 - OFF_BITS - IDX_BITS;
    localparam int WB = $clog2(LINE_WORDS) > 0 ? $clog2(LINE_WORDS) : 1;
    localparam int QB = $clog2(WB_DEPTH);

    logic [WAYS*SETS-1:0] valid;        // bit w*SETS+set; one vector so reset/flush clear it at once
    logic [TAG_BITS-1:0] tags  [WAYS][SETS];
    logic [31:0]         data  [WAYS][SETS*LINE_WORDS];
    logic [SETS-1:0]     lru;

    wire [IDX_BITS-1:0] idx = addr[OFF_BITS +: IDX_BITS];
    wire [TAG_BITS-1:0] tag = addr[31 -: TAG_BITS];
    wire [IDX_BITS+WB-1:0] widx = {idx, addr[2 +: WB]};
    wire cacheable = addr[31] && addr[31:6] != io_base && addr[31:6] != io_base + 26'd1;

    logic hit, hit_way;
    logic [31:0] hit_data;
    always_comb begin
        hit = 1'b0; hit_way = 1'b0; hit_data = data[0][widx];
        for (int w = 0; w < WAYS; w++)
            if (valid[w*SETS + 32'(idx)] && tags[w][idx] == tag) begin
                hit = 1'b1; hit_way = w[0]; hit_data = data[w][widx];
            end
    end

    // ---------------------------------------------------------- write buffer
    logic [31:0] q_addr [WB_DEPTH];
    logic [31:0] q_data [WB_DEPTH];
    logic [3:0]  q_strb [WB_DEPTH];
    logic [QB-1:0] q_head, q_tail;
    logic [QB:0]   q_count;
    assign wb_empty = q_count == 0;
    assign wb_full  = q_count == (QB+1)'(WB_DEPTH);
    assign wb_req   = !wb_empty;
    assign wb_addr  = q_addr[q_head];
    assign wb_data  = q_data[q_head];
    assign wb_strb  = q_strb[q_head];

    // ------------------------------------------------------- read state machine
    typedef enum logic [1:0] {IDLE, REQ, FILL, UCDONE} state_t;
    state_t state;
    logic [31:0] fill_addr, uc_data;
    logic [IDX_BITS-1:0] fill_idx;
    logic fill_way, fill_line, cancel;
    logic [WB-1:0] beat;

    wire victim = (WAYS == 1) ? 1'b0 : (!valid[32'(idx)] ? 1'b0 : !valid[(WAYS-1)*SETS + 32'(idx)] ? 1'b1 : lru[idx]);
    wire load_hit  = re && cacheable && hit;
    wire load_miss = re && !(cacheable && hit) && state == IDLE;  // needs a bus read
    wire store_ok  = we && !wb_full;
    // A read may only go to the bus once older buffered stores are in memory.
    wire start_rd  = load_miss && wb_empty && !snoop_inv;

    assign ready  = load_hit || (re && state == UCDONE) || store_ok;
    assign rdata  = (state == UCDONE) ? uc_data : hit_data;
    assign rd_req = state == REQ;
    assign rd_line = fill_line;
    assign rd_addr = fill_addr;
    assign drain_req = load_miss && !wb_empty;

    wire push = store_ok;
    wire pop  = wb_grant;

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state <= IDLE; cancel <= 1'b0; beat <= '0;
            fill_addr <= '0; fill_idx <= '0; fill_way <= 1'b0; fill_line <= 1'b0; uc_data <= '0;
            q_head <= '0; q_tail <= '0; q_count <= '0;
            perf_refills <= '0; perf_uncached <= '0; perf_wbuf_full <= '0;
            valid <= '0;
            lru <= '0;
        end else begin
            if ((load_hit || (we && hit && cacheable && store_ok)) && WAYS > 1) lru[idx] <= !hit_way;
            if (we && wb_full) perf_wbuf_full <= perf_wbuf_full + 32'd1;

            // write buffer
            if (push) q_tail <= q_tail + 1'b1;
            if (pop)  q_head <= q_head + 1'b1;
            q_count <= q_count + (QB+1)'(push) - (QB+1)'(pop);

            unique case (state)
                IDLE: if (start_rd) begin
                    state <= REQ;
                    fill_line <= cacheable;
                    fill_addr <= cacheable ? {addr[31:OFF_BITS], {OFF_BITS{1'b0}}} : {addr[31:2], 2'b00};
                    fill_idx <= idx; fill_way <= victim; cancel <= 1'b0;
                    if (cacheable) begin
                        valid[victim*SETS + 32'(idx)] <= 1'b0;
                        perf_refills <= perf_refills + 32'd1;
                    end else perf_uncached <= perf_uncached + 32'd1;
                end
                REQ: if (rd_grant) begin state <= FILL; beat <= '0; end
                FILL: if (resp_valid) begin
                    if (!fill_line) begin
                        uc_data <= resp_data;
                        state <= UCDONE;
                    end else begin
                        beat <= beat + 1'b1;
                        if (beat == WB'(LINE_WORDS - 1)) begin
                            state <= IDLE;
                            if (!cancel && !(snoop_inv && snoop_addr[31:OFF_BITS] == fill_addr[31:OFF_BITS])) begin
                                valid[fill_way*SETS + 32'(fill_idx)] <= 1'b1;
                                if (WAYS > 1) lru[fill_idx] <= !fill_way;
                            end
                        end
                    end
                end
                UCDONE: if (re) state <= IDLE;  // the core takes uc_data this cycle
                default: state <= IDLE;
            endcase

            if (snoop_inv) begin
                for (int w = 0; w < WAYS; w++)
                    if (tags[w][snoop_addr[OFF_BITS +: IDX_BITS]] == snoop_addr[31 -: TAG_BITS])
                        valid[w*SETS + 32'(snoop_addr[OFF_BITS +: IDX_BITS])] <= 1'b0;
                if (state != IDLE && fill_line && snoop_addr[31:OFF_BITS] == fill_addr[31:OFF_BITS]) cancel <= 1'b1;
            end
        end
    end

    always_ff @(posedge clk) begin
        if (state == IDLE && start_rd && cacheable) tags[victim][idx] <= tag;
        if (state == FILL && resp_valid && fill_line) data[fill_way][{fill_idx, beat}] <= resp_data;
        // store hit: update the cached copy (write-through; no allocate on miss)
        if (store_ok && cacheable && hit)
            for (int b = 0; b < 4; b++)
                if (wstrb[b]) data[hit_way][widx][8*b +: 8] <= wdata[8*b +: 8];
        if (push) begin
            q_addr[q_tail] <= {addr[31:2], 2'b00};
            q_data[q_tail] <= wdata;
            q_strb[q_tail] <= wstrb;
        end
    end
endmodule

module rv_top #(
    parameter logic [31:0] RESET_PC = 32'h8000_0000,
    parameter int BTB_BITS   = 6,
    parameter bit BPRED_EN   = 1'b1,
    parameter int I_SETS     = 64,
    parameter int I_WAYS     = 1,
    parameter int D_SETS     = 64,
    parameter int D_WAYS     = 1,
    parameter int LINE_WORDS = 4,
    parameter int WB_DEPTH   = 4
) (
    input  wire         clk,
    input  wire         rst_n,

    output logic        bus_req_valid,
    input  wire         bus_req_ready,
    output logic        bus_req_we,
    output logic        bus_req_line,
    output logic        bus_req_ifetch,
    output logic [31:0] bus_req_addr,
    output logic [31:0] bus_req_wdata,
    output logic [3:0]  bus_req_wstrb,
    input  wire         bus_resp_valid,
    input  wire  [31:0] bus_resp_rdata,

    input  wire  [25:0] io_base,
    input  wire         snoop_inv,
    input  wire  [31:0] snoop_addr,

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

    output logic [63:0] perf_cycles,
    output logic [63:0] perf_instret,
    output logic [31:0] perf_branches,
    output logic [31:0] perf_mispredicts,
    output logic [31:0] perf_load_use_stalls,
    output logic [31:0] perf_div_stalls,
    output logic [31:0] perf_imem_stalls,
    output logic [31:0] perf_dmem_stalls,
    output logic [31:0] perf_fetches,
    output logic [31:0] perf_dmem_accesses,
    output logic [31:0] perf_i_refills,
    output logic [31:0] perf_d_refills,
    output logic [31:0] perf_d_uncached,
    output logic [31:0] perf_wbuf_full
);
    logic [31:0] imem_addr, imem_rdata, dmem_addr, dmem_wdata, dmem_rdata;
    logic        imem_ready, dmem_re, dmem_we, dmem_ready, fencei;
    logic [3:0]  dmem_wstrb;

    rv_core #(.RESET_PC(RESET_PC), .BTB_BITS(BTB_BITS), .BPRED_EN(BPRED_EN)) u_core (
        .clk, .rst_n,
        .imem_addr, .imem_rdata, .imem_ready,
        .dmem_re, .dmem_we, .dmem_addr, .dmem_wstrb, .dmem_wdata, .dmem_rdata, .dmem_ready, .fencei,
        .irq_software, .irq_timer, .irq_external,
        .rvfi_valid, .rvfi_order, .rvfi_insn, .rvfi_pc_rdata, .rvfi_pc_wdata, .rvfi_rd_addr, .rvfi_rd_wdata,
        .rvfi_trap, .rvfi_intr, .rvfi_cause, .rvfi_mem_addr, .rvfi_mem_rmask, .rvfi_mem_wmask,
        .rvfi_mem_wdata, .rvfi_mem_rdata,
        .perf_cycles, .perf_instret, .perf_branches, .perf_mispredicts, .perf_load_use_stalls,
        .perf_div_stalls, .perf_imem_stalls, .perf_dmem_stalls, .perf_fetches
    );

    // FENCE.I: instruction refills wait until every older store is in memory
    logic fence_pending;
    logic wb_empty, wb_full, drain_req;

    logic        i_req, i_grant, d_rd_req, d_rd_line, d_rd_grant, wb_req, wb_grant;
    logic [31:0] i_addr, d_rd_addr, wb_addr, wb_data;
    logic [3:0]  wb_strb;
    logic        i_resp, d_resp;

    rv_icache #(.SETS(I_SETS), .LINE_WORDS(LINE_WORDS), .WAYS(I_WAYS)) u_icache (
        .clk, .rst_n, .addr(imem_addr), .rdata(imem_rdata), .ready(imem_ready),
        .flush(fencei), .hold_refill(fence_pending && !wb_empty),
        .req_valid(i_req), .req_addr(i_addr), .req_grant(i_grant),
        .resp_valid(i_resp), .resp_data(bus_resp_rdata), .perf_refills(perf_i_refills)
    );

    rv_dcache #(.SETS(D_SETS), .LINE_WORDS(LINE_WORDS), .WAYS(D_WAYS), .WB_DEPTH(WB_DEPTH)) u_dcache (
        .clk, .rst_n, .re(dmem_re), .we(dmem_we), .addr(dmem_addr), .wstrb(dmem_wstrb), .wdata(dmem_wdata),
        .rdata(dmem_rdata), .ready(dmem_ready), .io_base, .snoop_inv, .snoop_addr,
        .rd_req(d_rd_req), .rd_line(d_rd_line), .rd_addr(d_rd_addr), .rd_grant(d_rd_grant),
        .resp_valid(d_resp), .resp_data(bus_resp_rdata),
        .wb_req, .wb_addr, .wb_data, .wb_strb, .wb_grant, .wb_empty, .wb_full, .drain_req,
        .perf_refills(perf_d_refills), .perf_uncached(perf_d_uncached), .perf_wbuf_full(perf_wbuf_full)
    );

    // ------------------------------------------------------------ arbiter
    // One transaction at a time. Priority: data read > write buffer when it
    // must drain (full, a read or FENCE.I waits on it) > instruction refill >
    // write buffer otherwise.
    typedef enum logic [1:0] {A_IDLE, A_READ} astate_t;
    astate_t astate;
    logic    owner_d;  // the outstanding read belongs to the D-cache
    localparam int BL = $clog2(LINE_WORDS+1);
    logic [BL-1:0] beats_left;

    wire wb_urgent = wb_req && (wb_full || drain_req || fence_pending);
    logic sel_d, sel_wb, sel_i;
    always_comb begin
        sel_d = 1'b0; sel_wb = 1'b0; sel_i = 1'b0;
        if (astate == A_IDLE) begin
            if (d_rd_req) sel_d = 1'b1;
            else if (wb_urgent) sel_wb = 1'b1;
            else if (i_req) sel_i = 1'b1;
            else if (wb_req) sel_wb = 1'b1;
        end
    end
    assign bus_req_valid  = sel_d || sel_wb || sel_i;
    assign bus_req_we     = sel_wb;
    assign bus_req_line   = sel_i || (sel_d && d_rd_line);
    assign bus_req_ifetch = sel_i;
    assign bus_req_addr   = sel_d ? d_rd_addr : sel_wb ? wb_addr : i_addr;
    assign bus_req_wdata  = wb_data;
    assign bus_req_wstrb  = wb_strb;
    assign d_rd_grant = sel_d && bus_req_ready;
    assign wb_grant   = sel_wb && bus_req_ready;
    assign i_grant    = sel_i && bus_req_ready;
    assign i_resp = bus_resp_valid && astate == A_READ && !owner_d;
    assign d_resp = bus_resp_valid && astate == A_READ && owner_d;

    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            astate <= A_IDLE; owner_d <= 1'b0; beats_left <= '0; fence_pending <= 1'b0;
            perf_dmem_accesses <= '0;
        end else begin
            if (fencei) fence_pending <= 1'b1;
            else if (wb_empty) fence_pending <= 1'b0;
            if ((dmem_re || dmem_we) && dmem_ready) perf_dmem_accesses <= perf_dmem_accesses + 32'd1;
            unique case (astate)
                A_IDLE: if (d_rd_grant || i_grant) begin
                    astate <= A_READ;
                    owner_d <= d_rd_grant;
                    beats_left <= (d_rd_grant && !d_rd_line) ? BL'(1) : BL'(LINE_WORDS);
                end
                A_READ: if (bus_resp_valid) begin
                    beats_left <= beats_left - 1'b1;
                    if (beats_left == 1) astate <= A_IDLE;
                end
                default: astate <= A_IDLE;
            endcase
        end
    end
endmodule

`default_nettype wire
