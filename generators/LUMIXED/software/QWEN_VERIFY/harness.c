#define main qwen_unused_test_main
#include "Linear-sw.snapshot.c"
#undef main
#include <sys/types.h>
extern ssize_t _write(int fd, const void *buffer, size_t size);

_Alignas(8) static output_t guarded_output[RIN_MAX * COUT_MAX + 4];

int main(void) {
    const unsigned count = RIN_MAX * COUT_MAX;
    for (unsigned i = 0; i < count + 4; ++i) guarded_output[i] = 0xbadc0deu;
    output_t *out = guarded_output + 2;
    QuantizedTensor_input x = { .q = (input_t *)lumixed_vectors_input };
    QuantizedTensor_weight w = { .q = (weight_t *)lumixed_vectors_weights };
    if (lumixed_set_group_scales(lumixed_vectors_scales, LUMIXED_VECTOR_GROUP_SIZE)) return 3;
    preload_hw(CIN_MAX, RIN_MAX, COUT_MAX, XS * Mem_row_factor, YS, x.q, w.q, out);
    matmul_hw_uniform(out, &x, &w, CIN_MAX, COUT_MAX, XS * Mem_row_factor, YS, RIN_MAX);
    const unsigned long load_x = read_rd();
    const unsigned long gen = read_rd();
    const unsigned long load_w = read_rd();
    const unsigned long select = read_rd();
    const unsigned long store = read_rd();
    const unsigned long combined = read_rd();
    const unsigned long total = load_x + gen + combined + store;
    const int guards = guarded_output[0] == 0xbadc0deu && guarded_output[1] == 0xbadc0deu &&
                       guarded_output[count + 2] == 0xbadc0deu && guarded_output[count + 3] == 0xbadc0deu;
    printf("QWEN_VERIFY_GUARDS %s\n", guards ? "PASS" : "FAIL");
    printf("QWEN_VERIFY_CYCLES total=%lu load_x=%lu generate_brams=%lu load_w=%lu select_accumulate=%lu store_o=%lu load_w_and_select=%lu\n",
           total, load_x, gen, load_w, select, store, combined);
    printf("QWEN_VERIFY_BINARY %u\n", count * 4);
    fflush(stdout);
    // The nano stdio fwrite path can issue one HTIF syscall per byte.
    // Export the DMA buffer in bulk without changing accelerator timing.
    const unsigned char *bytes = (const unsigned char *)out;
    size_t remaining = count * sizeof(output_t);
    while (remaining) {
        const ssize_t sent = _write(1, bytes, remaining);
        if (sent <= 0 || (size_t)sent > remaining) return 4;
        bytes += sent;
        remaining -= sent;
    }
    printf("\nQWEN_VERIFY_END\n");
    return guards ? 0 : 5;
}
