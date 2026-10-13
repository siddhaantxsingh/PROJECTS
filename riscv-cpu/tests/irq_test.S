# Interrupt stress test (run with `sim --irq SEED`).
#
# The harness raises the software, timer and external interrupt lines at
# random cycles; the handler acknowledges each one by storing its mip bit to
# IRQ_ACK and counts it. Meanwhile the main loop computes a checksum with
# ALU, multiply/divide, loads/stores and branches. The first half runs with
# mtvec in direct mode, the second half in vectored mode. At the end the
# program checks that it took at least one interrupt and that its own count
# equals the harness's count. Lockstep against the ISS checks that every
# interrupt was precise (no lost or repeated instruction, correct mepc).
#include "../third_party/riscv-tests/env/encoding.h"

  .equ IRQ_ACK,   0x10000008
  .equ IRQ_COUNT, 0x1000000C
  .equ ITER,      3000

  .section .text.init
  .globl _start
_start:
  la t0, handler
  csrw mtvec, t0
  li t0, (MIP_MSIP | MIP_MTIP | MIP_MEIP)
  csrw mie, t0
  la s1, counter
  la s2, buf
  li s3, 0x12345678          # checksum
  li s4, ITER
  li s5, ITER / 2
  csrsi mstatus, MSTATUS_MIE  # only once the handler's pointers are set

loop:
  # switch to vectored mode half way through
  bne s4, s5, 1f
  la t0, vectors
  ori t0, t0, 1
  csrw mtvec, t0
1:
  li t1, 1103515245
  mul s3, s3, t1
  li t1, 12345
  add s3, s3, t1
  andi t2, s4, 63
  slli t2, t2, 2
  add t3, s2, t2
  lw t4, 0(t3)
  xor t4, t4, s3
  sw t4, 0(t3)
  li t5, 7
  remu t6, s3, t5
  divu a0, s3, t5
  add s3, s3, t6
  xor s3, s3, a0
  bltu t6, t5, 2f
  addi s3, s3, 1            # never taken (remainder < 7)
2:
  addi s4, s4, -1
  bnez s4, loop

  csrci mstatus, MSTATUS_MIE
  lw t0, 0(s1)
  beqz t0, fail
  li t1, IRQ_COUNT
  lw t1, 0(t1)
  bne t0, t1, fail
  li t0, 1
  la t1, tohost
  sw t0, 0(t1)
3: j 3b
fail:
  li t0, 3                  # exit code 1
  la t1, tohost
  sw t0, 0(t1)
4: j 4b

# Direct-mode handler: acknowledge whatever caused the trap.
  .align 2
handler:
  csrrw sp, mscratch, sp
  la sp, save
  sw a0, 0(sp)
  sw a1, 4(sp)
  csrr a0, mcause
  bgez a0, bad_trap         # only interrupts are expected
  andi a0, a0, 31
  li a1, 1
  sll a1, a1, a0
  li a0, IRQ_ACK
  sw a1, 0(a0)
  lw a0, 0(s1)
  addi a0, a0, 1
  sw a0, 0(s1)
  lw a0, 0(sp)
  lw a1, 4(sp)
  csrrw sp, mscratch, sp
  mret
bad_trap:
  j fail

# Vectored-mode table: exceptions to entry 0, interrupt n to entry n.
  .align 6
vectors:
  j bad_trap      # 0: exceptions
  j bad_trap
  j bad_trap
  j handler       # 3: machine software
  j bad_trap
  j bad_trap
  j bad_trap
  j handler       # 7: machine timer
  j bad_trap
  j bad_trap
  j bad_trap
  j handler       # 11: machine external

  .section .tohost, "aw", @progbits
  .align 6
  .globl tohost
tohost: .dword 0
  .align 6
  .globl fromhost
fromhost: .dword 0

  .data
  .align 4
counter: .word 0
save: .word 0, 0
buf: .fill 64, 4, 0x5a5a5a5a
