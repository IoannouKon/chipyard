#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("suite", HERE / "run_qwen_verify.py")
suite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(suite)


class CaptureChecks(unittest.TestCase):
    def test_binary_payload_preserves_zero_newline_and_ff(self):
        data = b"\0\n\xff\0" * 3
        raw = (b"QWEN_VERIFY_GUARDS PASS\n"
               b"QWEN_VERIFY_CYCLES total=10 load_x=1 generate_brams=2 load_w=9 select_accumulate=9 store_o=4 load_w_and_select=3\n"
               b"QWEN_VERIFY_BINARY 12\n" + data + b"\nQWEN_VERIFY_END\n")
        got, c = suite.extract_payload(raw, 3)
        self.assertEqual(got, data)
        self.assertEqual(c["total"], sum(c[x] for x in suite.STAGES))

    def test_truncated_payload_fails(self):
        with self.assertRaises(RuntimeError):
            suite.extract_payload(b"QWEN_VERIFY_BINARY 8\n123\nQWEN_VERIFY_END\n", 2)

    def test_native_bit_and_tolerance_are_distinct(self):
        a = struct.pack("<I", 0x3f800001)
        b = struct.pack("<I", 0x3f800000)
        result = suite.compare(a, b, atol=1e-6)
        self.assertFalse(result["bit_exact"])
        self.assertTrue(result["within_tolerance"])
        self.assertEqual(result["max_ulp_error"], 1)

    def test_signed_zero_still_counts_as_bit_difference(self):
        result = suite.compare(struct.pack("<I", 0x80000000), struct.pack("<I", 0))
        self.assertFalse(result["bit_exact"])
        self.assertEqual(result["max_abs_error"], 0)

    def test_folder_guard_rejects_outside_writes(self):
        with self.assertRaises(ValueError):
            suite.inside(HERE.parent / "outside-output")
        self.assertEqual(suite.inside(HERE / "cache"), HERE / "cache")

    def test_model_projection_counts(self):
        self.assertEqual(sum(x[3] for x in suite.SHAPES), 196)
        self.assertEqual(sum(x[2] for x in suite.OPS), 196)


if __name__ == "__main__":
    unittest.main()
