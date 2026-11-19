#!/usr/bin/env python3
"""Generate independent LUMIXED matmul references for all supported main modes.

Modes:
  0: INT8/INT16 activations * signed W2/W4/W8 -> INT32
  1: FP16/FP32 activations * signed W2/W4/W8 -> exact FP32 bits
  2: finite FP16/FP32 activations * signed W2/W4/W8 -> group-scaled FP32 bits

Group modes ALIGNED (1) and DEFERRED (2) have identical mathematical results.
Scales are IEEE FP16 bits in [ceil(K/G)][N] group-major order, padded through
one final 64-bit memory beat. The group sum is exact fixed24 * fixed24 ->
fixed48, rounded once to FP32 (RNE).

This is a mathematical reference, not a simulation of RoCC/DMA timing.
"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
from pathlib import Path


def newlib_rand(seed: int):
    """Existing LUMAX host-generator sequence, matched to the C test pattern."""
    state = seed & 0xFFFFFFFF
    while True:
        state = (state * 6364136223846793005 + 1) & ((1 << 64) - 1)
        yield (state >> 32) & 0x7FFFFFFF


def fp16_fixed24(bits: int) -> int:
    """Convert finite IEEE binary16 bits exactly to a signed value * 2^-24."""
    bits &= 0xFFFF
    exp = (bits >> 10) & 31
    frac = bits & 0x3FF
    if exp == 31:
        raise ValueError('NaN/Inf are not allowed in group-scaling mode')
    value = frac if exp == 0 else (1024 + frac) << (exp - 1)
    return -value if bits & 0x8000 else value


def fp_fixed(raw: int, fp_bits: int) -> int:
    """Exact integer units of 2^-24 or 2^-149; no host float arithmetic."""
    f, e = (10, 5) if fp_bits == 16 else (23, 8)
    exponent = (raw >> f) & ((1 << e) - 1)
    fraction = raw & ((1 << f) - 1)
    if exponent == (1 << e) - 1:
        raise ValueError("Group scaling requires finite activations")
    value = fraction if exponent == 0 else ((1 << f) | fraction) << (exponent - 1)
    return -value if raw & (1 << (fp_bits - 1)) else value


def round_rne_div_pow2(value: int, amount: int) -> int:
    """Exact nonnegative integer divide by 2**amount; round ties to even."""
    if amount <= 0:
        return value << (-amount)
    whole, rem = divmod(value, 1 << amount)
    half = 1 << (amount - 1)
    return whole + (rem > half or (rem == half and (whole & 1) == 1))


def fixed_to_fp32_bits(value: int, fractional_bits: int) -> int:
    """Correctly rounded IEEE FP32 bits from an exact signed fixed-point sum."""
    if value == 0:
        return 0  # RTL's exact-zero policy is +0.
    sign = (1 << 31) if value < 0 else 0
    mag = abs(value)
    top = mag.bit_length() - 1
    exp = top - fractional_bits
    if exp > 127:
        return sign | 0x7F800000
    if exp >= -126:
        significand = round_rne_div_pow2(mag, top - 23)
        if significand >= (1 << 24):
            significand >>= 1
            exp += 1
        if exp > 127:
            return sign | 0x7F800000
        return sign | ((exp + 127) << 23) | (significand & 0x7FFFFF)
    # General subnormal fallback; the fixed24/fixed48 domains used here cannot
    # reach this path except after adding a larger fractional precision.
    sub = round_rne_div_pow2(mag, fractional_bits - 149)
    if sub >= (1 << 23):
        return sign | (1 << 23)
    return sign | sub


def unpack_signed_weights(packed: list[int], k: int, n: int, w_bits: int) -> list[list[int]]:
    """Return q[N][K] from the C hardware's column-major bit packing."""
    q = []
    mask = (1 << w_bits) - 1
    for col in range(n):
        column = []
        for kk in range(k):
            index = col * k + kk
            encoded = ((packed[(index * w_bits) // 8] & 0xFF) >> ((index * w_bits) % 8)) & mask
            column.append(encoded - (1 << w_bits) if encoded & (1 << (w_bits - 1)) else encoded)
        q.append(column)
    return q


def int_matmul(x: list[int], q: list[list[int]], r: int, k: int, n: int) -> list[int]:
    result = [sum(x[row*k+kk] * q[col][kk] for kk in range(k))
              for row in range(r) for col in range(n)]
    if any(not (-2**31 <= v < 2**31) for v in result):
        raise ValueError('INT32 reference would overflow; use a smaller test or lower values')
    return result


def fp_matmul(x: list[int], q: list[list[int]], r: int, k: int, n: int, fp_bits: int = 16) -> list[int]:
    """Mirror the current C/RTL unscaled FP16 special-value behavior."""
    fraction_bits, exp_mask = (10, 31) if fp_bits == 16 else (23, 255)
    fixed_bits = 24 if fp_bits == 16 else 149
    result = []
    for row in range(r):
        for col in range(n):
            exact = 0
            nan = pos_inf = neg_inf = False
            for kk in range(k):
                raw = x[row*k+kk]
                weight = q[col][kk]
                exp, frac = (raw >> fraction_bits) & exp_mask, raw & ((1 << fraction_bits) - 1)
                if exp == exp_mask:
                    negative = bool(raw & (1 << (fp_bits - 1))) != (weight < 0)
                    if frac or weight == 0:
                        nan = True
                    elif negative:
                        neg_inf = True
                    else:
                        pos_inf = True
                else:
                    exact += fp_fixed(raw, fp_bits) * weight
            if nan or (pos_inf and neg_inf):
                bits = 0x7FC00000
            elif pos_inf:
                bits = 0x7F800000
            elif neg_inf:
                bits = 0xFF800000
            else:
                bits = fixed_to_fp32_bits(exact, fixed_bits)
            result.append(bits)
    return result


def grouped_matmul(x: list[int], q: list[list[int]], scales: list[int],
                   r: int, k: int, n: int, group_size: int, fp_bits: int = 16) -> list[int]:
    """Exact fixed48 group scaling with ONE final FP32 rounding."""
    groups = (k + group_size - 1) // group_size
    if len(scales) != groups * n:
        raise ValueError('Scale dimensions must be [ceil(K/G)][N]')
    xf = [fp_fixed(raw, fp_bits) for raw in x]
    sf = [fp16_fixed24(raw) for raw in scales]
    result = []
    for row in range(r):
        for col in range(n):
            total_fixed48 = 0
            for g in range(groups):
                begin, end = g * group_size, min((g + 1) * group_size, k)
                partial_fixed24 = sum(xf[row*k+kk] * q[col][kk]
                                      for kk in range(begin, end))
                total_fixed48 += partial_fixed24 * sf[g*n+col]
            result.append(fixed_to_fp32_bits(total_fixed48, (24 if fp_bits == 16 else 149) + 24))
    return result


# A deterministic mix of regular scales, zeros, negative scales, subnormals,
# and the maximum finite FP16. These do not imply Qwen/GGUF dequant support.
SCALE_PALETTE = (
    0x3C00, 0x3800, 0x3400, 0x4000, 0xBC00, 0xB800,
    0x0000, 0x0001, 0x8001, 0x0400, 0x7BFF,
)


@dataclass
class Vectors:
    inputs: list[int]
    packed_weights: list[int]
    expected: list[int]
    scales: list[int]
    fp_activations: bool
    group_scale_mode: int
    group_size: int


def prepare(rin: int, cin: int, cout: int, in_bits: int, w_bits: int,
            seed: int = 1, pattern: int = 0, *, fp_activations: bool = False,
            group_scale_mode: int = 0, group_size: int = 64) -> Vectors:
    if min(rin, cin, cout) < 1 or in_bits not in (8, 16, 32) or w_bits not in (2, 4, 8):
        raise ValueError('Invalid matrix size or element precision')
    if pattern not in (0, 1, 2):
        raise ValueError('pattern must be 0, 1, or 2')
    if group_scale_mode not in (0, 1, 2):
        raise ValueError('group scale mode must be 0=off, 1=aligned, or 2=deferred')
    if fp_activations and in_bits not in (16, 32):
        raise ValueError('FP activations require 16/32-bit storage')
    if not fp_activations and in_bits == 32:
        raise ValueError('INT32 activations are not supported')
    if group_scale_mode and (not fp_activations or w_bits not in (2, 4, 8)):
        raise ValueError('Group scaling requires FP16 activations and signed W2/W4/W8')
    if group_scale_mode and group_size not in (32, 64, 128):
        raise ValueError('Supported group sizes are 32, 64, 128')

    rng = newlib_rand(seed)
    if fp_activations:
        inputs = []
        for _ in range(rin * cin):
            if in_bits == 16:
                bits = next(rng) & 0xFFFF
                if (bits & 0x7C00) == 0x7C00:
                    bits ^= 0x0400
                minimum = 0xFBFF
            else:
                bits = next(rng) | ((next(rng) & 1) << 31)
                if (bits & 0x7F800000) == 0x7F800000:
                    bits ^= 0x00800000
                minimum = 0xFF7FFFFF
            inputs.append(minimum if pattern == 1 else bits)
    else:
        lo = -(1 << (in_bits - 1))
        inputs = [lo if pattern == 1 else lo + next(rng) % (1 << in_bits)
                  for _ in range(rin * cin)]

    rng = newlib_rand(seed)  # Weight C helper reseeds before generating weights.
    wlo = -(1 << (w_bits - 1))
    weights_row_major = [((i + 1 + 128) % 256 - 128) if w_bits == 8 and pattern != 2
                         else wlo + next(rng) % (1 << w_bits)
                         for i in range(cin * cout)]
    weights_col_major = [weights_row_major[kk*cout+col]
                         for col in range(cout) for kk in range(cin)]
    per_byte = 8 // w_bits
    packed = []
    for idx in range(0, len(weights_col_major), per_byte):
        byte = sum((v & ((1 << w_bits) - 1)) << (slot * w_bits)
                   for slot, v in enumerate(weights_col_major[idx:idx+per_byte]))
        packed.append(byte if byte < 128 else byte - 256)

    q = unpack_signed_weights(packed, cin, cout, w_bits)
    scales = []
    if group_scale_mode:
        groups = (cin + group_size - 1) // group_size
        scale_rng = newlib_rand((seed ^ 0x5EED5CA1 ^ group_size) & 0xFFFFFFFF)
        scales = [SCALE_PALETTE[next(scale_rng) % len(SCALE_PALETTE)]
                  for _ in range(groups * cout)]
        expected = grouped_matmul(inputs, q, scales, rin, cin, cout, group_size, in_bits)
    elif fp_activations:
        expected = fp_matmul(inputs, q, rin, cin, cout, in_bits)
    else:
        expected = int_matmul(inputs, q, rin, cin, cout)
    return Vectors(inputs, packed, expected, scales, fp_activations,
                   group_scale_mode, group_size)


def _array(name: str, ctype: str, values: list[int], *, align8: bool = False) -> list[str]:
    decl = f'static const {ctype} lumixed_vectors_{name}[{len(values)}]'
    if align8:
        decl += ' __attribute__((aligned(8)))'
    lines = [decl + ' = {']
    for start in range(0, len(values), 12):
        lines.append('    ' + ', '.join(str(v) for v in values[start:start+12]) + ',')
    lines.append('};')
    return lines


def write_header(path: str | Path, rin: int, cin: int, cout: int, in_bits: int,
                 w_bits: int, seed: int = 1, pattern: int = 0, *,
                 fp_activations: bool = False, group_scale_mode: int = 0,
                 group_size: int = 64) -> Vectors:
    data = prepare(rin, cin, cout, in_bits, w_bits, seed, pattern,
                   fp_activations=fp_activations,
                   group_scale_mode=group_scale_mode, group_size=group_size)
    mode = ('integer', f'fp{in_bits}', 'group-aligned' if group_scale_mode == 1 else 'group-deferred')[
        2 if group_scale_mode else (1 if fp_activations else 0)]
    lines = [
        '/* Auto-generated host Python vectors and independent matmul reference. */',
        f'/* R={rin} K={cin} N={cout} A={in_bits} W={w_bits} SEED={seed} PATTERN={pattern} MODE={mode} */',
        '#ifndef LUMIXED_PREPARED_VECTORS_H',
        '#define LUMIXED_PREPARED_VECTORS_H',
        f'#if RIN_MAX != {rin} || CIN_MAX != {cin} || COUT_MAX != {cout} || IN_BITS != {in_bits} || W_BITS != {w_bits}',
        '#error "Python vectors and lumixed_matmul_test.c matrix geometry differ"',
        '#endif',
        f'#if LUMIXED_FP_ACTIVATIONS != {int(fp_activations)} || LUMIXED_GROUP_SCALE_MODE != {group_scale_mode}',
        '#error "Python reference mode and compiled LUMIXED flags differ"',
        '#endif',
        f'#if LUMIXED_FP_ACTIVATIONS && LUMIXED_FP_BITS != {in_bits}',
        '#error "Python activation precision and hardware differ"',
        '#endif',
        f'#define LUMIXED_VECTOR_GROUP_SIZE {group_size if group_scale_mode else 0}',
    ]
    lines += _array('input', 'input_t', data.inputs)
    lines += _array('weights', 'weight_t', data.packed_weights)
    lines += _array('reference', 'output_t', data.expected)
    if group_scale_mode:
        # RTL scale SRAM uses raw group-major FP16 entries, four per 64-bit beat.
        # Only the final transfer beat is padded (NOT each group row).
        padded = data.scales + [0] * (-len(data.scales) % 4)
        lines += _array('scales', 'uint16_t', padded, align8=True)
    lines.append('#endif /* LUMIXED_PREPARED_VECTORS_H */')
    Path(path).write_text('\n'.join(lines) + '\n')
    return data


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('rin', 'cin', 'cout', 'in_bits', 'w_bits'):
        parser.add_argument(name, type=int)
    parser.add_argument('--seed', type=int, default=1)
    parser.add_argument('--pattern', type=int, default=0)
    parser.add_argument('--fp-activations', type=int, choices=(0, 1), default=0)
    parser.add_argument('--group-scale-mode', type=int, choices=(0, 1, 2), default=0)
    parser.add_argument('--group-size', type=int, default=64)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    data = write_header(args.output, args.rin, args.cin, args.cout,
                        args.in_bits, args.w_bits, args.seed, args.pattern,
                        fp_activations=bool(args.fp_activations),
                        group_scale_mode=args.group_scale_mode,
                        group_size=args.group_size)
    print(f'Python reference ready: {len(data.expected)} outputs; '
          f'FP={int(data.fp_activations)}, group mode={data.group_scale_mode}, '
          f'group size={data.group_size if data.group_scale_mode else "off"}')


if __name__ == '__main__':
    main()
