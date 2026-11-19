// Bare-metal LUMIXED matrix-multiplication test.
#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdio.h>
#include <time.h>
#include <string.h>
#include <math.h>
#include "compiler.h"

// ============================================================================
// 1. TEST PARAMETERS: element precision and matrix dimensions
// ============================================================================
#define IN_BITS 32
#define W_BITS 2
#define RIN_MAX 2
#define CIN_MAX 256
#define COUT_MAX 4
#define FILTERS_MAX         1  // Number of filters.

// ============================================================================
// 2. HARDWARE GEOMETRY: must match Config.scala
// ============================================================================
#define banks_per_row 4
#define parallel_rows 1
#define table_capacity_factor 4

// ============================================================================
// 3. EXECUTION, OUTPUT AND DIAGNOSTIC OPTIONS
// ============================================================================
#define Debug               0  // Print input and output matrices.
#define PC_COUNTERS         1  // Print accelerator performance counters.
#define FPU                 0  // Whether the Rocket core supports an FPU.

// Build-time activation mode; raw IEEE bits transfer without CPU conversion.
#ifndef LUMIXED_FP_ACTIVATIONS
#define LUMIXED_FP_ACTIVATIONS 0
#endif
#ifndef LUMIXED_FP_BITS
#define LUMIXED_FP_BITS 16
#endif

// Allow runtime bank regrouping when implemented by the hardware.
#ifndef LUMIXED_FLEXIBLE_BANK_GROUPING
#define LUMIXED_FLEXIBLE_BANK_GROUPING 0
#endif

// Enable the group-scale configuration interface when supported by hardware.
#ifndef LUMIXED_GROUP_SCALE_MODE
#define LUMIXED_GROUP_SCALE_MODE 0
#endif

// Deterministic test inputs and reference generation.
#ifndef LUMIXED_TEST_SEED
#define LUMIXED_TEST_SEED 1
#endif
#ifndef LUMIXED_TEST_PATTERN
#define LUMIXED_TEST_PATTERN 0
#endif

// ============================================================================
// 4. DERIVED BUFFER SIZES: calculated from the parameters above
// ============================================================================
#define INPUT_SIZE (RIN_MAX * CIN_MAX)
#define WEIGHT_SIZE (FILTERS_MAX * CIN_MAX * COUT_MAX)

#if IN_BITS == 32 || IN_BITS == 16 || IN_BITS == 8
    #define MAX_INPUT_SIZE (RIN_MAX * CIN_MAX)
#elif IN_BITS == 4
    #define MAX_INPUT_SIZE ((RIN_MAX * CIN_MAX) / 2)
#elif IN_BITS == 2
    #define MAX_INPUT_SIZE ((RIN_MAX * CIN_MAX) / 4)
#endif

#if LUMIXED_FP_ACTIVATIONS
    #define MAX_WEIGHT_SIZE ((CIN_MAX * COUT_MAX * W_BITS + 7) / 8)
#elif W_BITS == 8
    #define MAX_WEIGHT_SIZE (CIN_MAX * COUT_MAX)
#elif W_BITS == 4
    #define MAX_WEIGHT_SIZE ((CIN_MAX * COUT_MAX + 1) / 2)
#elif W_BITS == 2
    #define MAX_WEIGHT_SIZE ((CIN_MAX * COUT_MAX + 3) / 4)
#endif

// ============================================================================
// 5. COMPILE-TIME FORMAT CHECKS
// ============================================================================
#if LUMIXED_FP_ACTIVATIONS && (IN_BITS != LUMIXED_FP_BITS || (W_BITS != 8 && W_BITS != 4 && W_BITS != 2))
#error "FP activations require IN_BITS=FP_BITS and integer W_BITS=8, 4 or 2"
#endif
#if LUMIXED_FP_ACTIVATIONS && IN_BITS == 32 && !defined(LUMIXED_PREGENERATED_TEST)
#error "FP32 exact-reference tests require FAST_VECTORS=1 (Python arbitrary-precision reference)"
#endif
#if LUMIXED_GROUP_SCALE_MODE && !LUMIXED_FP_ACTIVATIONS
#error "Group scaling requires FP16 activations"
#endif
#if LUMIXED_GROUP_SCALE_MODE && !defined(LUMIXED_PREGENERATED_TEST)
#error "Group-scaling test requires a pregenerated Python reference and scales"
#endif

// The printf wrapper follows its function definition so it cannot recurse.
#if defined(LUMIXED_QUIET_TEST) && LUMIXED_QUIET_TEST
#include <stdarg.h>
#define printf lumixed_test_printf
#endif

// ============================================================================
// 6. RUNTIME TEST/HARDWARE CONFIGURATION
// ============================================================================
bool fast_mode = false;       // Skip software/hardware result comparison.

// Runtime bank geometry (initialized from the compile-time settings).
int active_banks_per_row = banks_per_row;
int active_parallel_rows = parallel_rows;

int ACTIVATION_MAX_BITS = LUMIXED_FP_ACTIVATIONS ? LUMIXED_FP_BITS : 16;
int WEIGHTS_MAX_BITS    = 8;

// Matrix dimension limits used for test buffers.
int Rin_max  = RIN_MAX;
int Cin_max  = CIN_MAX;
int Cout_max = COUT_MAX;

////////////////////////////////////////////////////////////////////////////

#if LUMIXED_FP_ACTIVATIONS && LUMIXED_FP_BITS == 32
    typedef uint32_t input_t; // Raw IEEE binary32 activation bits.
    static const int max_in = 0;
    static const int min_in = 0;
#elif LUMIXED_FP_ACTIVATIONS
    typedef uint16_t input_t; // Raw IEEE binary16 bits, never cast to int16_t.
    static const int max_in = 65535;
    static const int min_in = 0;
#elif IN_BITS == 16
    typedef int16_t input_t;
    static const int max_in = 32767;
    static const int min_in = -32768;
#elif IN_BITS == 8
    typedef int8_t input_t;
    static const int max_in = 127;
    static const int min_in = -128;
#elif IN_BITS == 4
    typedef int8_t input_t;
    static const int max_in = 7;
    static const int min_in =  -8;
#elif IN_BITS == 2
    typedef int8_t input_t;
    static const int max_in = 1;
    static const int min_in = -2;
#endif

#if W_BITS == 8
    typedef int8_t weight_t;
    static const int max_w = 127; //127;
    static const int min_w = -128; //128;
#elif W_BITS == 4
    typedef int8_t weight_t;
    static const int max_w = 7;
    static const int min_w = -8;
#elif W_BITS == 2
    typedef int8_t weight_t;
    static const int max_w = 1;
    static const int min_w = -2;
#endif

#if LUMIXED_FP_ACTIVATIONS
    typedef uint32_t output_t; // Raw IEEE binary32 bits; works without a CPU FPU.
#else
    typedef int32_t output_t;
#endif

#ifdef LUMIXED_PREGENERATED_TEST
#include "lumixed_test_vectors.h"
#endif

// Hardware API uses the scalar types selected above and the test configuration.
#include "lumixed_api.h"

// CPU timing results recorded by lumixed_execute_matmul_timed().
unsigned long int_hw_cycles,float_hw_cycles,start,end;

// Runtime matrix parameters used by the test and its helpers.
typedef struct {
    int Rin;
    int Cin;
    int Cout;
    int XBitWidth;
    int WBitWidth;
    int x_slice;
    int num_filters;
    int parallel_rin;

} LUMIXEDParams;

// Test-only reference, packing, generation, printing and comparison helpers.
#include "lumixed_test_utils.h"

// ============================================================================
// 7. TEST FLOW: buffers -> inputs -> reference -> hardware -> verification
// ============================================================================
int main() {
    // 1. Allocate test buffers and connect tensor wrappers.
    int test_failed = 0;
    printf("LUMIXED bare-metal matmul test\n");
    printf("HW geometry: banks_per_row=%d, table_capacity_factor=%d, parallel_rows=%d (must match RTL)\n",
           banks_per_row, table_capacity_factor, parallel_rows);
    #if LUMIXED_FP_ACTIVATIONS
    printf("Format: FP%d activations, signed W%d, FP32 output\n",
           LUMIXED_FP_BITS, W_BITS);
    #else
    printf("Format: signed INT%d activations, signed W%d, INT32 output\n",
           IN_BITS, W_BITS);
    #endif
    #if LUMIXED_GROUP_SCALE_MODE
    printf("Group scaling: FP16 scales, G=%d\n", LUMIXED_VECTOR_GROUP_SIZE);
    #else
    printf("Group scaling: disabled\n");
    #endif
    #ifdef LUMIXED_PREGENERATED_TEST
    printf("Reference: pregenerated Python vectors (exact FP32 bits or integers)\n");
    #else
    printf("Reference: C calculation\n");
    #endif

    unsigned long sw_cycles, hw_cycles, start, end;

    int filters_max = 1;

    LUMIXEDParams p = {
        .num_filters = 1
    };

    LumixedActivationTensor inputMatrix;
    LumixedWeightTensor weightMatrix[filters_max];
    int COUT[filters_max];

    // static arrays for bare-metal
    static input_t inputMatrix_q[MAX_INPUT_SIZE];
    static weight_t weightMatrix_q[FILTERS_MAX][MAX_WEIGHT_SIZE];
    output_t outputMatrix_HW[filters_max][Rin_max * Cout_max];

    inputMatrix.q = inputMatrix_q;
    for (int f = 0; f < p.num_filters; f++)
        weightMatrix[f].q = weightMatrix_q[f];

    output_t outputMatrix_SW[filters_max][Rin_max * Cout_max];

    // 2. Prepare input/weight data: saved Python vectors or generated values.
    #ifdef LUMIXED_PREGENERATED_TEST
    memcpy(inputMatrix.q, lumixed_vectors_input, sizeof(lumixed_vectors_input));
    memcpy(weightMatrix[0].q, lumixed_vectors_weights, sizeof(lumixed_vectors_weights));
    #else
    printf("Inputs: generating test matrices\n");
    generate_random_matrix_in(inputMatrix.q, Rin_max * Cin_max);
    generate_random_matrix_w(weightMatrix, filters_max * Cin_max * Cout_max, p.num_filters);

    #endif /* LUMIXED_PREGENERATED_TEST */

    p.Rin  = Rin_max;
    p.Cin  = Cin_max;
    p.Cout = Cout_max;
    sw_cycles = 0;
    for (int c = 0; c < p.num_filters; c++) {
        COUT[c] = Cout_max;
    }

    #ifndef LUMIXED_PREGENERATED_TEST
    for (int f = 0; f < p.num_filters; f++) {
        transpose_weightMatrix(weightMatrix[f].q, p.Cin, COUT[f]);
    }

    #endif /* LUMIXED_PREGENERATED_TEST */

    // 3. Load the Python reference or calculate the C reference.
    #ifdef LUMIXED_PREGENERATED_TEST
    memcpy(outputMatrix_SW[0], lumixed_vectors_reference, sizeof(lumixed_vectors_reference));
    #else
    if (!fast_mode) {
        printf("Reference: computing C matmul\n");
        start = rdcycle();
        for (int f = 0; f < p.num_filters; f++) {
            matmul_sw_uniform(outputMatrix_SW[f], &inputMatrix, &weightMatrix[f], p.Cin, COUT[f], p.Rin);
        }
        end = rdcycle();
        sw_cycles = end - start;
    }

    #endif /* LUMIXED_PREGENERATED_TEST */

    // 4. Select runtime geometry and optionally display input matrices.
    p.x_slice = active_banks_per_row * table_capacity_factor;
    p.parallel_rin = active_parallel_rows;

    printf("Matmul: X[%d,%d] * W[%d,%d] -> O[%d,%d]\n",
           p.Rin, p.Cin, p.Cin, p.Cout, p.Rin, p.Cout);
    printf("Schedule: base X slice=%d, parallel rows=%d\n",
           p.x_slice, p.parallel_rin);

    #if Debug == 1
        printMatrices_in(p, inputMatrix.q);
        for (int f = 0; f < p.num_filters; f++) {
            printf("Filter %d\n", f);
            p.Cout = COUT[f];
            printMatrices_w(p, weightMatrix[f].q);

        }
    #endif

    // 5. Configure optional group scales, preload, execute and wait.
    printf("Hardware: starting matmul\n");
    start = rdcycle();
    for (int f = 0; f < p.num_filters; f++) {

        // Configure per-K-group FP16 scales before starting the matmul.
        // The accelerator DMA reads the padded, aligned array from memory.
        #if LUMIXED_GROUP_SCALE_MODE
        if (lumixed_set_group_scales(lumixed_vectors_scales, LUMIXED_VECTOR_GROUP_SIZE) != 0) {
            printf("FAIL: invalid group-scale configuration (address alignment or G)\n");
            return 1;
        }
        #endif

        lumixed_configure_matmul(p.Cin, p.Rin, COUT[f], p.x_slice, p.parallel_rin,
                   inputMatrix.q, weightMatrix[f].q, outputMatrix_HW[f]);

        lumixed_execute_matmul_timed(outputMatrix_HW[f], &inputMatrix, &weightMatrix[f],
                          p.Cin, COUT[f], p.x_slice, p.parallel_rin, Rin_max);
    }
    end = rdcycle();
    hw_cycles = end - start;

    // 6. Report timings, optionally display outputs, and verify results.
    lumixed_print_timing(sw_cycles, hw_cycles);

    #if Debug == 1
        for (int f = 0; f < p.num_filters; f++) {
            printf("Filter %d\n", f);
            p.Cout = COUT[f];
            printMatrices_out(p, outputMatrix_SW[f], outputMatrix_HW[f]);
        }
    #endif

    #if FPU == 0
        if (!fast_mode) {
            for (int f = 0; f < p.num_filters; f++) {
                printf("Filter %d\n", f);
                int result = compare_matrices(outputMatrix_HW[f], outputMatrix_SW[f], 1, p.Rin, COUT[f]);
                if (!result) test_failed = 1;
                printf("\n");
            }
        }
    #elif FPU == 1
        if (!fast_mode) {
            for (int f = 0; f < p.num_filters; f++) {
                printf("Filter %d\n", f);
                int result = compare_matrices(outputMatrix_HW[f], outputMatrix_SW[f], 1, p.Rin, COUT[f], 0.01f);
                if (!result) test_failed = 1;
                printf("\n");
            }
        }
    #endif

    // 7. Read/report accelerator counters after result checks.
    lumixed_print_hw_counters();

    return test_failed;
}
