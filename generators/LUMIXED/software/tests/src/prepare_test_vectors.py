#!/usr/bin/env python3
"""Prepare the same newlib-seeded test vectors and an integer dot-product reference."""
import argparse
from pathlib import Path


def newlib_rand(seed):
    state = seed & 0xffffffff
    while True:
        state = (state * 6364136223846793005 + 1) & ((1 << 64) - 1)
        yield (state >> 32) & 0x7fffffff


def prepare(rin, cin, cout, in_bits, w_bits, seed=1, pattern=0):
    if min(rin, cin, cout) < 1 or in_bits not in (8, 16) or w_bits not in (2, 4, 8):
        raise ValueError("Unsupported dimensions or precision")
    if pattern not in (0, 1, 2):
        raise ValueError("TEST_PATTERN must be 0, 1, or 2")
    rng = newlib_rand(seed)
    lo = -(1 << (in_bits - 1))
    inputs = [lo if pattern == 1 else lo + next(rng) % (1 << in_bits)
              for _ in range(rin * cin)]
    rng = newlib_rand(seed)
    wlo = -(1 << (w_bits - 1))
    weights = [((i + 1 + 128) % 256 - 128) if w_bits == 8 and pattern != 2
               else wlo + next(rng) % (1 << w_bits) for i in range(cin * cout)]
    transposed = [weights[j * cout + c] for c in range(cout) for j in range(cin)]
    packed = []
    per_byte = 8 // w_bits
    for start in range(0, len(transposed), per_byte):
        byte = sum((v & ((1 << w_bits) - 1)) << (i * w_bits)
                   for i, v in enumerate(transposed[start:start + per_byte]))
        packed.append(byte if byte < 128 else byte - 256)
    reference = [sum(inputs[r * cin + j] * weights[j * cout + c] for j in range(cin))
                 for r in range(rin) for c in range(cout)]
    if any(v < -(1 << 31) or v >= (1 << 31) for v in reference):
        raise ValueError("Reference exceeds signed 32-bit output range")
    return inputs, packed, reference


def write_header(path, rin, cin, cout, in_bits, w_bits, seed=1, pattern=0):
    vectors = prepare(rin, cin, cout, in_bits, w_bits, seed, pattern)
    lines = ["/* Deterministic host vectors, equivalent to the RISC-V newlib generator. */",
             f"/* RIN={rin} CIN={cin} COUT={cout} IN={in_bits} W={w_bits} SEED={seed} PATTERN={pattern} */"]
    for name, ctype, values in zip(("input", "weights", "reference"),
                                  ("input_t", "weight_t", "output_t"), vectors):
        lines.append(f"static const {ctype} lumixed_vectors_{name}[{len(values)}] = {{")
        for start in range(0, len(values), 16):
            lines.append("    " + ", ".join(map(str, values[start:start + 16])) + ",")
        lines.append("};")
    Path(path).write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("rin", "cin", "cout", "in_bits", "w_bits"):
        parser.add_argument(name, type=int)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--pattern", type=int, default=0)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    write_header(args.output, args.rin, args.cin, args.cout,
                 args.in_bits, args.w_bits, args.seed, args.pattern)
