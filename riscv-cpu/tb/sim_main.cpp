// Verilator harness for rv_core.
//
//   sim [options] program.elf
//     --max-cycles N     give up after N cycles (default 200M)
//     --no-lockstep      do not run the reference ISS alongside the RTL
//     --trace FILE       write one line per retired instruction (for spike-compare.py)
//     --stats FILE       write performance counters as JSON
//     --irq SEED         randomly assert the timer/external/software interrupt lines;
//                        the program acknowledges by storing to IRQ_ACK
//     --quiet            no console echo
//
// Memory map: RAM at 0x8000_0000 (16 MiB), console (byte store) at
// 0x1000_0000, interrupt acknowledge at 0x1000_0008, irq counter (read) at
// 0x1000_000C. Programs end by writing tohost (HTIF): an odd value v exits
// with code v >> 1; an even value points to an HTIF syscall block (only
// SYS_write is supported, enough for the riscv-tests benchmarks' printf).
// Exit status: 0 pass, 1 test failure, 2 lockstep mismatch, 3 timeout, 4 error.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <random>
#include <string>
#include <vector>

#include "Vrv_core.h"
#include "iss.h"
#include "verilated.h"

namespace {

constexpr uint32_t RAM_BASE = 0x80000000u;
constexpr uint32_t RAM_SIZE = 16u << 20;
constexpr uint32_t CONSOLE = 0x10000000u;
constexpr uint32_t IRQ_ACK = 0x10000008u;
constexpr uint32_t IRQ_COUNT = 0x1000000Cu;

struct Memory {
    std::vector<uint8_t> ram = std::vector<uint8_t>(RAM_SIZE, 0);
    bool in_ram(uint32_t a, int n) const { return a >= RAM_BASE && uint64_t(a) + n <= uint64_t(RAM_BASE) + RAM_SIZE; }
    uint32_t rd(uint32_t a, int n) const {
        uint32_t v = 0;
        for (int i = 0; i < n; i++) v |= uint32_t(ram[a - RAM_BASE + i]) << (8 * i);
        return v;
    }
    void wr(uint32_t a, int n, uint32_t v) {
        for (int i = 0; i < n; i++) ram[a - RAM_BASE + i] = uint8_t(v >> (8 * i));
    }
};

struct Elf {
    uint32_t entry = 0, tohost = 0, fromhost = 0;
};

bool load_elf(const char* path, Memory& m, Elf& e) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<uint8_t> b((std::istreambuf_iterator<char>(f)), {});
    auto u16 = [&](size_t o) { return uint32_t(b.at(o)) | uint32_t(b.at(o + 1)) << 8; };
    auto u32 = [&](size_t o) { return u16(o) | u16(o + 2) << 16; };
    if (b.size() < 52 || memcmp(b.data(), "\x7f" "ELF", 4) || b[4] != 1 || u16(18) != 243) {
        fprintf(stderr, "not a 32-bit RISC-V ELF: %s\n", path);
        return false;
    }
    e.entry = u32(24);
    uint32_t phoff = u32(28), shoff = u32(32), phnum = u16(44), shnum = u16(48), shentsz = u16(46);
    for (uint32_t i = 0; i < phnum; i++) {
        size_t p = phoff + i * 32;
        if (u32(p) != 1) continue;  // PT_LOAD
        uint32_t off = u32(p + 4), paddr = u32(p + 12), filesz = u32(p + 16), memsz = u32(p + 20);
        if (!m.in_ram(paddr, memsz)) {
            fprintf(stderr, "segment at 0x%08x (+%u) outside RAM\n", paddr, memsz);
            return false;
        }
        for (uint32_t k = 0; k < memsz; k++) m.ram[paddr - RAM_BASE + k] = k < filesz ? b.at(off + k) : 0;
    }
    // symbols: tohost / fromhost
    for (uint32_t i = 0; i < shnum; i++) {
        size_t s = shoff + i * shentsz;
        if (u32(s + 4) != 2) continue;  // SHT_SYMTAB
        uint32_t off = u32(s + 16), size = u32(s + 20), link = u32(s + 24), entsz = u32(s + 36);
        size_t strtab = u32(shoff + link * shentsz + 16);
        for (uint32_t k = 0; k * entsz < size; k++) {
            size_t sym = off + k * entsz;
            std::string name(reinterpret_cast<const char*>(&b.at(strtab + u32(sym))));
            if (name == "tohost") e.tohost = u32(sym + 4);
            if (name == "fromhost") e.fromhost = u32(sym + 4);
        }
    }
    return true;
}

// HTIF device shared by both models (each with its own memory copy).
struct Htif {
    uint32_t tohost = 0, fromhost = 0;
    bool done = false;
    int code = 0;
    std::string out;
    bool echo = false;
    // returns true if the store was consumed by the host
    void on_store(Memory& m, uint32_t addr, uint32_t val, bool primary) {
        if (!tohost || addr != tohost || val == 0) return;
        if (val & 1) {
            if (primary) { done = true; code = int(val >> 1); }
            m.wr(tohost, 4, 0);
            return;
        }
        // syscall block: magic_mem[0..3] as 64-bit words
        uint32_t which = m.rd(val, 4), a0 = m.rd(val + 8, 4), a1 = m.rd(val + 16, 4), a2 = m.rd(val + 24, 4);
        uint32_t ret = uint32_t(-38);  // -ENOSYS
        if (which == 64 && (a0 == 1 || a0 == 2) && m.in_ram(a1, a2)) {
            if (primary) {
                std::string s(reinterpret_cast<const char*>(&m.ram[a1 - RAM_BASE]), a2);
                out += s;
                if (echo) fputs(s.c_str(), stdout);
            }
            ret = a2;
        } else if (which == 93) {  // exit
            if (primary) { done = true; code = int(a0); }
            ret = 0;
        }
        m.wr(val, 4, ret);
        m.wr(val + 4, 4, 0);
        m.wr(tohost, 4, 0);
        m.wr(tohost + 4, 4, 0);
        if (fromhost) { m.wr(fromhost, 4, 1); m.wr(fromhost + 4, 4, 0); }
    }
};

}  // namespace

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    uint64_t max_cycles = 200000000ull;
    bool lockstep = true, quiet = false, irq_mode = false;
    uint32_t irq_seed = 0;
    const char* trace_path = nullptr;
    const char* stats_path = nullptr;
    const char* elf_path = nullptr;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        if (a == "--max-cycles" && i + 1 < argc) max_cycles = strtoull(argv[++i], nullptr, 0);
        else if (a == "--no-lockstep") lockstep = false;
        else if (a == "--trace" && i + 1 < argc) trace_path = argv[++i];
        else if (a == "--stats" && i + 1 < argc) stats_path = argv[++i];
        else if (a == "--irq" && i + 1 < argc) { irq_mode = true; irq_seed = uint32_t(strtoul(argv[++i], nullptr, 0)); }
        else if (a == "--quiet") quiet = true;
        else if (a[0] != '+' && a[0] != '-') elf_path = argv[i];
    }
    if (!elf_path) {
        fprintf(stderr, "usage: sim [--max-cycles N] [--no-lockstep] [--trace F] [--stats F] [--irq SEED] [--quiet] prog.elf\n");
        return 4;
    }

    Memory mem, iss_mem;
    Elf elf;
    if (!load_elf(elf_path, mem, elf)) return 4;
    iss_mem = mem;
    Htif htif, iss_htif;
    htif.tohost = iss_htif.tohost = elf.tohost;
    htif.fromhost = iss_htif.fromhost = elf.fromhost;
    htif.echo = !quiet;
    std::string console;
    uint32_t irq_lines = 0;  // bit 3 software, 7 timer, 11 external (mip layout)
    uint32_t irq_count = 0;
    std::mt19937 rng(irq_seed);

    Bus bus;
    bus.fetch = [&](uint32_t a) { return iss_mem.in_ram(a, 4) ? iss_mem.rd(a, 4) : 0u; };
    bus.load = [&](uint32_t a, int n, uint32_t& v) {
        if (iss_mem.in_ram(a, n)) { v = iss_mem.rd(a, n); return true; }
        if (a == IRQ_COUNT && n == 4) { v = irq_count; return true; }
        return false;
    };
    bus.store = [&](uint32_t a, int n, uint32_t v) {
        if (a == CONSOLE || a == IRQ_ACK) return true;
        if (!iss_mem.in_ram(a, n)) return false;
        iss_mem.wr(a, n, v);
        if (n == 4) iss_htif.on_store(iss_mem, a, v, false);
        return true;
    };
    Iss iss(bus, elf.entry);

    std::unique_ptr<VerilatedContext> ctx(new VerilatedContext);
    std::unique_ptr<Vrv_core> top(new Vrv_core(ctx.get()));
    FILE* trace = trace_path ? fopen(trace_path, "w") : nullptr;

    if (elf.entry != 0x80000000u) {
        fprintf(stderr, "entry point 0x%08x != reset vector 0x80000000\n", elf.entry);
        return 4;
    }

    top->clk = 0;
    top->rst_n = 0;
    top->irq_software = top->irq_timer = top->irq_external = 0;
    top->eval();
    top->clk = 1; top->eval();
    top->clk = 0; top->eval();
    top->rst_n = 1;
    top->eval();

    int status = -1;
    uint64_t cycle = 0, retired = 0;
    bool bad_access = false;
    for (; cycle < max_cycles && status < 0; cycle++) {
        // combinational memory reads for this cycle
        uint32_t ia = top->imem_addr;
        top->imem_rdata = mem.in_ram(ia & ~3u, 4) ? mem.rd(ia & ~3u, 4) : 0u;
        uint32_t da = top->dmem_addr & ~3u;
        if (top->dmem_re) {
            if (mem.in_ram(da, 4)) top->dmem_rdata = mem.rd(da, 4);
            else if (da == IRQ_COUNT) top->dmem_rdata = irq_count;
            else { bad_access = true; top->dmem_rdata = 0; }
        }
        top->eval();

        // synchronous store at the clock edge
        if (top->dmem_we) {
            uint32_t a = top->dmem_addr & ~3u, w = top->dmem_wdata;
            uint8_t s = top->dmem_wstrb;
            if (mem.in_ram(a, 4)) {
                for (int i = 0; i < 4; i++)
                    if (s >> i & 1) mem.ram[a - RAM_BASE + i] = uint8_t(w >> (8 * i));
                if (s == 0xF) htif.on_store(mem, a, w, true);
            } else if (a == CONSOLE) {
                char c = char(w & 0xff);
                console += c;
                if (!quiet) { putchar(c); fflush(stdout); }
            } else if (a == IRQ_ACK) {
                irq_lines &= ~w;
                irq_count++;
            } else {
                bad_access = true;
            }
        }
        // interrupt lines change only between clock edges
        if (irq_mode && (rng() % 64) == 0) irq_lines |= (1u << (rng() % 3 == 0 ? 3 : rng() % 2 ? 7 : 11));
        top->irq_software = (irq_lines >> 3) & 1;
        top->irq_timer = (irq_lines >> 7) & 1;
        top->irq_external = (irq_lines >> 11) & 1;

        top->clk = 1;
        top->eval();
        top->clk = 0;
        top->eval();

        if (bad_access) {
            fprintf(stderr, "access outside the memory map near cycle %llu (addr 0x%08x)\n", (unsigned long long)cycle, top->dmem_addr);
            status = 4;
            break;
        }

        if (top->rvfi_valid) {
            retired++;
            Retire got;
            got.pc = top->rvfi_pc_rdata;
            got.insn = top->rvfi_insn;
            got.next_pc = top->rvfi_pc_wdata;
            got.rd = top->rvfi_rd_addr;
            got.rd_val = top->rvfi_rd_wdata;
            got.trap = top->rvfi_trap;
            got.intr = top->rvfi_intr;
            got.cause = top->rvfi_cause;
            got.mem_addr = top->rvfi_mem_addr;
            got.mem_rmask = top->rvfi_mem_rmask;
            got.mem_wmask = top->rvfi_mem_wmask;
            got.mem_wdata = top->rvfi_mem_wdata;
            if (trace) {
                fprintf(trace, "%08x %08x", got.pc, got.insn);
                if (got.trap) fprintf(trace, " trap %08x", got.cause);
                if (got.rd) fprintf(trace, " x%d %08x", got.rd, got.rd_val);
                if (got.mem_wmask) fprintf(trace, " st %08x %x %08x", got.mem_addr, got.mem_wmask, got.mem_wdata);
                if (got.mem_rmask) fprintf(trace, " ld %08x %x", got.mem_addr, got.mem_rmask);
                fputc('\n', trace);
            }
            if (lockstep) {
                Retire exp;
                if (got.intr) {
                    // The RTL sampled the interrupt lines one cycle before this
                    // record left WB; the ISS must also find it enabled+pending.
                    if (!iss.irq_enabled(got.cause)) {
                        fprintf(stderr, "RTL took interrupt %08x at pc %08x while the ISS has it disabled\n", got.cause, got.pc);
                        status = 2;
                        break;
                    }
                    exp = iss.interrupt(got.cause);
                } else {
                    exp = iss.step();
                }
                if (exp.reads_counter && got.rd == exp.rd) {
                    exp.rd_val = got.rd_val;
                    iss.set_reg(got.rd, got.rd_val);
                }
                uint32_t wm = 0;
                for (int i = 0; i < 4; i++) if (got.mem_wmask >> i & 1) wm |= 0xffu << (8 * i);
                bool ok = got.pc == exp.pc && got.insn == exp.insn && got.trap == exp.trap &&
                          got.next_pc == exp.next_pc && got.rd == exp.rd && got.rd_val == exp.rd_val &&
                          (!got.trap || got.cause == exp.cause) && got.mem_rmask == exp.mem_rmask &&
                          got.mem_wmask == exp.mem_wmask &&
                          ((got.mem_rmask | got.mem_wmask) == 0 || got.mem_addr == exp.mem_addr) &&
                          (got.mem_wdata & wm) == (exp.mem_wdata & wm);
                if (!ok) {
                    fprintf(stderr,
                            "LOCKSTEP MISMATCH at retirement %llu (cycle %llu)\n"
                            "  rtl: pc=%08x insn=%08x next=%08x rd=x%d=%08x trap=%d intr=%d cause=%08x mem=%08x r%x w%x %08x\n"
                            "  iss: pc=%08x insn=%08x next=%08x rd=x%d=%08x trap=%d intr=%d cause=%08x mem=%08x r%x w%x %08x\n",
                            (unsigned long long)retired, (unsigned long long)cycle, got.pc, got.insn, got.next_pc, got.rd,
                            got.rd_val, got.trap, got.intr, got.cause, got.mem_addr, got.mem_rmask, got.mem_wmask,
                            got.mem_wdata, exp.pc, exp.insn, exp.next_pc, exp.rd, exp.rd_val, exp.trap, exp.intr, exp.cause,
                            exp.mem_addr, exp.mem_rmask, exp.mem_wmask, exp.mem_wdata);
                    status = 2;
                    break;
                }
            }
        }
        if (lockstep) iss.set_mip(irq_lines);
        if (htif.done) status = htif.code == 0 ? 0 : 1;
    }
    if (status < 0) status = 3;

    double cpi = top->perf_instret ? double(top->perf_cycles) / double(top->perf_instret) : 0.0;
    if (stats_path) {
        FILE* s = fopen(stats_path, "w");
        fprintf(s,
                "{\"status\": %d, \"exit_code\": %d, \"cycles\": %llu, \"instret\": %llu, \"cpi\": %.4f, "
                "\"branches\": %u, \"mispredicts\": %u, \"load_use_stalls\": %u, \"div_stall_cycles\": %u, "
                "\"interrupts_acked\": %u, \"lockstep\": %s}\n",
                status, htif.code, (unsigned long long)top->perf_cycles, (unsigned long long)top->perf_instret, cpi,
                top->perf_branches, top->perf_mispredicts, top->perf_load_use_stalls, top->perf_div_stalls, irq_count,
                lockstep ? "true" : "false");
        fclose(s);
    }
    if (trace) fclose(trace);
    const char* what[] = {"PASS", "FAIL", "MISMATCH", "TIMEOUT", "ERROR"};
    fprintf(stderr, "%s %s: exit=%d cycles=%llu instret=%llu CPI=%.3f branches=%u mispredicts=%u irqs=%u\n",
            what[status], elf_path, htif.code, (unsigned long long)top->perf_cycles,
            (unsigned long long)top->perf_instret, cpi, top->perf_branches, top->perf_mispredicts, irq_count);
    top->final();
    return status;
}
