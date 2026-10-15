# Self-modifying code WITHOUT FENCE.I (documents behaviour; not a pass/fail
# test of correctness). The ISA only guarantees that a store is visible to
# instruction fetch after FENCE.I. This program runs `patchme` once (so the
# cached build has it in the I-cache), overwrites it, and runs it again with
# no FENCE.I. Exit code 0: the new instruction ran; exit code 2: the old
# (stale) instruction ran. Run with --no-lockstep: the reference ISS always
# fetches from memory, so it would disagree with a stale fetch.
  .section .text.init
  .globl _start
_start:
  jal patchme
  la t1, patchme
  li t2, 0x02a00513          # addi a0, zero, 42
  sw t2, 0(t1)
  nop
  nop
  nop
  nop
  jal patchme
  li t0, 42
  li t3, 1                   # exit 0 (new instruction)
  beq a0, t0, 1f
  li t3, 5                   # exit 2 (stale instruction)
1:la t1, tohost
  sw t3, 0(t1)
2:j 2b

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
