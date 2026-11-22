#ifndef GEMMINI_EDITED_FP32_H
#define GEMMINI_EDITED_FP32_H

/* Include using the matching FP32/W8 configuration parameters as
 * include/gemmini_params.h in a private software build. No runtime weight mode.
 * Activations are raw IEEE binary32; outputs are raw IEEE binary32.
 * Weights are two's-complement, low field first, with each K row byte aligned.
 * Scaling/bias and transpose are intentionally outside this matmul interface.
 */
extern unsigned long gemmini_setup_cycles;
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include "include/gemmini_params.h"
#include "rocc-software/src/xcustom.h"
#ifndef GEMMINI_FP_INT
#error "Use the generated parameters for the mixed FP-INT Gemmini build"
#endif

/* The existing Gemmini RoCC ISA, restricted to plain WS matmul. This small
 * interface avoids pulling in gemmini.h's integer-only CPU softmax fallback,
 * which cannot compile with an FP32 acc_t. No new instruction is introduced.
 * Function codes/bitfields match gemmini-rocc-tests/include/gemmini.h.
 */
#define GEMMINI_FPINT_CMD(a, b, funct) \
    ROCC_INSTRUCTION_0_R_R(XCUSTOM_ACC, a, b, funct)

static inline void gemmini_fpint_flush(void) {
    GEMMINI_FPINT_CMD(0, 0, 7);
}

static inline void gemmini_fpint_setup(size_t k, size_t n, size_t w_stride) {
    /* CONFIG_EX: FP32 identity scale, A/C stride=1, WS, no activation. */
    GEMMINI_FPINT_CMD((UINT64_C(0x3f800000)<<32) | (UINT64_C(1)<<16) | 4,
                     UINT64_C(1)<<48, 0);
    /* CONFIG_ST: full FP32 output row stride; identity scale. */
    GEMMINI_FPINT_CMD(2, (UINT64_C(0x3f800000)<<32) | (n*4), 0);
    /* CONFIG_LD: raw FP32 identity 0x3f800000, block stride DIM, one repeat.
     * LOAD2/state 1 is the packed-weight stream. */
    const uint64_t ld=(UINT64_C(0x3f800000)<<32) | ((uint64_t)DIM<<16) | 0x101;
    GEMMINI_FPINT_CMD(ld, k*4, 0);
    GEMMINI_FPINT_CMD(ld | 8, w_stride, 0);
    GEMMINI_FPINT_CMD(ld | 16, n*4, 0);
}

static inline void gemmini_fpint_ws_loop(size_t i, size_t j, size_t k,
        size_t pi, size_t pj, size_t pk, const uint32_t *a,
        const uint8_t *b, uint32_t *c, size_t a_stride, size_t n, int accumulate) {
    GEMMINI_FPINT_CMD(((uint64_t)pk<<32) | ((uint64_t)pj<<16) | pi,
                     ((uint64_t)k<<32) | ((uint64_t)j<<16) | i, 9);
    GEMMINI_FPINT_CMD(a, b, 10);
    GEMMINI_FPINT_CMD(0, c, 11);
    /* B_stride is logical columns, as consumed by the mixed RTL loader. */
    GEMMINI_FPINT_CMD(a_stride, n, 12);
    GEMMINI_FPINT_CMD(n, n, 13);
    GEMMINI_FPINT_CMD(2 | (uint64_t)accumulate, 0, 8);
}

static inline size_t gemmini_fpint_weight_row_bytes(size_t cols) {
    return (cols * GEMMINI_WEIGHT_BITS + 7) / 8;
}

static inline size_t gemmini_fpint_min(size_t a, size_t b) { return a<b ? a:b; }

/* All products and partial-sum accumulation are performed by Gemmini RTL.
 * Uses the existing WS hardware loop with its double-buffering protocol:
 * C=NULL on intermediate K chunks retains the HW accumulator; only the final
 * chunk stores FP32 output. There is no CPU multiplication or output reduction.
 */
static inline void gemmini_fpint_matmul_tiled(size_t r, size_t k, size_t n,
        const uint32_t *x, const uint8_t *packed_w, uint32_t *out,
        size_t max_k_tiles) {
    assert(r && k && n);
    const size_t ri=(r+DIM-1)/DIM, nj=(n+DIM-1)/DIM, kk=(k+DIM-1)/DIM;
    const size_t sp_tiles = (BANK_NUM * BANK_ROWS / 2) / DIM;
    const size_t acc_tiles = (ACC_ROWS / 2) / DIM;
    size_t ti=gemmini_fpint_min(ri, 4);
    size_t tj=gemmini_fpint_min(nj, MAX_BYTES*8/(DIM*GEMMINI_WEIGHT_BITS));
    tj=gemmini_fpint_min(tj, acc_tiles/ti);
    assert(ti && tj && sp_tiles>ti+tj);
    size_t tk=gemmini_fpint_min(kk, sp_tiles/(ti+tj));
    if (max_k_tiles) tk=gemmini_fpint_min(tk,max_k_tiles);
    assert(tk);
    const size_t w_stride=gemmini_fpint_weight_row_bytes(n);

    asm volatile("fence" ::: "memory");
    unsigned long setup_start; asm volatile("rdcycle %0" : "=r"(setup_start));
    gemmini_fpint_setup(k, n, w_stride);
    unsigned long setup_end; asm volatile("rdcycle %0" : "=r"(setup_end));
    gemmini_setup_cycles=setup_end-setup_start;
    for (size_t i=0; i<ri; i+=ti) {
        const size_t ii=gemmini_fpint_min(ti,ri-i);
        for (size_t j=0; j<nj; j+=tj) {
            const size_t jj=gemmini_fpint_min(tj,nj-j);
            for (size_t z=0; z<kk; z+=tk) {
                const size_t zz=gemmini_fpint_min(tk,kk-z);
                const size_t pi=i+ii==ri ? ri*DIM-r : 0;
                const size_t pj=j+jj==nj ? nj*DIM-n : 0;
                const size_t pk=z+zz==kk ? kk*DIM-k : 0;
                const uint32_t *a=x+i*DIM*k+z*DIM;
                const uint8_t *b=packed_w+z*DIM*w_stride+j*DIM*GEMMINI_WEIGHT_BITS/8;
                uint32_t *c=z+zz==kk ? out+i*DIM*n+j*DIM : NULL;
                gemmini_fpint_ws_loop(ii,jj,zz,pi,pj,pk,a,b,c,k,n,z!=0);
            }
        }
    }
    asm volatile("fence" ::: "memory");
}

/* Zero tile limit uses the full scratchpad budget. A smaller software limit can
 * exercise/reuse the same hardware accumulator across shorter K chunks. */
static inline void gemmini_fpint_matmul(size_t r, size_t k, size_t n,
        const uint32_t *x, const uint8_t *packed_w, uint32_t *out) {
    gemmini_fpint_matmul_tiled(r,k,n,x,packed_w,out,0);
}
#endif
