#ifndef LUMIXED_ROCC_H
#define LUMIXED_ROCC_H

#include <stdint.h>
#include "rocc.h"

// ============================================================================
// 1. COMMAND INTERFACE: RoCC ABI v2, custom-0
// ============================================================================
// Bit ranges are inclusive low:high.
// Configure all four words before START. The four configuration commands may be
// sent in any order. All addresses retain their full 64 bits. Reconfigure each run.
//
// funct  Wrapper                   rs1                         rs2
//   0    lumixed_set_x_w           X address                   W address
//   1    lumixed_set_output        O address                   N
//   2    lumixed_set_shape         K[0:31], R[32:63]            rows[0:31], loops[32:63]
//   3    lumixed_set_execution     packed slices/precision     G (0 if disabled)
//   4    lumixed_start             FP16 scale address          reserved=0
//   5    lumixed_wait_done         selector=0                  reserved=0
//   5    lumixed_read_counter      selector=1                  counter index 0..5
// Only a counter read returns rd. Old command encodings are not compatible.

// ============================================================================
// 2. HARDWARE COUNTER INDICES
// ============================================================================
enum {
    LUMIXED_COUNTER_LOAD_X           = 0,
    LUMIXED_COUNTER_GENERATE         = 1,
    LUMIXED_COUNTER_LOAD_W           = 2,
    LUMIXED_COUNTER_SELECT           = 3,
    LUMIXED_COUNTER_STORE_O          = 4,
    LUMIXED_COUNTER_LOAD_W_AND_SELECT = 5
};

// ============================================================================
// 3. CONFIGURATION COMMANDS: store settings before START
// ============================================================================

// Set activation and packed-weight addresses.
// Bits: rs1[0:63]=X address; rs2[0:63]=W address.
static inline void lumixed_set_x_w(const void *activation_base, const void *weight_base)
{
    asm volatile ("fence rw, rw" ::: "memory");
    ROCC_INSTRUCTION_SS(0, (uint64_t)activation_base, (uint64_t)weight_base, 0);
}

// Set the output address and number of output columns.
// Bits: rs1[0:63]=O address; rs2[0:63]=N (must fit hardware Cout capacity).
static inline void lumixed_set_output(void *output_base, uint64_t output_columns)
{
    ROCC_INSTRUCTION_SS(0, (uint64_t)output_base, output_columns, 1);
}

// Set matrix dimensions and row scheduling.
// Bits: rs1[0:31]=K, [32:63]=R; rs2[0:31]=parallel rows, [32:63]=ceil(R/rows).
static inline void lumixed_set_shape(
    uint32_t inner_dim,
    uint32_t activation_rows,
    uint32_t rows_per_group,
    uint32_t row_group_count)
{
    uint64_t shape_word = (uint64_t)inner_dim | ((uint64_t)activation_rows << 32);
    uint64_t schedule_word = (uint64_t)rows_per_group | ((uint64_t)row_group_count << 32);
    ROCC_INSTRUCTION_SS(0, shape_word, schedule_word, 2);
}

// Set slice geometry, element precision and quantization group size.
// activation_slice_slots: native activation-storage slots per K window; packed
// integer inputs can hold multiple logical activations in each slot.
// weight_slice_bytes: packed weight bytes associated with the activation slice.
// k_window_count: ceil(K / logical activations per window), not DMA beat count.
// Bits: rs1[0:15]=activation slice slots, [16:31]=weight slice bytes, [32:47]=K-window count,
// [48:53]=activation bits, [54:59]=weight bits, [60:63]=0; rs2[0:63]=quantization group size.
// Unlike ABI v1, the hardware checks the supplied FP activation precision.
static inline void lumixed_set_execution(
    unsigned activation_slice_slots,
    unsigned weight_slice_bytes,
    unsigned k_window_count,
    unsigned activation_bits,
    unsigned weight_bits,
    unsigned quant_group_size)
{
    if (!activation_slice_slots || activation_slice_slots > UINT16_MAX || !weight_slice_bytes || weight_slice_bytes > UINT16_MAX ||
        !k_window_count || k_window_count > UINT16_MAX ||
        activation_bits > 63 || weight_bits > 63)
        __builtin_trap();

    uint64_t execution_word = (uint64_t)activation_slice_slots | ((uint64_t)weight_slice_bytes << 16) |
                      ((uint64_t)k_window_count << 32) | ((uint64_t)activation_bits << 48) |
                      ((uint64_t)weight_bits << 54);

    ROCC_INSTRUCTION_SS(0, execution_word, (uint64_t)quant_group_size, 3);
}

// ============================================================================
// 4. EXECUTION AND COUNTER COMMANDS
// ============================================================================

// Start with the stored configuration and the supplied scale address.
// Bits: rs1[0:63]=FP16 scale address (0 if disabled); rs2[0:63]=reserved=0.
static inline void lumixed_start(const uint16_t *group_scales)
{
    asm volatile ("fence rw, rw" ::: "memory");
    ROCC_INSTRUCTION_SS(0, (uint64_t)group_scales, 0, 4);
}

// Bits: rs1[0:63]=selector 0; rs2[0:63]=reserved=0. No destination register.
// Hardware accepts this instruction only after output DMA has drained.
static inline void lumixed_wait_done(void)
{
    ROCC_INSTRUCTION_SS(0, 0, 0, 5);
    asm volatile ("fence rw, rw" ::: "memory");
}

// Bits: rs1[0:63]=selector 1; rs2[0:63]=index 0..5; rd[0:63]=counter value.
static inline unsigned long lumixed_read_counter(unsigned counter_index)
{
    unsigned long counter_value;
    if (counter_index > LUMIXED_COUNTER_LOAD_W_AND_SELECT)
        __builtin_trap();
    ROCC_INSTRUCTION_DSS(0, counter_value, 1, (uint64_t)counter_index, 5);
    return counter_value;
}

// ============================================================================
// 5. SOFTWARE CONFIGURATION: no RoCC instruction is issued here
// ============================================================================
// Optional software configuration. These helpers do not issue RoCC commands.
// lumixed_configure_matmul sends G with execution settings; lumixed_execute_matmul sends scales at START.
extern int active_banks_per_row;
extern int active_parallel_rows;

static const uint16_t *lumixed_group_scale_address = 0;
static unsigned lumixed_group_size = 0;

// Bits: no instruction; selects software geometry for the next SET_SHAPE.
static inline int lumixed_select_bank_grouping(unsigned rows_per_group)
{
    if (!rows_per_group || (rows_per_group & (rows_per_group - 1)) || rows_per_group > parallel_rows)
        return -1;
    if (!LUMIXED_FLEXIBLE_BANK_GROUPING && rows_per_group != parallel_rows)
        return -1;

    active_parallel_rows = (int)rows_per_group;
    active_banks_per_row = (banks_per_row * parallel_rows) / (int)rows_per_group;
    return 0;
}

// Bits: no instruction; cache the aligned FP16 pointer and G for the next run.
// Layout: scales[ceil(K/G)][N], group-major, final DMA beat padded to 8 bytes.
static inline int lumixed_set_group_scales(const uint16_t *group_scales, unsigned quant_group_size)
{
    if (!LUMIXED_GROUP_SCALE_MODE || !group_scales || ((uintptr_t)group_scales & 7) ||
        (quant_group_size != 32 && quant_group_size != 64 && quant_group_size != 128))
        return -1;

    lumixed_group_scale_address = group_scales;
    lumixed_group_size = quant_group_size;
    return 0;
}

#endif // LUMIXED_ROCC_H
