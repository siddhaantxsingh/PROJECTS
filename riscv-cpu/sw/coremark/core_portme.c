/* CoreMark port for rv_core. Time is measured in core clock cycles (mcycle);
 * EE_TICKS_PER_SEC is a nominal 1 MHz so "secs" are megacycles. The score
 * reported by docs/benchmarks.md is iterations per megacycle (CoreMark/MHz). */
#include <stdarg.h>
#include "coremark.h"
#include "../lib/rt.h"

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;

#define EE_TICKS_PER_SEC 1000000u
static CORE_TICKS start_time_val, stop_time_val;

void start_time(void) { start_time_val = rt_cycles(); }
void stop_time(void) { stop_time_val = rt_cycles(); }
CORE_TICKS get_time(void) { return stop_time_val - start_time_val; }
secs_ret time_in_secs(CORE_TICKS ticks) { return ticks / EE_TICKS_PER_SEC; }

ee_u32 default_num_contexts = 1;

void portable_init(core_portable* p, int* argc, char* argv[]) {
    (void)argc; (void)argv;
    p->portable_id = 1;
}
void portable_fini(core_portable* p) {
    p->portable_id = 0;
    ee_printf("CoreMark ticks (cycles): %lu\n", (unsigned long)get_time());
}

int ee_printf(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    rt_vprintf(fmt, ap);
    va_end(ap);
    return 0;
}
