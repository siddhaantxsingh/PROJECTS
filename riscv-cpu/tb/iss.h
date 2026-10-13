// Reference instruction-set simulator for RV32IM_Zicsr_Zifencei, machine mode.
//
// Written from the ISA manuals, independently of the RTL, and used as the
// golden model for lockstep comparison. It is itself checked against the
// official riscv-tests and against Spike commit logs (see docs/verification.md).
#pragma once
#include <cstdint>
#include <functional>
#include <string>

struct Bus {
    // Return false for an access outside the address map (the harness treats
    // that as a test failure; neither model implements access faults).
    std::function<bool(uint32_t addr, int size, uint32_t& val)> load;
    std::function<bool(uint32_t addr, int size, uint32_t val)> store;
    std::function<uint32_t(uint32_t addr)> fetch;
};

struct Retire {
    uint32_t pc = 0, insn = 0, next_pc = 0;
    int rd = 0;            // 0 when no register is written
    uint32_t rd_val = 0;
    bool trap = false, intr = false;
    uint32_t cause = 0;
    uint32_t mem_addr = 0, mem_wdata = 0;
    uint8_t mem_rmask = 0, mem_wmask = 0;
    bool reads_counter = false;  // rd value depends on cycle counters (not comparable)
};

class Iss {
public:
    explicit Iss(Bus bus, uint32_t reset_pc) : bus_(std::move(bus)), pc_(reset_pc) {}

    // Execute one instruction (or take its exception).
    Retire step();
    // Take an interrupt with the given mcause before the instruction at pc.
    Retire interrupt(uint32_t cause);
    // Overwrite a register (used when the RTL read a cycle counter).
    void set_reg(int r, uint32_t v) { if (r) x_[r] = v; }
    void set_mip(uint32_t v) { mip_ = v; }
    bool irq_ready() const { return mstatus_mie_ && (mip_ & mie_); }
    bool irq_enabled(uint32_t cause) const { return mstatus_mie_ && ((mie_ >> (cause & 31)) & 1); }

    uint32_t pc() const { return pc_; }
    uint32_t reg(int r) const { return x_[r]; }
    uint64_t instret() const { return minstret_; }

private:
    bool csr_read(uint32_t addr, uint32_t& v, bool& counter);
    void csr_write(uint32_t addr, uint32_t v);
    Retire trap(Retire r, uint32_t cause, uint32_t tval);

    Bus bus_;
    uint32_t pc_;
    uint32_t x_[32] = {};
    bool mstatus_mie_ = false, mstatus_mpie_ = false;
    uint32_t mie_ = 0, mip_ = 0, mtvec_ = 0, mscratch_ = 0, mepc_ = 0, mcause_ = 0, mtval_ = 0;
    uint64_t mcycle_ = 0, minstret_ = 0;
    bool instret_written_ = false;
};
