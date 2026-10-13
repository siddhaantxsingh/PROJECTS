// Tiny freestanding runtime: console output, printf subset, mem/str helpers.
#include "rt.h"

#define CONSOLE ((volatile uint8_t*)0x10000000u)

void rt_putc(char c) { *CONSOLE = (uint8_t)c; }

static void put_num(unsigned long v, unsigned base, int upper, int width, char pad, int neg) {
    char buf[24];
    int n = 0;
    const char* digits = upper ? "0123456789ABCDEF" : "0123456789abcdef";
    do { buf[n++] = digits[v % base]; v /= base; } while (v);
    if (neg) {
        if (pad == '0') { rt_putc('-'); width--; } else { buf[n++] = '-'; }
    }
    while (width-- > n) rt_putc(pad);
    while (n) rt_putc(buf[--n]);
}

int rt_vprintf(const char* f, va_list ap) {
    for (; *f; f++) {
        if (*f != '%') { rt_putc(*f); continue; }
        f++;
        char pad = ' ';
        int width = 0;
        if (*f == '0') { pad = '0'; f++; }
        while (*f >= '0' && *f <= '9') width = width * 10 + (*f++ - '0');
        while (*f == 'l' || *f == 'h' || *f == 'z') f++;
        switch (*f) {
            case 'd': case 'i': { long v = va_arg(ap, long); put_num(v < 0 ? -(unsigned long)v : (unsigned long)v, 10, 0, width, pad, v < 0); break; }
            case 'u': put_num(va_arg(ap, unsigned long), 10, 0, width, pad, 0); break;
            case 'x': put_num(va_arg(ap, unsigned long), 16, 0, width, pad, 0); break;
            case 'X': put_num(va_arg(ap, unsigned long), 16, 1, width, pad, 0); break;
            case 'p': rt_putc('0'); rt_putc('x'); put_num(va_arg(ap, unsigned long), 16, 0, 8, '0', 0); break;
            case 'c': rt_putc((char)va_arg(ap, int)); break;
            case 's': { const char* s = va_arg(ap, const char*); int l = 0; while (s[l]) l++; while (width-- > l) rt_putc(' '); while (*s) rt_putc(*s++); break; }
            case '%': rt_putc('%'); break;
            default: rt_putc('%'); rt_putc(*f); break;
        }
    }
    return 0;
}

int rt_printf(const char* f, ...) {
    va_list ap;
    va_start(ap, f);
    rt_vprintf(f, ap);
    va_end(ap);
    return 0;
}

void* memcpy(void* d, const void* s, size_t n) { uint8_t* a = d; const uint8_t* b = s; while (n--) *a++ = *b++; return d; }
void* memset(void* d, int c, size_t n) { uint8_t* a = d; while (n--) *a++ = (uint8_t)c; return d; }
size_t strlen(const char* s) { size_t n = 0; while (s[n]) n++; return n; }
