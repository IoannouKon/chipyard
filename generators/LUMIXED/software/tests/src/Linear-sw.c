// Bare-metal LUMIXED matrix-multiplication test.
#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdio.h>
#include <time.h>
#include <string.h>
#include <math.h>
#include "compiler.h"
#include "rocc.h"

// ============================================================================
// 1. TEST PARAMETERS: element precision and matrix dimensions
// ============================================================================
#define IN_BITS            16  // Activation bit width.
#define W_BITS              4  // Signed weight bit width.
#define RIN_MAX             1  // Maximum input rows (R).
#define CIN_MAX            64  // Maximum reduction dimension (K).
#define COUT_MAX           64  // Maximum output columns (N).
#define FILTERS_MAX         1  // Number of filters.

// ============================================================================
// 2. HARDWARE GEOMETRY: must match Config.scala
// ============================================================================
#define XS                  2  // Banks per activation row.
#define YS                  1  // Parallel activation rows.
#define Mem_row_factor      8  // Product-table capacity factor.

// ============================================================================
// 3. EXECUTION, OUTPUT AND DIAGNOSTIC OPTIONS
// ============================================================================
#define Debug               0  // Print input and output matrices.
#define PC_COUNTERS         1  // Print accelerator performance counters.
#define FPU                 0  // Whether the Rocket core supports an FPU.
#define SCALE               0  // Legacy uniform integer-output scaling.

// Build script controls FP16 hardware mode; this is not a runtime selector.
#ifndef LUMIXED_FP16_ACTIVATIONS
#define LUMIXED_FP16_ACTIVATIONS 0
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

#if IN_BITS == 16 || IN_BITS == 8
    #define MAX_INPUT_SIZE (RIN_MAX * CIN_MAX)
#elif IN_BITS == 4
    #define MAX_INPUT_SIZE ((RIN_MAX * CIN_MAX) / 2)
#elif IN_BITS == 2
    #define MAX_INPUT_SIZE ((RIN_MAX * CIN_MAX) / 4)
#endif

#if LUMIXED_FP16_ACTIVATIONS
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
#if LUMIXED_FP16_ACTIVATIONS && (IN_BITS != 16 || (W_BITS != 8 && W_BITS != 4 && W_BITS != 2))
#error "FP16 activations require IN_BITS=16 and integer W_BITS=8, 4 or 2"
#endif
#if LUMIXED_FP16_ACTIVATIONS && SCALE
#error "FP16 mode already returns FP32; the integer SCALE path must be disabled"
#endif
#if LUMIXED_GROUP_SCALE_MODE && !LUMIXED_FP16_ACTIVATIONS
#error "Group scaling requires FP16 activations"
#endif
#if LUMIXED_GROUP_SCALE_MODE && !defined(LUMIXED_PREGENERATED_TEST)
#error "Group-scaling test requires a pregenerated Python reference and scales"
#endif
#if SCALE && defined(LUMIXED_PREGENERATED_TEST)
#error "Legacy uniform SCALE is not covered by the Python reference"
#endif

// The printf wrapper follows its function definition so it cannot recurse.
#if defined(LUMIXED_QUIET_TEST) && LUMIXED_QUIET_TEST
#include <stdarg.h>
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
#define printf lumixed_test_printf
#endif

// ============================================================================
// 6. RUNTIME TEST/HARDWARE CONFIGURATION
// ============================================================================
bool fast_mode = false;       // Skip software/hardware result comparison.
bool ultra_fast_mode = false; // Skip random test-vector generation.

// Runtime bank geometry (initialized from the compile-time settings).
int x_slice_max = XS;
int y_slice = YS;

// Call while idle, before preload_hw(). XS*YS is the physical lane count;
// YS is the maximum supported grouping, not the number of rows in a tail.
// The selected grouping reaches RTL through the existing set_rin command.
// A model-generated schedule supplies rows; no simulator runs on the CPU.
static inline int lumixed_select_bank_grouping(unsigned rows) {
    if (!rows || (rows & (rows - 1)) || rows > YS) return -1;
    if (!LUMIXED_FLEXIBLE_BANK_GROUPING && rows != YS) return -1;
    y_slice = (int)rows;
    x_slice_max = (XS * YS) / (int)rows;
    return 0;
}

// Scales are contiguous FP16 bits s[ceil(Cin/G)][Cout], group-major.
// Configure before preload_hw; no scale computation or matmul runs on the CPU.
static inline int lumixed_set_group_scales(const uint16_t *scales, unsigned group) {
    if (!LUMIXED_GROUP_SCALE_MODE || !scales || ((uintptr_t)scales & 7) ||
        (group != 32 && group != 64 && group != 128)) return -1;
    asm volatile ("fence rw, rw" ::: "memory");
    ROCC_INSTRUCTION_SS(0, (uint64_t)scales, 0, 11);
    ROCC_INSTRUCTION_SS(0, group, 0, 12);
    return 0;
}

int ACTIVATION_MAX_BITS = 16;
int WEIGHTS_MAX_BITS    = 8;
int RF = Mem_row_factor; 

bool cahce4_8 = false;  // Enable the legacy 4-bit input / 8-bit weight path.

// Matrix dimension limits used for test buffers.
int Rin_max  = RIN_MAX;
int Cin_max  = CIN_MAX;
int Cout_max = COUT_MAX;


////////////////////////////////////////////////////////////////////////////

#if LUMIXED_FP16_ACTIVATIONS
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

#if LUMIXED_FP16_ACTIVATIONS
    typedef uint32_t output_t; // Raw IEEE binary32 bits; works without a CPU FPU.
#elif SCALE == 1
    typedef float output_t; 
#elif SCALE == 0  
    typedef int32_t output_t;  
#endif    

#if LUMIXED_FP16_ACTIVATIONS
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


#ifdef LUMIXED_PREGENERATED_TEST
#include "lumixed_test_vectors.h"
#endif

typedef struct {
  input_t *q; // quantized values
  #if SCALE == 1
    float s;  // scaling factors
  #endif    
} QuantizedTensor_input;

typedef struct {
  weight_t *q; // quantized values
  #if SCALE == 1
    float s;  // scaling factors
  #endif   
} QuantizedTensor_weight;


void delay(int milli_seconds)
{
    clock_t start_time = clock();
    while (clock() < start_time + milli_seconds);
}

/////////////////////////////////////////////////// CUSTOM INSTRUCTIONS ///////////////////////////////////////////////////////////// 

static inline void load_x(void *ptr,uint64_t len)
{   
	asm volatile ("fence" ::: "memory"); 
	ROCC_INSTRUCTION_SS(0, (uint64_t) ptr,len, 0);
}

static inline void load_w(void *ptr,int x_slice_input_reg , int x_slice_weights_reg, int dma_limit) 
{
	asm volatile ("fence" ::: "memory");
    uint64_t combined = 0;

    combined |= ((uint64_t)((uint16_t)x_slice_input_reg)) << 0;
    combined |= ((uint64_t)((uint16_t)x_slice_weights_reg)) << 16;
    combined |= ((uint64_t)((uint16_t)dma_limit)) << 32;
	ROCC_INSTRUCTION_SS(0, (uint64_t) ptr, combined, 1);
}

static inline void load_o(void *ptr,uint64_t len) 
{  
	asm volatile ("fence" ::: "memory");
	ROCC_INSTRUCTION_SS(0, (uint64_t) ptr, len, 2);
 } 

 static inline void set_rin(uint64_t parallel_rin, uint64_t Cin ) 
{
	asm volatile ("fence" ::: "memory");
	ROCC_INSTRUCTION_SS(0, Cin, parallel_rin, 3);
 } 


static inline void set_loops(uint64_t loops,uint64_t Rin) 
{
	asm volatile ("fence" ::: "memory");
	ROCC_INSTRUCTION_SS(0, loops, Rin, 5);
} 

static inline void stop_busy() 
{
	asm volatile ("fence" ::: "memory");
	ROCC_INSTRUCTION_SS(0, 0, 0, 6);
} 

static inline void set_scale(float scale) 

{
	asm volatile ("fence" ::: "memory");

    uint32_t scale_bits;
    memcpy(&scale_bits, &scale, sizeof(scale)); // safely convert float to raw bits

	ROCC_INSTRUCTION_SS(0, (uint64_t)scale_bits, 0, 7);
} 

static inline void set_bitwidths(int  input_bits,int weight_bits,int output_bits) 

{
	asm volatile ("fence" ::: "memory");
    uint64_t result = 0;
    result |= ((uint64_t)(output_bits & 0x3F)) << 12;
    result |= ((uint64_t)(weight_bits & 0x3F)) << 6;
    result |= ((uint64_t)(input_bits & 0x3F));
	ROCC_INSTRUCTION_SS(0, (uint64_t)result, 0, 8);
} 

static inline unsigned long read_rd() 

{
	asm volatile ("fence" ::: "memory");
    uint64_t result = 0;
	unsigned long value;
    ROCC_INSTRUCTION_D(0,value,9); //CUSTOM_READ_OUTPUT_FUNCT
	return value; 
}

static inline void start_calculation() 
{
	asm volatile ("fence" ::: "memory");
	ROCC_INSTRUCTION_SS(0, 0, 0, 10);
} 


/////////////////////////////////////////////////// CUSTOM INSTRUCTIONS /////////////////////////////////////////////////////////////


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

static inline long rdcycle(void)
{
	long cycle;
	asm volatile ("csrr %[cycle], cycle" : [cycle] "=r" (cycle));
	return cycle;
}

void printMatrices_in(LUMIXEDParams p, input_t *inputMatrix) {
    // Printing Input Matrix
    #if IN_BITS == 16 || IN_BITS == 8  
        printf("Input Matrix (%d x %d):\n", p.Rin, p.Cin);
        for (int i = 0; i < p.Rin; i++) {
            for (int j = 0; j < p.Cin; j++) {
                int index = i * p.Cin + j;
                printf("%d ", inputMatrix[index]);
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
    printf("\nOutput Matrix SW \n");
    for (int f = 0; f < 1; f++) {
        printf("Filter %d\n", f);
        for (int r = 0; r < p.Rin; r++) {
            for (int c = 0; c < p.Cout; c++) {
                int index = f * p.Rin * p.Cout + r * p.Cout + c; 
                #if LUMIXED_FP16_ACTIVATIONS
                    printf("0x%08x ", outputMatrix_SW[index]);
                #elif SCALE == 1
                    printf("%.6f ", outputMatrix_SW[index]);
                #elif SCALE == 0  
                    printf("%d ", outputMatrix_SW[index]);
                #endif        

            }
            printf("\n");  // Newline after each row in Rin dimension
        }
        printf("\n");  // Newline after each filter
    }

    // Printing Output Matrix HW
    printf("\nOutput Matrix HW (num_filters x Rin x Cout):\n");
    for (int f = 0; f < 1; f++) {
        for (int r = 0; r < p.Rin; r++) {
            for (int c = 0; c < p.Cout; c++) {
                int index = f * p.Rin * p.Cout + r * p.Cout + c;
                #if LUMIXED_FP16_ACTIVATIONS
                    printf("0x%08x ", outputMatrix_HW[index]);
                #elif SCALE == 1
                    printf("%.6f ", outputMatrix_HW[index]);
                #elif SCALE == 0  
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
    
    #if LUMIXED_FP16_ACTIVATIONS
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


void generate_random_matrix_w(QuantizedTensor_weight *matrix, int size, int filters) {   
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
                    #if LUMIXED_FP16_ACTIVATIONS
                    printf("Mismatch [%d,%d]: HW=0x%08x SW=0x%08x (FP32 bits)\n", r, c, hw, sw);
                    #elif SCALE == 1
                    printf("Mismatch [%d,%d]: HW=%.9g SW=%.9g\n", r, c, (double)hw, (double)sw);
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
    printf("Accuracy Report:\n");
    printf("----------------\n");
    #if FPU == 1
    printf("Exact matches:    %d/%d (%.2f%%)\n", exact_matches, total_elements, exact_match_percent);
    #elif FPU == 0
    printf("Exact matches:    %d/%d \n", exact_matches, total_elements);
    #endif


    #if SCALE == 1
    printf("Tolerant matches: %d/%d (%.2f%%)\n", tolerant_matches, total_elements, tolerant_match_percent);
    printf("Max absolute error: %.8f\n", max_abs_error);
    printf("Max relative error: %.8f%%\n", max_rel_error*100);
    printf("Avg relative error: %.8f%%\n", avg_rel_error*100); 
    #endif  
    
    if (correct) {
        #if FPU == 1
        printf("PASS (All elements within tolerance %.2e)\n", tolerance);
        #elif FPU == 0
        printf("PASS (All elements)\n");
        #endif  
    } else {
        printf("FAIL (%d elements exceeded tolerance)\n", total_elements - tolerant_matches);
    }
    
    return correct;
}

void preload_hw(
    int Cin,
    int Rin,
    int Cout,
    int x_slice,
    int parallel_rin,
    input_t *inputMatrix,
    weight_t *weightMatrix,
    output_t *outputMatrix_HW
    #if SCALE == 1
    , float scale
    #endif
) {
    int input_bits = IN_BITS;
    int w_bits = W_BITS;
    int o_bits = sizeof(output_t) * 8;
    set_bitwidths(input_bits, w_bits, o_bits);
    
    int sliceFactor,x_slice_weights_reg,x_elems,dma_limit;

    if(cahce4_8 && input_bits == 4 && w_bits == 8 ){ 
        sliceFactor = 1U << (8 - 4);
    } else { 
        sliceFactor = 1U << (WEIGHTS_MAX_BITS - w_bits);
    }
    int scaledSlice = x_slice * sliceFactor;

    #if LUMIXED_FLEXIBLE_BANK_GROUPING
    // Large flexible modes (e.g. B112/W2) can exceed the 16-bit command
    // field. Never encode an unused theoretical capacity by truncation.
    if (scaledSlice > Cin) scaledSlice = Cin;
    if (scaledSlice > UINT16_MAX) scaledSlice = UINT16_MAX;
    #endif

    
    if(cahce4_8 && input_bits == 4 && w_bits == 8 ){ 
        x_slice_weights_reg = scaledSlice; 
        x_elems = scaledSlice ;
        dma_limit = (Cin + x_elems - 1) / x_elems;

    } else { 
        x_slice_weights_reg = scaledSlice * (ACTIVATION_MAX_BITS / input_bits) / (WEIGHTS_MAX_BITS / w_bits);
        #if LUMIXED_FLEXIBLE_BANK_GROUPING
        // A partial packed byte still occupies a weight-storage slot.
        x_slice_weights_reg = (scaledSlice + WEIGHTS_MAX_BITS / w_bits - 1)
                             / (WEIGHTS_MAX_BITS / w_bits);
        #endif
        x_elems = scaledSlice * (ACTIVATION_MAX_BITS / input_bits);
        dma_limit = (Cin + x_elems - 1) / x_elems;
    }
    
    int x_slice_input_reg = scaledSlice;

    uint64_t loops2 = (Rin + parallel_rin - 1) / parallel_rin;

    set_rin(parallel_rin, Cin);
    set_loops(loops2, Rin);

    #if SCALE == 1
        set_scale(scale);
    #endif
    
    #if BAREMETAL_NEW == 0
        // Print addresses before calling load functions

        load_o(&outputMatrix_HW[0 * Cout], Cout);
        load_x(&inputMatrix[0 * Cin], x_slice);
        load_w(&weightMatrix[0], x_slice_input_reg, x_slice_weights_reg, dma_limit);
    #endif


}

void HW_Mat_Mul(
    int Cin,
    int Rin,
    int Cout,
    int x_slice,
    int parallel_rin,
    input_t *inputMatrix,
    weight_t *weightMatrix,
    output_t *outputMatrix_HW
    #if SCALE == 1
    , float scale
    #endif
) {
    start_calculation();

    asm volatile ("fence rw, rw" ::: "memory"); // Memory sync

    stop_busy(); // Stop waiting
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


void SW_Mat_Mul(
    int num_filters,      // Number of filters (f)
    int Rin,              // Rows in input matrix (i)
    int Cout,             // Columns in output matrix (j)
    int Cin,              // Channels in input matrix (k)
    input_t *inputMatrix, // Input matrix
    weight_t *weightMatrix, // Transposed weight matrix
    #if LUMIXED_FP16_ACTIVATIONS
    output_t *outputMatrix_SW // Raw FP32 output bits
    #else
    int32_t *outputMatrix_SW // Original integer output matrix
    #endif
) {
#if LUMIXED_FP16_ACTIVATIONS
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
    QuantizedTensor_input* x,      // input: size n * m (quantized)
    QuantizedTensor_weight* w,     // weights: size d * n (quantized)
    int n,                        // input vector length
    int d,                        // output vector length
    int m                         // number of input vectors
) {
#if LUMIXED_FP16_ACTIVATIONS
    for (int row = 0; row < m; ++row)
        for (int column = 0; column < d; ++column)
            xout[row * d + column] = lumixed_fp16_dot(x->q + row * n, w->q, n, column);
#else
    int32_t sum;

    #if SCALE == 1  
        float scale = x->s * w->s;
    #endif    

    for (int vec_i = 0; vec_i < m; vec_i++) {           // For each input vector
        for (int i = 0; i < d; i++) {                    // For each output dimension
            sum = 0;
            for (int j = 0; j < n; j++) {                // For each element in input vector and weights
                int x_val, w_val;

                // ----------------------------
                // UNPACK input x->q for vec_i-th vector and j-th element
                // ----------------------------
                #if IN_BITS == 16 || IN_BITS == 8
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

            #if SCALE == 1  
                xout[vec_i * d + i] = (float)sum * scale;
            #else
                xout[vec_i * d + i] = sum;
            #endif  
        }
    }
#endif
}


unsigned long int_hw_cycles,float_hw_cycles,start,end;


// Main matmul with uniform row-wise scale
void matmul_hw_uniform(
    output_t* xout,                 // float output vector (size d)
    QuantizedTensor_input* x,         // quantized input vector (n,)
    QuantizedTensor_weight* w,         // quantized weight matrix (d x n)
    int n,                      // input dimension
    int d,                       // output dimension
    int x_slice,
    int y_slice,
    int vectors
) {
    #if SCALE == 1
        float scale = x->s * w->s;
        HW_Mat_Mul(n,1,d,x_slice,y_slice,x->q,w->q,xout,scale);
    #endif      
     
    start = rdcycle();
    HW_Mat_Mul(n,vectors,d,x_slice,y_slice,x->q,w->q,xout);
    end = rdcycle();
    int_hw_cycles = end - start;

}

int main() {
    int test_failed = 0; 
    printf("HW parameters (WARNING: same as CONFIGS HW): x_slice_max = %d, y_slice_max = %d, ACTIVATION_MAX_BITS = %d, WEIGHTS_MAX_BITS = %d\n", x_slice_max, y_slice, ACTIVATION_MAX_BITS, WEIGHTS_MAX_BITS);

    unsigned long sw_cycles,hw_cycles,start,end;
    
    int filters_max = 1;

    LUMIXEDParams p = {
        .num_filters    = 1
        }; 
    
    QuantizedTensor_input inputMatrix;
    QuantizedTensor_weight weightMatrix[filters_max]; 
    int COUT [filters_max]; 
  printf("Hello Baremetal Mode\n");

    // static arrays for bare-metal
    static input_t inputMatrix_q[MAX_INPUT_SIZE];
    static weight_t weightMatrix_q[FILTERS_MAX][MAX_WEIGHT_SIZE];
    output_t outputMatrix_HW[filters_max][Rin_max * Cout_max]; 

    inputMatrix.q = inputMatrix_q;
    for (int f = 0; f < p.num_filters; f++)
        weightMatrix[f].q = weightMatrix_q[f];


    output_t outputMatrix_SW[filters_max][Rin_max * Cout_max];  
 
    #ifdef LUMIXED_PREGENERATED_TEST
    memcpy(inputMatrix.q, lumixed_vectors_input, sizeof(lumixed_vectors_input));
    memcpy(weightMatrix[0].q, lumixed_vectors_weights, sizeof(lumixed_vectors_weights));
    #else
    if(!ultra_fast_mode){ 
        printf("Generate matrices\n"); 
        generate_random_matrix_in(inputMatrix.q,Rin_max * Cin_max);
        generate_random_matrix_w(weightMatrix,filters_max * Cin_max * Cout_max,p.num_filters);  
    }

    #endif /* LUMIXED_PREGENERATED_TEST */


    
    #if SCALE == 1
    printf("Generates Scales\n");

    float rand_frac = (float)rand() / RAND_MAX; // [0, 1)
    inputMatrix.s =  0.01f + rand_frac * (1.0f - 0.01f); // [min_val, max_val)

    rand_frac = (float)rand() / RAND_MAX; // [0, 1)
    for(int f =0 ; f < p.num_filters ; f++){ 
        rand_frac = (float)rand() / RAND_MAX; // [0, 1)
        weightMatrix[f].s = 0.01f + rand_frac * (0.5f - 0.01f); // [min_val, max_val)
    }
    #endif 

        
    p.Rin  = Rin_max;
    p.Cin  = Cin_max; 
    p.Cout = Cout_max;
    sw_cycles = 0; 
    for(int c = 0; c < p.num_filters; c++){
        COUT[c] = Cout_max;
    }

   
    #ifndef LUMIXED_PREGENERATED_TEST
    if(!ultra_fast_mode){
        for(int f =0; f < p.num_filters; f++){ 
            transpose_weightMatrix(weightMatrix[f].q,p.Cin,COUT[f]);
        }
    }    

    #endif /* LUMIXED_PREGENERATED_TEST */

    #ifdef LUMIXED_PREGENERATED_TEST
    memcpy(outputMatrix_SW[0], lumixed_vectors_reference, sizeof(lumixed_vectors_reference));
    #else
    if(!fast_mode){
        printf("SW Mat-Mul Start\n");
        start = rdcycle();
        for(int f =0; f < p.num_filters; f++){ 
            matmul_sw_uniform(outputMatrix_SW[f], &inputMatrix, &weightMatrix[f], p.Cin,COUT[f],p.Rin); 
        }
        end = rdcycle();
        sw_cycles = end-start;  
    } 

    
    #endif /* LUMIXED_PREGENERATED_TEST */

    p.x_slice = x_slice_max*RF;
    p.parallel_rin = y_slice;

    printf("### Rin = %d, Cin = %d, Cout = %d, X Slice = %d, Parallel Rows = %d, input_t: %d bits, weight_t: %d bits, output_t: %d bits ###\n",
        p.Rin, p.Cin, p.Cout, p.x_slice, p.parallel_rin, IN_BITS, W_BITS, sizeof(output_t) * 8);


    #if Debug == 1                             
            printMatrices_in(p, inputMatrix.q);    
        for(int f =0; f < p.num_filters; f++){
            printf("Filter %d \n",f);
            p.Cout = COUT[f];
            printMatrices_w(p, weightMatrix[f].q);    

        }
    #endif    
        


    printf("HW Mat-Mul Start\n");
    start = rdcycle();
    for(int f = 0; f < p.num_filters; f++){  

        // Configure per-K-group FP16 scales before starting the matmul.
        // The accelerator DMA reads the padded, aligned array from memory.
        #if LUMIXED_GROUP_SCALE_MODE
        if (lumixed_set_group_scales(lumixed_vectors_scales, LUMIXED_VECTOR_GROUP_SIZE) != 0) {
            printf("FAIL: group-scale setup\n");
            return 1;
        }
        #endif

        preload_hw(p.Cin, p.Rin, COUT[f], p.x_slice, p.parallel_rin,inputMatrix.q,weightMatrix[f].q,outputMatrix_HW[f]);

        matmul_hw_uniform(outputMatrix_HW[f],&inputMatrix,&weightMatrix[f],p.Cin,COUT[f],p.x_slice, p.parallel_rin,Rin_max);
    }
    end = rdcycle();
    hw_cycles = end-start; 
    
    #ifdef LUMIXED_PREGENERATED_TEST
    printf("Host Python reference; HW Mat-Mul %lu cycles\n", hw_cycles);
    #else
    if(!fast_mode){
        #if FPU == 1
        double speedup = (double)sw_cycles / hw_cycles;
        #endif    

        printf("SW Mat-Mul %lu cycles\n", sw_cycles); 
        printf("HW Mat-Mul %lu cycles\n", hw_cycles); 
        #if FPU == 1
        printf("Speed-up: %.2f \n", speedup);
        #endif    

    }

    if(fast_mode){
        printf("HW Mat-Mul %lu cycles\n", hw_cycles); 
    }

        
    #endif /* LUMIXED_PREGENERATED_TEST */

    #if Debug == 1   
        for (int f =0; f < p.num_filters; f++) { 
            printf("Filter %d \n",f);
            p.Cout = COUT[f];
            printMatrices_out(p, outputMatrix_SW[f], outputMatrix_HW[f]);
        }
    #endif    

    #if FPU == 0
        if(!fast_mode){
            for(int f =0; f < p.num_filters; f++){ 
                printf("Filter %d \n",f);
                int result = compare_matrices(outputMatrix_HW[f], outputMatrix_SW[f],1, p.Rin, COUT[f]);
                if (!result) test_failed = 1;
                printf("\n",f);
            }
        } 
    #elif FPU == 1
        if(!fast_mode){
            for(int f =0; f < p.num_filters; f++){ 
                printf("Filter %d \n",f);
                int result = compare_matrices(outputMatrix_HW[f], outputMatrix_SW[f],1, p.Rin, COUT[f],0.01f);
                if (!result) test_failed = 1;
                printf("\n",f);
            }
        } 
    #endif    


    #if PC_COUNTERS == 1 && FPU == 1
        unsigned long load_x, Generate_Brams, load_w, Select_and_Accumulate, store_o,load_w_and_select;

        load_x = read_rd();
        Generate_Brams = read_rd();
        load_w = read_rd();
        Select_and_Accumulate = read_rd();
        store_o  = read_rd ();
        load_w_and_select = read_rd();

        // Calculate total
        unsigned long total_cycles = load_x + Generate_Brams +  load_w_and_select  + store_o;

        // Print actual cycles
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

        load_x = read_rd();
        Generate_Brams = read_rd();
        load_w = read_rd();
        Select_and_Accumulate = read_rd();
        store_o = read_rd();
        load_w_and_select = read_rd();

        // Calculate total
        unsigned long total_cycles = load_x + Generate_Brams + load_w_and_select + store_o;

        // Print raw cycle counts (no percentages)
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


  return test_failed;
}
