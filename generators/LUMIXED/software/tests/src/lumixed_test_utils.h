#ifndef LUMIXED_TEST_UTILS_H
#define LUMIXED_TEST_UTILS_H

#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <string.h>
#include <math.h>

// Test-only implementation for one translation unit. Include after defining
// test format/geometry macros, scalar types, runtime parameters and LUMIXEDParams,
// and after including lumixed_api.h. Configuration remains in the test .c file.

// Optional quiet logging. The printf alias is selected by the test .c file.
#if defined(LUMIXED_QUIET_TEST) && LUMIXED_QUIET_TEST
static int lumixed_test_printf(const char *fmt, ...) {
    if (strncmp(fmt, "PASS", 4) && strncmp(fmt, "FAIL", 4) &&
        strncmp(fmt, "Exact matches:", 14) && strncmp(fmt, "Mismatch", 8) &&
        strncmp(fmt, "HW_CYCLES ", 10)) return 0;
    va_list args;
    va_start(args, fmt);
    int result = vprintf(fmt, args);
    va_end(args);
    return result;
}
#endif

// Exact software reference for unscaled FP16 activations.
#if LUMIXED_FP_ACTIVATIONS && LUMIXED_FP_BITS == 16
// Exact sum in units of 2^-24, rounded once to binary32, nearest/ties-to-even.
// Cin <= 65536 and signed W8/W4/W2 fit this signed 64-bit software reference.
static uint32_t lumixed_fixed24_to_fp32(int64_t value) {
    if (value == 0) return 0;
    uint32_t sign = value < 0 ? 0x80000000u : 0;
    uint64_t mag = value < 0 ? (uint64_t)(-(value + 1)) + 1 : (uint64_t)value;
    int top = 63 - __builtin_clzll(mag);
    uint64_t significand;
    if (top > 23) {
        int drop = top - 23;
        significand = mag >> drop;
        uint64_t remainder = mag & ((UINT64_C(1) << drop) - 1);
        uint64_t midpoint = UINT64_C(1) << (drop - 1);
        if (remainder > midpoint || (remainder == midpoint && (significand & 1)))
            ++significand;
    } else {
        significand = mag << (23 - top);
    }
    uint32_t exponent = (uint32_t)(top + 103); // top - 24 + binary32 bias 127
    if (significand == (UINT64_C(1) << 24)) {
        significand >>= 1;
        ++exponent;
    }
    return sign | (exponent << 23) | ((uint32_t)significand & 0x7fffffu);
}

static uint32_t lumixed_fp16_dot(const input_t *x, const weight_t *w, int n, int column) {
    int64_t exact = 0;
    unsigned nan = 0, pos_inf = 0, neg_inf = 0;
    for (int j = 0; j < n; ++j) {
        int index = column * n + j;
        unsigned code = ((uint8_t)w[index * W_BITS / 8] >> ((index % (8 / W_BITS)) * W_BITS)) & ((1u << W_BITS) - 1);
        int weight = (code & (1u << (W_BITS - 1))) ? (int)code - (1 << W_BITS) : (int)code;
        unsigned raw = x[j], exponent = (raw >> 10) & 31u, fraction = raw & 1023u;
        if (exponent == 31) {
            unsigned negative = ((raw >> 15) != 0) ^ (weight < 0);
            if (fraction || weight == 0) nan = 1;
            else if (negative) neg_inf = 1;
            else pos_inf = 1;
        } else {
            uint64_t magnitude = exponent ? (uint64_t)(1024u | fraction) << (exponent - 1) : fraction;
            int64_t activation = (raw & 0x8000u) ? -(int64_t)magnitude : (int64_t)magnitude;
            exact += activation * weight;
        }
    }
    if (nan || (pos_inf && neg_inf)) return 0x7fc00000u;
    if (pos_inf) return 0x7f800000u;
    if (neg_inf) return 0xff800000u;
    return lumixed_fixed24_to_fp32(exact);
}
#endif

// Test delay, matrix display, data generation and result comparison.
void delay(int milli_seconds)
{
    clock_t start_time = clock();
    while (clock() < start_time + milli_seconds);
}

void printMatrices_in(LUMIXEDParams p, input_t *inputMatrix) {
    // Printing Input Matrix
    #if IN_BITS == 32 || IN_BITS == 16 || IN_BITS == 8
        printf("Input Matrix (%d x %d):\n", p.Rin, p.Cin);
        for (int i = 0; i < p.Rin; i++) {
            for (int j = 0; j < p.Cin; j++) {
                int index = i * p.Cin + j;
                #if LUMIXED_FP_ACTIVATIONS && LUMIXED_FP_BITS == 32
                printf("0x%08x ", (unsigned)inputMatrix[index]);
                #elif LUMIXED_FP_ACTIVATIONS
                printf("0x%04x ", (unsigned)inputMatrix[index]);
                #else
                printf("%d ", inputMatrix[index]);
                #endif
            }
            printf("\n");  // Newline after each row
        }
    #elif IN_BITS == 4
        int total_elements = p.Rin * p.Cin;
        for (int i = 0; i < total_elements / 2; i++) {
            int8_t packed = inputMatrix[i];  // Contains two packed INT4 values.

            int8_t high = (packed >> 4) & 0x0F;
            int8_t low  = packed & 0x0F;

            if (high & 0x08) high |= 0xF0;
            if (low & 0x08)  low  |= 0xF0;

            printf("%d ", low);
            printf("%d ", high);

            if ((i * 2 + 2) % p.Cin == 0) printf("\n");
        }
    #elif IN_BITS == 2
        printf("Input Matrix (%d x %d):\n", p.Rin, p.Cin);
        int total_elements = p.Rin * p.Cin;

        for (int i = 0; i < total_elements / 4; i++) {
            int8_t packed = inputMatrix[i];  // Contains four packed INT2 values.

            int8_t v0 = (packed >> 0) & 0x03;
            int8_t v1 = (packed >> 2) & 0x03;
            int8_t v2 = (packed >> 4) & 0x03;
            int8_t v3 = (packed >> 6) & 0x03;

            if (v0 & 0x02) v0 |= 0xFC;
            if (v1 & 0x02) v1 |= 0xFC;
            if (v2 & 0x02) v2 |= 0xFC;
            if (v3 & 0x02) v3 |= 0xFC;

            printf("%d ", v0);
            printf("%d ", v1);
            printf("%d ", v2);
            printf("%d ", v3);

            if (((i * 4 + 4) % p.Cin) == 0)  printf("\n");
        }
    #endif

}

void printMatrices_w(LUMIXEDParams p, weight_t *weightMatrix) {
    printf("\nWeight Matrix (%d x %d):\n", p.Cin, p.Cout);
    for (int f = 0; f < 1; f++) {
        printf("Filter %d\n", f);
        for (int c = 0; c < p.Cin; c++) {
            for (int w = 0; w < p.Cout; w++) {
                int logical_index = f * p.Cin * p.Cout + w * p.Cin + c;

                #if W_BITS == 8
                    int8_t val = weightMatrix[logical_index];
                    printf("%d ", val);

                #elif W_BITS == 4
                    // 2 weights per byte
                    int packed_index = logical_index / 2;
                    int8_t packed = weightMatrix[packed_index];
                    int shift = (logical_index % 2) * 4;
                    int8_t val = (packed >> shift) & 0x0F;
                    // Sign-extend 4-bit
                    if (val & 0x08) val |= 0xF0;
                    printf("%d ", val);

                #elif W_BITS == 2
                    // 4 weights per byte
                    int packed_index = logical_index / 4;
                    int8_t packed = weightMatrix[packed_index];
                    int shift = (logical_index % 4) * 2;
                    int8_t val = (packed >> shift) & 0x03;
                    // Sign-extend 2-bit
                    if (val & 0x02) val |= 0xFC;
                    printf("%d ", val);
                #endif
            }
            printf("\n");
        }
        printf("\n");
    }
}

void printMatrices_out(LUMIXEDParams p, output_t *outputMatrix_SW, output_t *outputMatrix_HW) {
    // Printing Output Matrix SW
    printf("\nReference output matrix\n");
    for (int f = 0; f < 1; f++) {
        printf("Filter %d\n", f);
        for (int r = 0; r < p.Rin; r++) {
            for (int c = 0; c < p.Cout; c++) {
                int index = f * p.Rin * p.Cout + r * p.Cout + c;
                #if LUMIXED_FP_ACTIVATIONS
                    printf("0x%08x ", outputMatrix_SW[index]);
                #else
                    printf("%d ", outputMatrix_SW[index]);
                #endif

            }
            printf("\n");  // Newline after each row in Rin dimension
        }
        printf("\n");  // Newline after each filter
    }

    // Printing Output Matrix HW
    printf("\nHardware output matrix (FP mode: raw FP32 bits):\n");
    for (int f = 0; f < 1; f++) {
        for (int r = 0; r < p.Rin; r++) {
            for (int c = 0; c < p.Cout; c++) {
                int index = f * p.Rin * p.Cout + r * p.Cout + c;
                #if LUMIXED_FP_ACTIVATIONS
                    printf("0x%08x ", outputMatrix_HW[index]);
                #else
                    printf("%d ", outputMatrix_HW[index]);
                #endif
            }
            printf("\n");  // Newline after each row in Rin dimension
        }
        printf("\n");  // Newline after each filter
    }
}

void generate_random_matrix_in(input_t *matrix, int size) {
    // Seed the random number generator to get different values on each run
    srand(LUMIXED_TEST_SEED);

    #if LUMIXED_FP_ACTIVATIONS && LUMIXED_FP_BITS == 32
        for (int i = 0; i < size; ++i) {
            uint32_t bits = (uint32_t)rand();
            bits |= ((uint32_t)rand() & 1u) << 31;
            if ((bits & 0x7f800000u) == 0x7f800000u) bits ^= 0x00800000u;
            matrix[i] = LUMIXED_TEST_PATTERN == 1 ? 0xff7fffffu : bits;
        }
    #elif LUMIXED_FP_ACTIVATIONS
        for (int i = 0; i < size; ++i) {
            uint16_t bits = (uint16_t)rand();
            if ((bits & 0x7c00u) == 0x7c00u) bits ^= 0x0400u; // Random finite FP16.
            matrix[i] = LUMIXED_TEST_PATTERN == 1 ? 0xfbffu : bits;
        }
    #elif IN_BITS == 16 || IN_BITS == 8
        for (int i = 0; i < size; ++i) {
            matrix[i] = LUMIXED_TEST_PATTERN == 1 ? min_in : min_in + rand() % (max_in - min_in + 1);
        }

    #elif IN_BITS == 4
        for (int i = 0; i < size / 2; ++i) {
            int8_t packed = 0;
            for (int j = 0; j < 2; j++) {
                int8_t val = min_in + rand() % (max_in - min_in + 1);  // signed 4-bit: [-8, 7]
                val &= 0x0F;  // keep only 4 bits
                packed |= (val << (j * 4));
            }
            matrix[i] = packed;
        }

    #elif IN_BITS == 2
        for (int i = 0; i < size / 4; ++i) {
            int8_t packed = 0;
            for (int j = 0; j < 4; j++) {
                int8_t val = min_in + rand() % (max_in - min_in + 1);  // signed 2-bit: [-2, 1]
                val &= 0x03;  // keep only 2 bits
                packed |= (val << (j * 2));
            }
            matrix[i] = packed;
        }
    #endif
}

void generate_random_matrix_w(LumixedWeightTensor *matrix, int size, int filters) {
    srand(LUMIXED_TEST_SEED);  // Seed

    #if W_BITS == 8
    for (int f = 0; f < filters; f++) {
        for (int i = 0; i < size; ++i) {
            matrix[f].q[i] = LUMIXED_TEST_PATTERN == 2 ? min_w + rand() % (max_w - min_w + 1) : i + 1;
        }
    }

    #elif W_BITS == 4
    // 2 values per byte
    for (int f = 0; f < filters; f++) {
        for (int i = 0; i < size; i += 2) {
            int8_t val1 =   min_w + rand() % (max_w - min_w + 1);
            int8_t val2 =  min_w + rand() % (max_w - min_w + 1);
            val1 &= 0x0F;  // keep lower 4 bits
            val2 &= 0x0F;
            matrix[f].q[i / 2] = (val2 << 4) | val1;
        }
    }

    #elif W_BITS == 2
    // 4 values per byte
    for (int f = 0; f < filters; f++) {
        for (int i = 0; i < size; i += 4) {
            int8_t vals[4];
            for (int j = 0; j < 4; ++j) {
                vals[j] = min_w + rand() % (max_w - min_w + 1);
                vals[j] &= 0x03;  // keep lower 2 bits
            }
            matrix[f].q[i / 4] =  (vals[3] << 6) | (vals[2] << 4) | (vals[1] << 2) | vals[0];
        }
    }
    #endif
}

void generate_random_float_matrix(float *matrix, int size, float min_val, float max_val) {
    srand(LUMIXED_TEST_SEED); // Seed RNG

    for (int i = 0; i < size; ++i) {
        float rand_frac = (float)rand() / RAND_MAX; // [0, 1)
        matrix[i] = min_val + rand_frac * (max_val - min_val); // [min_val, max_val)
    }
}

int compare_matrices(output_t* outputMatrix_HW, output_t* outputMatrix_SW,
                    int num_filters, int Rin, int Cout
                     #if FPU == 1
                        ,float tolerance
                    #endif

                    )
    {
    int correct = 1;
    int total_elements = num_filters * Rin * Cout;
    int exact_matches = 0;
    int tolerant_matches = 0;
    int mismatch_count = 0;

    #if FPU == 1
    // Error statistics
    float max_rel_error = 0.0f;
    float sum_rel_error = 0.0f;
    float max_abs_error = 0.0f;
    #endif

        for (int r = 0; r < Rin; r++) {
            for (int c = 0; c < Cout; c++) {
                int index = 0 * Rin * Cout + r * Cout + c;
                output_t hw = outputMatrix_HW[index];
                output_t sw = outputMatrix_SW[index];

                // Count exact matches
                if (hw == sw) {
                    exact_matches++;
                    tolerant_matches++;
                    continue;
                }

                if (mismatch_count++ < 8) {
                    #if LUMIXED_FP_ACTIVATIONS
                    printf("Mismatch [%d,%d]: HW=0x%08x SW=0x%08x (FP32 bits)\n", r, c, hw, sw);
                    #else
                    printf("Mismatch [%d,%d]: HW=%d SW=%d\n", r, c, hw, sw);
                    #endif
                }

                #if FPU == 1
                // Calculate errors
                float abs_error = fabsf(hw - sw);
                float rel_error = abs_error / fmaxf(fabsf(sw), 1e-9f);

                // Update statistics
                max_abs_error = fmaxf(max_abs_error, abs_error);
                max_rel_error = fmaxf(max_rel_error, rel_error);
                sum_rel_error += rel_error;

                // Check against tolerance
                if (abs_error <= tolerance) {
                    tolerant_matches++;
                } else {
                    correct = 0;
                }
                #endif

            }
    }

     if (exact_matches != total_elements) {
        correct = 0;
    }

    #if FPU == 1
    // Calculate matching statistics
    float exact_match_percent = 100.0f * exact_matches / total_elements;
    float tolerant_match_percent = 100.0f * tolerant_matches / total_elements;
    float avg_rel_error = sum_rel_error / total_elements;
    #endif

    // Print comprehensive report
    printf("Verification against reference:\n");
    printf("----------------\n");
    #if FPU == 1
    printf("Exact matches:    %d/%d (%.2f%%)\n", exact_matches, total_elements, exact_match_percent);
    #elif FPU == 0
    printf("Exact matches:    %d/%d \n", exact_matches, total_elements);
    #endif

    if (correct) {
        #if FPU == 1
        printf("PASS (All elements match exactly)\n");
        #elif FPU == 0
        printf("PASS (All elements match exactly)\n");
        #endif
    } else {
        printf("FAIL (%d elements differ from reference)\n", total_elements - exact_matches);
    }

    return correct;
}

int min(int a, int b) {
    if (a < b) {
        return a;
    } else {
        return b;
    }
}

void transpose_weightMatrix(weight_t *c, int Cin, int Cout) {
    #if W_BITS == 8
    weight_t temp[Cin * Cout];

    for (int i = 0; i < Cin; ++i) {
        for (int j = 0; j < Cout; ++j) {
            temp[j * Cin + i] = c[i * Cout + j];
        }
    }

    memcpy(c, temp, sizeof(weight_t) * Cin * Cout);

    #elif W_BITS == 4
    // 2 values per byte: Cin * Cout values require (Cin * Cout + 1) / 2 bytes
    uint8_t temp[(Cin * Cout + 1) / 2];
    memset(temp, 0, sizeof(temp));

    for (int i = 0; i < Cin; ++i) {
        for (int j = 0; j < Cout; ++j) {
            int in_index = i * Cout + j;
            int in_byte = in_index / 2;
            uint8_t in_val = (in_index % 2 == 0) ? (c[in_byte] & 0x0F) : ((c[in_byte] >> 4) & 0x0F);

            int out_index = j * Cin + i;
            int out_byte = out_index / 2;
            if (out_index % 2 == 0) {
                temp[out_byte] = (temp[out_byte] & 0xF0) | (in_val & 0x0F);
            } else {
                temp[out_byte] = (temp[out_byte] & 0x0F) | ((in_val & 0x0F) << 4);
            }
        }
    }

    memcpy(c, temp, sizeof(temp));

    #elif W_BITS == 2
    // 4 values per byte: Cin * Cout values require (Cin * Cout + 3) / 4 bytes
    uint8_t temp[(Cin * Cout + 3) / 4];
    memset(temp, 0, sizeof(temp));

    for (int i = 0; i < Cin; ++i) {
        for (int j = 0; j < Cout; ++j) {
            int in_index = i * Cout + j;
            int in_byte = in_index / 4;
            int in_shift = (in_index % 4) * 2;
            uint8_t in_val = (c[in_byte] >> in_shift) & 0x03;

            int out_index = j * Cin + i;
            int out_byte = out_index / 4;
            int out_shift = (out_index % 4) * 2;

            temp[out_byte] &= ~(0x03 << out_shift);          // clear
            temp[out_byte] |= (in_val & 0x03) << out_shift;  // set
        }
    }

    memcpy(c, temp, sizeof(temp));

    #endif
}

#if !(LUMIXED_FP_ACTIVATIONS && LUMIXED_FP_BITS == 32)
// FP32 tests use the independent Python reference.
void SW_Mat_Mul(
    int num_filters,      // Number of filters (f)
    int Rin,              // Rows in input matrix (i)
    int Cout,             // Columns in output matrix (j)
    int Cin,              // Channels in input matrix (k)
    input_t *inputMatrix, // Input matrix
    weight_t *weightMatrix, // Transposed weight matrix
    #if LUMIXED_FP_ACTIVATIONS
    output_t *outputMatrix_SW // Raw FP32 output bits
    #else
    int32_t *outputMatrix_SW // Original integer output matrix
    #endif
) {
#if LUMIXED_FP_ACTIVATIONS
    for (int f = 0; f < num_filters; ++f)
        for (int i = 0; i < Rin; ++i)
            for (int j = 0; j < Cout; ++j)
                outputMatrix_SW[f * Rin * Cout + i * Cout + j] = lumixed_fp16_dot(inputMatrix + i * Cin, weightMatrix, Cin, j);
#else
    for (int f = 0; f < num_filters; f++) {
        for (int i = 0; i < Rin; i++) {
            for (int j = 0; j < Cout; j++) {
                // Initialize output to 0 for this f, i, j
                outputMatrix_SW[f * Rin * Cout + i * Cout + j] = 0;

                // Perform matrix multiplication: Multiply and accumulate
                for (int k = 0; k < Cin; k++) {
                    // Access inputMatrix and weightMatrix directly using the calculated 1D index
                    int inputIndex = i * Cin + k; // Input matrix index: (i, k)
                    int weightIndex = j * Cin + k; // Transposed weight matrix index: (j, k) since B^T

                    // Multiply and accumulate
                    outputMatrix_SW[f * Rin * Cout + i * Cout + j] += inputMatrix[inputIndex] * weightMatrix[weightIndex];
                }
            }
        }
    }
#endif
}

void matmul_sw_uniform(
    output_t* xout,                // output: size d * m
    LumixedActivationTensor* x,      // input: size n * m (quantized)
    LumixedWeightTensor* w,     // weights: size d * n (quantized)
    int n,                        // input vector length
    int d,                        // output vector length
    int m                         // number of input vectors
) {
#if LUMIXED_FP_ACTIVATIONS
    for (int row = 0; row < m; ++row)
        for (int column = 0; column < d; ++column)
            xout[row * d + column] = lumixed_fp16_dot(x->q + row * n, w->q, n, column);
#else
    int32_t sum;

    for (int vec_i = 0; vec_i < m; vec_i++) {           // For each input vector
        for (int i = 0; i < d; i++) {                    // For each output dimension
            sum = 0;
            for (int j = 0; j < n; j++) {                // For each element in input vector and weights
                int x_val, w_val;

                // ----------------------------
                // UNPACK input x->q for vec_i-th vector and j-th element
                // ----------------------------
                #if IN_BITS == 32 || IN_BITS == 16 || IN_BITS == 8
                    // Assuming x->q is stored row-major: input vectors are contiguous
                    x_val = x->q[vec_i * n + j];
                #elif IN_BITS == 4
                    {
                        int idx = vec_i * n + j;
                        int byte_idx = idx / 2;
                        int shift = (idx % 2) * 4;
                        x_val = (x->q[byte_idx] >> shift) & 0x0F;
                        if (x_val & 0x08) x_val |= 0xFFFFFFF0;
                    }
                #elif IN_BITS == 2
                    {
                        int idx = vec_i * n + j;
                        int byte_idx = idx / 4;
                        int shift = (idx % 4) * 2;
                        x_val = (x->q[byte_idx] >> shift) & 0x03;
                        if (x_val & 0x02) x_val |= 0xFFFFFFFC;
                    }
                #endif

                // ----------------------------
                // UNPACK weight w->q[i * n + j]
                // ----------------------------
                #if W_BITS == 16 || W_BITS == 8
                    w_val = w->q[i * n + j];
                #elif W_BITS == 4
                    {
                        int idx = i * n + j;
                        int byte_idx = idx / 2;
                        int shift = (idx % 2) * 4;
                        w_val = (w->q[byte_idx] >> shift) & 0x0F;
                        if (w_val & 0x08) w_val |= 0xFFFFFFF0;
                    }
                #elif W_BITS == 2
                    {
                        int idx = i * n + j;
                        int byte_idx = idx / 4;
                        int shift = (idx % 4) * 2;
                        w_val = (w->q[byte_idx] >> shift) & 0x03;
                        if (w_val & 0x02) w_val |= 0xFFFFFFFC;
                    }
                #endif

                sum += (int32_t)x_val * (int32_t)w_val;
            }

                xout[vec_i * d + i] = sum;
        }
    }
#endif
}

#endif // C reference for integer and FP16 modes

// Display CPU timing. Reference mode determines which timings are meaningful.
static inline void lumixed_print_timing(unsigned long sw_cycles, unsigned long hw_cycles) {
    #ifdef LUMIXED_PREGENERATED_TEST
    printf("Hardware run: %lu CPU cycles (setup and wait included)\n", hw_cycles);
    #else
    if(!fast_mode){
        #if FPU == 1
        double speedup = (double)sw_cycles / hw_cycles;
        #endif

        printf("C reference: %lu CPU cycles\n", sw_cycles);
        printf("Hardware run: %lu CPU cycles (setup and wait included)\n", hw_cycles);
        #if FPU == 1
        printf("C-reference / hardware-run cycle ratio: %.2f\n", speedup);
        #endif

    }

    if(fast_mode){
        printf("Hardware run: %lu CPU cycles (setup and wait included)\n", hw_cycles);
    }

    #endif /* LUMIXED_PREGENERATED_TEST */

}

// Consume counters once, in the RTL-defined order, and report their total.
static inline void lumixed_print_hw_counters(void) {
    #if PC_COUNTERS == 1 && FPU == 1
        unsigned long load_x, Generate_Brams, load_w, Select_and_Accumulate, store_o,load_w_and_select;

        load_x = lumixed_read_counter(LUMIXED_COUNTER_LOAD_X);
        Generate_Brams = lumixed_read_counter(LUMIXED_COUNTER_GENERATE);
        load_w = lumixed_read_counter(LUMIXED_COUNTER_LOAD_W);
        Select_and_Accumulate = lumixed_read_counter(LUMIXED_COUNTER_SELECT);
        store_o  = lumixed_read_counter(LUMIXED_COUNTER_STORE_O);
        load_w_and_select = lumixed_read_counter(LUMIXED_COUNTER_LOAD_W_AND_SELECT);

        // Calculate total
        unsigned long total_cycles = load_x + Generate_Brams +  load_w_and_select  + store_o;

        // Print actual cycles
        printf("Accelerator active-cycle counters (overlapped stages are not added twice):\n");
        printf("Stage                  | Cycles     | Percentage\n");
        printf("-----------------------|------------|-----------\n");
        printf("Load X                 | %10lu | %6.2f%%\n", load_x, 100.0 * load_x / total_cycles);
        printf("Generate BRAMs         | %10lu | %6.2f%%\n", Generate_Brams, 100.0 * Generate_Brams / total_cycles);
        printf("Load W and Select      | %10lu | %6.2f%%\n", load_w_and_select, 100.0 * load_w_and_select / total_cycles);
        printf("Store O                | %10lu | %6.2f%%\n", store_o, 100.0 * store_o / total_cycles);
        printf("-----------------------|------------|-----------\n");

        printf("Load W  Alone          | %10lu | %6.2f%%\n", load_w, 100.0 * load_w / total_cycles);
        printf("Select  Alone          | %10lu | %6.2f%%\n", Select_and_Accumulate, 100.0 * Select_and_Accumulate / total_cycles);

        // Print total
        printf("-----------------------|------------|-----------\n");
        printf("Total                  | %10lu | 100.00%%\n", total_cycles);

    #elif PC_COUNTERS == 1 && FPU == 0

        unsigned long load_x, Generate_Brams, load_w, Select_and_Accumulate, store_o, load_w_and_select;

        load_x = lumixed_read_counter(LUMIXED_COUNTER_LOAD_X);
        Generate_Brams = lumixed_read_counter(LUMIXED_COUNTER_GENERATE);
        load_w = lumixed_read_counter(LUMIXED_COUNTER_LOAD_W);
        Select_and_Accumulate = lumixed_read_counter(LUMIXED_COUNTER_SELECT);
        store_o = lumixed_read_counter(LUMIXED_COUNTER_STORE_O);
        load_w_and_select = lumixed_read_counter(LUMIXED_COUNTER_LOAD_W_AND_SELECT);

        // Calculate total
        unsigned long total_cycles = load_x + Generate_Brams + load_w_and_select + store_o;

        // Print raw cycle counts (no percentages)
        printf("Accelerator active-cycle counters (overlapped stages are not added twice):\n");
        printf("Stage                  | Cycles\n");
        printf("-----------------------|------------\n");
        printf("Load X                 | %10lu\n", load_x);
        printf("Generate BRAMs         | %10lu\n", Generate_Brams);
        printf("Load W and Select      | %10lu\n", load_w_and_select);
        printf("Store O                | %10lu\n", store_o);
        printf("-----------------------|------------\n");
        printf("Load W  Alone          | %10lu\n", load_w);
        printf("Select  Alone          | %10lu\n", Select_and_Accumulate);
        printf("-----------------------|------------\n");
        printf("Total                  | %10lu\n", total_cycles);

    #endif

    #if PC_COUNTERS == 1
    printf("HW_CYCLES total=%lu load_x=%lu generate_brams=%lu load_w=%lu select_accumulate=%lu store_o=%lu load_w_and_select=%lu\n",
           total_cycles, load_x, Generate_Brams, load_w, Select_and_Accumulate, store_o, load_w_and_select);
    #endif

}

#endif // LUMIXED_TEST_UTILS_H
