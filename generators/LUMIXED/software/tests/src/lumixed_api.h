#ifndef LUMIXED_API_H
#define LUMIXED_API_H

#include <stdbool.h>
#include <stdint.h>
#include "lumixed_rocc.h"

// Header implementation for one test translation unit. Include after defining
// input_t, weight_t, output_t and the IN_BITS/W_BITS format macros.
// Runtime limits and timing storage are supplied by the calling test.

// ============================================================================
// 1. SHARED STATE AND TENSOR TYPES
// ============================================================================
extern int ACTIVATION_MAX_BITS;
extern int WEIGHTS_MAX_BITS;
extern unsigned long int_hw_cycles, float_hw_cycles, start, end;

typedef struct {
    input_t *q; // Integer activations or raw IEEE FP bits.
} LumixedActivationTensor;

typedef struct {
    weight_t *q; // Packed signed weights in column-major order.
} LumixedWeightTensor;

// ============================================================================
// 2. CPU TIMING
// ============================================================================
// CPU cycle counter, separate from the accelerator counters.
static inline long rdcycle(void)
{
    long cycle;
    asm volatile ("csrr %[cycle], cycle" : [cycle] "=r" (cycle));
    return cycle;
}

// ============================================================================
// 3. HARDWARE API: configure, then execute
// ============================================================================
// Matrix convention: X[R,K] * W[K,N] -> O[R,N].
// Dimensions: activation_rows=R, inner_dim=K, output_columns=N.
// X/O are row-major; W uses the accelerator's column-major packed layout.
//
// Normal call sequence:
//   1. Optional: lumixed_select_bank_grouping(rows), when hardware supports it.
//   2. Optional: lumixed_set_group_scales(scales, G), for group-scaled hardware.
//   3. lumixed_configure_matmul(K, R, N, base_slice_slots, rows_per_group, X, W, O).
//   4. lumixed_execute_matmul_timed(O, &x, &w, K, N, base_slice_slots, rows_per_group, R).
//
// 3a. CONFIGURE: validate dimensions and calculate execution settings.
// base_activation_slice_slots = active_banks_per_row * table_capacity_factor.
// Weight precision expands this base capacity; integer packing can place
// multiple logical activations in one native activation-storage slot.
// Configure precision, dimensions, row groups, and slice/DMA settings.
// Emits SET_X_W, SET_OUTPUT, SET_SHAPE and SET_EXECUTION (RoCC ABI v2).
// Does not start computation or wait for completion.
void lumixed_configure_matmul(
    int inner_dim,
    int activation_rows,
    int output_columns,
    int base_activation_slice_slots,
    int rows_per_group,
    input_t *activations,
    weight_t *packed_weights,
    output_t *output
)
{
    int activation_bits = IN_BITS;
    int weight_bits = W_BITS;
    if (inner_dim <= 0 || activation_rows <= 0 || output_columns <= 0 || rows_per_group <= 0 ||
        inner_dim > CIN_MAX || activation_rows > RIN_MAX || output_columns > COUT_MAX ||
        rows_per_group > parallel_rows)
        __builtin_trap();
    if (LUMIXED_GROUP_SCALE_MODE && (!lumixed_group_scale_address ||
        !lumixed_group_size))
        __builtin_trap();

    int weight_precision_expansion;
    int weight_slice_bytes;
    int activations_per_window;
    int k_window_count;

    weight_precision_expansion = 1U << (WEIGHTS_MAX_BITS - weight_bits);
    int activation_slice_slots = base_activation_slice_slots * weight_precision_expansion;

    #if LUMIXED_FLEXIBLE_BANK_GROUPING
    // Large flexible modes (e.g. B112/W2) can exceed the 16-bit command
    // field. Never encode an unused theoretical capacity by truncation.
    if (activation_slice_slots > inner_dim) activation_slice_slots = inner_dim;
    if (activation_slice_slots > UINT16_MAX) activation_slice_slots = UINT16_MAX;
    #endif

    weight_slice_bytes = activation_slice_slots * (ACTIVATION_MAX_BITS / activation_bits) / (WEIGHTS_MAX_BITS / weight_bits);
    #if LUMIXED_FLEXIBLE_BANK_GROUPING
    // A partial packed byte still occupies a weight-storage slot.
    weight_slice_bytes = (activation_slice_slots + WEIGHTS_MAX_BITS / weight_bits - 1)
                         / (WEIGHTS_MAX_BITS / weight_bits);
    #endif
    activations_per_window = activation_slice_slots * (ACTIVATION_MAX_BITS / activation_bits);
    k_window_count = (inner_dim + activations_per_window - 1) / activations_per_window;

    int configured_activation_slots = activation_slice_slots;

    uint64_t row_group_count = (activation_rows + rows_per_group - 1) / rows_per_group;

    lumixed_set_x_w(activations, packed_weights);
    lumixed_set_output(output, (uint64_t)output_columns);
    lumixed_set_shape((uint32_t)inner_dim, (uint32_t)activation_rows,
                     (uint32_t)rows_per_group, (uint32_t)row_group_count);
    lumixed_set_execution((unsigned)configured_activation_slots,
                         (unsigned)weight_slice_bytes, (unsigned)k_window_count,
                         (unsigned)activation_bits, (unsigned)weight_bits,
                         LUMIXED_GROUP_SCALE_MODE ? lumixed_group_size : 0);

}

// 3b. EXECUTE: start and wait using settings installed by lumixed_configure_matmul().
// The retained arguments preserve the existing API; this function only issues
// lumixed_start() and lumixed_wait_done().
void lumixed_execute_matmul(
    int inner_dim,
    int activation_rows,
    int output_columns,
    int base_activation_slice_slots,
    int rows_per_group,
    input_t *activations,
    weight_t *packed_weights,
    output_t *output
)
{
    lumixed_start(LUMIXED_GROUP_SCALE_MODE ? lumixed_group_scale_address : 0);
    lumixed_wait_done();
}

// 3c. TIMED EXECUTE: requires lumixed_configure_matmul() first.
// Calls lumixed_execute_matmul() and records CPU elapsed cycles in int_hw_cycles.
// This wrapper measures execution/wait CPU cycles; configuration is separate.
// Group scales are configured with lumixed_set_group_scales().
void lumixed_execute_matmul_timed(
    output_t *output,                 // Output matrix [activation_rows][output_columns].
    LumixedActivationTensor *activation_tensor,
    LumixedWeightTensor *weight_tensor,
    int inner_dim,
    int output_columns,
    int base_activation_slice_slots,
    int rows_per_group,
    int activation_rows
)
{

    start = rdcycle();
    lumixed_execute_matmul(inner_dim, activation_rows, output_columns,
               base_activation_slice_slots, rows_per_group,
               activation_tensor->q, weight_tensor->q, output);
    end = rdcycle();
    int_hw_cycles = end - start;

}

#endif // LUMIXED_API_H
