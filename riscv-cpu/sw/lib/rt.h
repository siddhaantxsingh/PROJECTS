#pragma once
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

void rt_putc(char c);
int rt_printf(const char* fmt, ...);
int rt_vprintf(const char* fmt, va_list ap);
static inline uint32_t rt_cycles(void) { uint32_t v; __asm__ volatile("csrr %0, mcycle" : "=r"(v)); return v; }
static inline uint32_t rt_instret(void) { uint32_t v; __asm__ volatile("csrr %0, minstret" : "=r"(v)); return v; }
void exit(int code) __attribute__((noreturn));
