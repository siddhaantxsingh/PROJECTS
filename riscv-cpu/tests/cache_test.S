# Directed cache corner cases. Runs on both the idealised-memory build and
# the cached builds, in lockstep with the ISS; it also checks its own results
# and exits with a non-zero code naming the first failed check.
#
#  1. conflict evictions: lines 1, 2, 4 and 8 KiB apart map to the same set
#     in every configuration used by `make test-cache-all`; written, then
#     re-read after the others have evicted them
#  2. store-then-load to the same line: byte/half/word stores into a cached
#     cache line, read back at once with every width
#  3. store miss (no write-allocate) followed by a load of the same word:
#     the load must wait for the write buffer before refilling
#  4. a burst of stores that fills the write buffer, then loads of them
#  5. self-modifying code with FENCE.I: an instruction that is already in the
#     I-cache is overwritten and executed again after FENCE.I
#  6. an uncached MMIO load (irq counter) between cached accesses
  .equ IRQ_COUNT, 0x1000000C

  .section .text.init
  .globl _start
_start:
  li s11, 0                  # check number
  la s0, buf                 # 16 KiB, 64-byte aligned

  # ---- 1. conflict evictions
  li s11, 1
  li t0, 0
  li t1, 16                  # 16 addresses: buf + k*1024, k = 0..15
1:slli t2, t0, 10
  add t2, s0, t2
  addi t3, t0, 0x100
  sw t3, 0(t2)
  sw t3, 12(t2)              # last word of a 16-byte line
  addi t0, t0, 1
  blt t0, t1, 1b
  li t0, 15                  # read back in reverse order
2:slli t2, t0, 10
  add t2, s0, t2
  lw t4, 0(t2)
  lw t5, 12(t2)
  addi t3, t0, 0x100
  bne t4, t3, fail
  bne t5, t3, fail
  addi t0, t0, -1
  bgez t0, 2b
  # ping-pong between two conflicting lines many times
  li t0, 64
  li t6, 4096
  add t6, s0, t6
3:lw t4, 0(s0)
  lw t5, 0(t6)
  li t3, 0x100
  bne t4, t3, fail
  li t3, 0x104
  bne t5, t3, fail
  addi t0, t0, -1
  bnez t0, 3b

  # ---- 2. store-then-load to the same (cached) line
  li s11, 2
  addi s1, s0, 64
  lw zero, 0(s1)             # bring the line in
  li t0, 0x11223344
  sw t0, 0(s1)
  lw t1, 0(s1)
  bne t0, t1, fail
  li t0, 0xAB
  sb t0, 1(s1)
  lw t1, 0(s1)
  li t2, 0x1122AB44
  bne t1, t2, fail
  lbu t1, 1(s1)
  bne t1, t0, fail
  li t0, 0x8765
  sh t0, 2(s1)
  lh t1, 2(s1)
  li t2, 0xFFFF8765
  bne t1, t2, fail
  lw t1, 0(s1)
  li t2, 0x8765AB44
  bne t1, t2, fail
  sw t1, 4(s1)               # store, then a dependent load of the next word
  lw t3, 4(s1)
  bne t3, t2, fail

  # ---- 3. store miss (no allocate), then load of the same word
  li s11, 3
  li t0, 8192 + 128
  add s2, s0, t0             # a line nobody touched yet
  li t0, 0x5A5A1234
  sw t0, 4(s2)
  lw t1, 4(s2)
  bne t0, t1, fail
  sb t0, 9(s2)               # byte store miss, then halfword load
  lhu t1, 8(s2)
  li t2, 0x3400
  bne t1, t2, fail

  # ---- 4. burst of stores (fills the write buffer), then loads
  li s11, 4
  li t0, 12288
  add s3, s0, t0
  li t0, 0
  li t1, 32
4:slli t2, t0, 2
  add t2, s3, t2
  sw t0, 0(t2)
  addi t0, t0, 1
  blt t0, t1, 4b
  li t0, 0
5:slli t2, t0, 2
  add t2, s3, t2
  lw t3, 0(t2)
  bne t3, t0, fail
  addi t0, t0, 1
  blt t0, t1, 5b

  # ---- 5. self-modifying code with FENCE.I
  li s11, 5
  jal patchme                # first execution: caches the line, returns 1
  li t0, 1
  bne a0, t0, fail
  la t1, patchme
  li t2, 0x02a00513          # addi a0, zero, 42
  sw t2, 0(t1)
  fence.i
  jal patchme
  li t0, 42
  bne a0, t0, fail
  la t1, patchme             # restore, so a re-run starts clean
  li t2, 0x00100513          # addi a0, zero, 1
  sw t2, 0(t1)
  fence.i
  jal patchme
  li t0, 1
  bne a0, t0, fail

  # ---- 6. uncached MMIO load between cached accesses
  li s11, 6
  lw t0, 0(s0)
  li t1, IRQ_COUNT
  lw t2, 0(t1)
  bnez t2, fail              # no interrupts in this test
  lw t3, 0(s0)
  bne t0, t3, fail

  li t0, 1
  la t1, tohost
  sw t0, 0(t1)
6:j 6b
fail:
  slli t0, s11, 1
  ori t0, t0, 1
  la t1, tohost
  sw t0, 0(t1)
7:j 7b

  .align 6
patchme:
  addi a0, zero, 1
  ret

  .section .tohost, "aw", @progbits
  .align 6
  .globl tohost
tohost: .dword 0
  .globl fromhost
fromhost: .dword 0

  .bss
  .align 6
buf: .space 16384
