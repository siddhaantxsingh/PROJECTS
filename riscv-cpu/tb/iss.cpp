#include "iss.h"

namespace {
inline int32_t sx(uint32_t v, int bits) { return int32_t(v << (32 - bits)) >> (32 - bits); }
inline uint32_t bits(uint32_t v, int hi, int lo) { return (v >> lo) & ((1u << (hi - lo + 1)) - 1); }
}  // namespace

Retire Iss::trap(Retire r, uint32_t cause, uint32_t tval) {
    r.trap = true;
    r.cause = cause;
    r.rd = 0;
    r.rd_val = 0;
    r.mem_addr = r.mem_wdata = 0;
    r.mem_rmask = r.mem_wmask = 0;
    mepc_ = r.pc;
    mcause_ = cause;
    mtval_ = tval;
    mstatus_mpie_ = mstatus_mie_;
    mstatus_mie_ = false;
    uint32_t base = mtvec_ & ~3u;
    pc_ = ((mtvec_ & 1) && (cause & 0x80000000u)) ? base + 4 * (cause & 0x7fffffffu) : base;
    r.next_pc = pc_;
    return r;
}

Retire Iss::interrupt(uint32_t cause) {
    Retire r;
    r.pc = pc_;
    r.insn = bus_.fetch(pc_);
    r.intr = true;
    return trap(r, cause, 0);
}

bool Iss::csr_read(uint32_t a, uint32_t& v, bool& counter) {
    counter = false;
    switch (a) {
        case 0x300: v = (3u << 11) | (mstatus_mpie_ << 7) | (mstatus_mie_ << 3); return true;
        case 0x301: v = 0x40001100; return true;
        case 0x304: v = mie_; return true;
        case 0x305: v = mtvec_; return true;
        case 0x306: case 0x310: case 0x320: v = 0; return true;
        case 0x340: v = mscratch_; return true;
        case 0x341: v = mepc_; return true;
        case 0x342: v = mcause_; return true;
        case 0x343: v = mtval_; return true;
        case 0x344: v = mip_; return true;
        case 0xB00: case 0xC00: v = uint32_t(mcycle_); counter = true; return true;
        case 0xB80: case 0xC80: v = uint32_t(mcycle_ >> 32); counter = true; return true;
        case 0xB02: case 0xC02: v = uint32_t(minstret_); return true;
        case 0xB82: case 0xC82: v = uint32_t(minstret_ >> 32); return true;
        case 0xF11: case 0xF12: case 0xF13: case 0xF14: case 0xF15: v = 0; return true;
        case 0x7A4: v = 1; return true;  // tinfo: no triggers
        default: break;
    }
    // hardwired-zero hpm counters and events
    if ((a >= 0xB03 && a <= 0xB1F) || (a >= 0xB83 && a <= 0xB9F) || (a >= 0xC03 && a <= 0xC1F) ||
        (a >= 0xC83 && a <= 0xC9F) || (a >= 0x323 && a <= 0x33F) || (a >= 0x7A0 && a <= 0x7A3) ||
        (a >= 0x3A0 && a <= 0x3EF)) {
        v = 0;
        return true;
    }
    return false;
}

void Iss::csr_write(uint32_t a, uint32_t v) {
    switch (a) {
        case 0x300: mstatus_mie_ = (v >> 3) & 1; mstatus_mpie_ = (v >> 7) & 1; break;
        case 0x304: mie_ = v & 0x888; break;
        case 0x305: mtvec_ = v & ~2u; break;
        case 0x340: mscratch_ = v; break;
        case 0x341: mepc_ = v & ~3u; break;
        case 0x342: mcause_ = v; break;
        case 0x343: mtval_ = v; break;
        case 0xB00: mcycle_ = (mcycle_ & ~0xffffffffull) | v; break;
        case 0xB80: mcycle_ = (mcycle_ & 0xffffffffull) | (uint64_t(v) << 32); break;
        case 0xB02: minstret_ = (minstret_ & ~0xffffffffull) | v; instret_written_ = true; break;
        case 0xB82: minstret_ = (minstret_ & 0xffffffffull) | (uint64_t(v) << 32); instret_written_ = true; break;
        default: break;  // read-only-zero / WARL registers ignore writes
    }
}

Retire Iss::step() {
    Retire r;
    r.pc = pc_;
    uint32_t in = bus_.fetch(pc_);
    r.insn = in;
    uint32_t op = in & 0x7f, rd = bits(in, 11, 7), f3 = bits(in, 14, 12), rs1 = bits(in, 19, 15),
             rs2 = bits(in, 24, 20), f7 = bits(in, 31, 25);
    uint32_t a = x_[rs1], b = x_[rs2];
    int32_t imm_i = sx(in >> 20, 12);
    int32_t imm_s = sx((f7 << 5) | rd, 12);
    int32_t imm_b = sx((bits(in, 31, 31) << 12) | (bits(in, 7, 7) << 11) | (bits(in, 30, 25) << 5) | (bits(in, 11, 8) << 1), 13);
    uint32_t imm_u = in & 0xfffff000u;
    int32_t imm_j = sx((bits(in, 31, 31) << 20) | (bits(in, 19, 12) << 12) | (bits(in, 20, 20) << 11) | (bits(in, 30, 21) << 1), 21);

    uint32_t next = pc_ + 4;
    bool wr = false;
    uint32_t val = 0;
    auto illegal = [&]() { return trap(r, 2, in); };
    instret_written_ = false;
    mcycle_++;  // not comparable with the RTL; only used for monotonicity

    if ((in & 3) != 3) return illegal();
    switch (op) {
        case 0x37: wr = true; val = imm_u; break;
        case 0x17: wr = true; val = pc_ + imm_u; break;
        case 0x6f: {
            uint32_t t = pc_ + imm_j;
            if (t & 3) return trap(r, 0, t);
            wr = true; val = pc_ + 4; next = t;
            break;
        }
        case 0x67: {
            if (f3) return illegal();
            uint32_t t = (a + imm_i) & ~1u;
            if (t & 3) return trap(r, 0, t);
            wr = true; val = pc_ + 4; next = t;
            break;
        }
        case 0x63: {
            bool tk;
            switch (f3) {
                case 0: tk = a == b; break;
                case 1: tk = a != b; break;
                case 4: tk = int32_t(a) < int32_t(b); break;
                case 5: tk = int32_t(a) >= int32_t(b); break;
                case 6: tk = a < b; break;
                case 7: tk = a >= b; break;
                default: return illegal();
            }
            if (tk) {
                uint32_t t = pc_ + imm_b;
                if (t & 3) return trap(r, 0, t);
                next = t;
            }
            break;
        }
        case 0x03: {
            uint32_t addr = a + imm_i;
            int size;
            switch (f3) {
                case 0: case 4: size = 1; break;
                case 1: case 5: size = 2; break;
                case 2: size = 4; break;
                default: return illegal();
            }
            if (addr & (size - 1)) return trap(r, 4, addr);
            uint32_t v;
            if (!bus_.load(addr, size, v)) return trap(r, 5, addr);
            switch (f3) {
                case 0: val = uint32_t(sx(v, 8)); break;
                case 1: val = uint32_t(sx(v, 16)); break;
                default: val = v; break;
            }
            wr = true;
            r.mem_addr = addr;
            r.mem_rmask = uint8_t(((1u << size) - 1) << (addr & 3));
            break;
        }
        case 0x23: {
            uint32_t addr = a + imm_s;
            int size;
            switch (f3) {
                case 0: size = 1; break;
                case 1: size = 2; break;
                case 2: size = 4; break;
                default: return illegal();
            }
            if (addr & (size - 1)) return trap(r, 6, addr);
            uint32_t v = size == 4 ? b : (b & ((1u << (8 * size)) - 1));
            if (!bus_.store(addr, size, v)) return trap(r, 7, addr);
            r.mem_addr = addr;
            r.mem_wmask = uint8_t(((1u << size) - 1) << (addr & 3));
            r.mem_wdata = size == 1 ? (b & 0xff) * 0x01010101u : size == 2 ? (b & 0xffff) * 0x00010001u : b;
            break;
        }
        case 0x13: {
            wr = true;
            uint32_t sh = rs2;
            switch (f3) {
                case 0: val = a + imm_i; break;
                case 2: val = int32_t(a) < imm_i; break;
                case 3: val = a < uint32_t(imm_i); break;
                case 4: val = a ^ uint32_t(imm_i); break;
                case 6: val = a | uint32_t(imm_i); break;
                case 7: val = a & uint32_t(imm_i); break;
                case 1: if (f7) return illegal(); val = a << sh; break;
                default:
                    if (f7 == 0) val = a >> sh;
                    else if (f7 == 0x20) val = uint32_t(int32_t(a) >> sh);
                    else return illegal();
            }
            break;
        }
        case 0x33: {
            wr = true;
            if (f7 == 1) {
                int64_t sa = int32_t(a), sb = int32_t(b);
                uint64_t ua = a, ub = b;
                switch (f3) {
                    case 0: val = a * b; break;
                    case 1: val = uint32_t((sa * sb) >> 32); break;
                    case 2: val = uint32_t(uint64_t(sa * int64_t(ub)) >> 32); break;
                    case 3: val = uint32_t((ua * ub) >> 32); break;
                    case 4: val = b == 0 ? 0xffffffffu : (a == 0x80000000u && b == 0xffffffffu) ? a : uint32_t(int32_t(a) / int32_t(b)); break;
                    case 5: val = b == 0 ? 0xffffffffu : a / b; break;
                    case 6: val = b == 0 ? a : (a == 0x80000000u && b == 0xffffffffu) ? 0 : uint32_t(int32_t(a) % int32_t(b)); break;
                    default: val = b == 0 ? a : a % b; break;
                }
            } else if (f7 == 0) {
                switch (f3) {
                    case 0: val = a + b; break;
                    case 1: val = a << (b & 31); break;
                    case 2: val = int32_t(a) < int32_t(b); break;
                    case 3: val = a < b; break;
                    case 4: val = a ^ b; break;
                    case 5: val = a >> (b & 31); break;
                    case 6: val = a | b; break;
                    default: val = a & b; break;
                }
            } else if (f7 == 0x20 && f3 == 0) {
                val = a - b;
            } else if (f7 == 0x20 && f3 == 5) {
                val = uint32_t(int32_t(a) >> (b & 31));
            } else {
                return illegal();
            }
            break;
        }
        case 0x0f:
            if (f3 > 1) return illegal();
            break;  // fence / fence.i: no caches to manage
        case 0x73: {
            if (f3 == 0) {
                if (in == 0x00000073) return trap(r, 11, 0);
                if (in == 0x00100073) return trap(r, 3, pc_);
                if (in == 0x30200073) {
                    next = mepc_;
                    mstatus_mie_ = mstatus_mpie_;
                    mstatus_mpie_ = true;
                    break;
                }
                if (in == 0x10500073) break;  // wfi as nop
                return illegal();
            }
            if (f3 == 4) return illegal();
            uint32_t csr = in >> 20;
            uint32_t src = (f3 & 4) ? rs1 : a;
            bool writes = (f3 & 3) == 1 || rs1 != 0;
            uint32_t old;
            bool counter;
            if (!csr_read(csr, old, counter)) return illegal();
            if (writes && (csr >> 10) == 3) return illegal();
            if (writes) {
                uint32_t nv = (f3 & 3) == 1 ? src : (f3 & 3) == 2 ? (old | src) : (old & ~src);
                csr_write(csr, nv);
            }
            wr = true;
            val = old;
            r.reads_counter = counter;
            break;
        }
        default: return illegal();
    }
    if (wr && rd) {
        x_[rd] = val;
        r.rd = int(rd);
        r.rd_val = val;
    }
    pc_ = next;
    r.next_pc = next;
    if (!instret_written_) minstret_++;
    return r;
}
