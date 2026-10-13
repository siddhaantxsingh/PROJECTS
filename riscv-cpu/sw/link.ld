/* Bare-metal layout for rv_core programs: 16 MiB of RAM at 0x80000000. */
OUTPUT_ARCH("riscv")
ENTRY(_start)

MEMORY { RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 16M }

SECTIONS
{
  .text.init : { *(.text.init) } > RAM
  .text : { *(.text .text.*) } > RAM
  . = ALIGN(64);
  .tohost : { *(.tohost) } > RAM
  .rodata : { *(.rodata .rodata.* .srodata .srodata.*) } > RAM
  .data : { *(.data .data.* .sdata .sdata.*) } > RAM
  .bss (NOLOAD) : { __bss_start = .; *(.bss .bss.* .sbss .sbss.* COMMON); __bss_end = .; } > RAM
  . = ALIGN(16);
  _end = .;
  __stack_top = ORIGIN(RAM) + LENGTH(RAM);
}
