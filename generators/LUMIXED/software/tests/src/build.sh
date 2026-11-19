#!/usr/bin/env bash
# =============================================
# Build script for lumixed_matmul_test.c (RISC-V)
# Usage:
#   ./build.sh baremetal   -> build for bare-metal (no OS)
#   ./build.sh linux       -> build for Linux RISC-V
# =============================================

set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
SRC="lumixed_matmul_test.c"
OUT="lumixed_matmul_test.riscv"

# Check argument
if [ "$#" -ne 1 ]; then
  echo "Usage: $0 [baremetal|linux]"
  exit 1
fi

MODE="$1"
read -r -a TEST_FLAGS <<< "${TEST_CFLAGS:--O2}"

# Match the software datatype to the elaboration-time hardware format. A copied
# test harness can select explicitly with -DLUMIXED_FP_ACTIVATIONS=0 or 1.
CONFIG_SOURCE="${LUMIXED_CONFIG_PATH:-$SCRIPT_DIR/../../../src/main/scala/Config.scala}"
if [ -f "$CONFIG_SOURCE" ]; then
  fp_mode=$(sed -n 's/^[[:space:]]*FP_ACTIVATIONS[[:space:]]*=[[:space:]]*\(true\|false\).*/\1/p' "$CONFIG_SOURCE")
  case "$fp_mode" in
    true) fp_define=1 ;;
    false) fp_define=0 ;;
    *) echo "Missing or ambiguous FP_ACTIVATIONS in $CONFIG_SOURCE" >&2; exit 1 ;;
  esac
  for flag in "${TEST_FLAGS[@]}"; do
    case "$flag" in
      -DLUMIXED_FP_ACTIVATIONS=*)
        if [ "$flag" != "-DLUMIXED_FP_ACTIVATIONS=$fp_define" ]; then
          echo "Software FP_ACTIVATIONS disagrees with Config.scala" >&2; exit 1
        fi ;;
    esac
  done
  TEST_FLAGS+=("-DLUMIXED_FP_ACTIVATIONS=$fp_define")
  fp_bits=$(sed -nE 's/^[[:space:]]*FP_BITS[[:space:]]*=[[:space:]]*(16|32).*/\1/p' "$CONFIG_SOURCE")
  case "$fp_bits" in 16|32) ;; *) echo "Invalid FP_BITS in $CONFIG_SOURCE" >&2; exit 1 ;; esac
  for flag in "${TEST_FLAGS[@]}"; do
    case "$flag" in -DLUMIXED_FP_BITS=*)
      if [ "$flag" != "-DLUMIXED_FP_BITS=$fp_bits" ]; then echo "Software FP_BITS disagrees with Config.scala" >&2; exit 1; fi ;;
    esac
  done
  TEST_FLAGS+=("-DLUMIXED_FP_BITS=$fp_bits")
  grouping_mode=$(sed -n 's/^[[:space:]]*FLEXIBLE_BANK_GROUPING[[:space:]]*=[[:space:]]*\(true\|false\).*/\1/p' "$CONFIG_SOURCE")
  grouping_define=0
  if [ "$grouping_mode" = true ]; then grouping_define=1; fi
  for flag in "${TEST_FLAGS[@]}"; do
    case "$flag" in
      -DLUMIXED_FLEXIBLE_BANK_GROUPING=*)
        if [ "$flag" != "-DLUMIXED_FLEXIBLE_BANK_GROUPING=$grouping_define" ]; then
          echo "Software bank grouping disagrees with Config.scala" >&2; exit 1
        fi ;;
    esac
  done
  TEST_FLAGS+=("-DLUMIXED_FLEXIBLE_BANK_GROUPING=$grouping_define")
  aligned=$(sed -n 's/^[[:space:]]*GROUP_SCALE_ALIGNED[[:space:]]*=[[:space:]]*\(true\|false\).*/\1/p' "$CONFIG_SOURCE")
  deferred=$(sed -n 's/^[[:space:]]*GROUP_SCALE_DEFERRED[[:space:]]*=[[:space:]]*\(true\|false\).*/\1/p' "$CONFIG_SOURCE")
  scale_mode=0
  if [ "$aligned" = true ]; then scale_mode=1; fi
  if [ "$deferred" = true ]; then
    if [ "$scale_mode" != 0 ]; then echo "Group scaling flags are mutually exclusive" >&2; exit 1; fi
    scale_mode=2
  fi
  for flag in "${TEST_FLAGS[@]}"; do
    case "$flag" in -DLUMIXED_GROUP_SCALE_MODE=*)
      if [ "$flag" != "-DLUMIXED_GROUP_SCALE_MODE=$scale_mode" ]; then
        echo "Software group scaling disagrees with Config.scala" >&2; exit 1
      fi ;;
    esac
  done
  TEST_FLAGS+=("-DLUMIXED_GROUP_SCALE_MODE=$scale_mode")

fi

if [ "$MODE" = "baremetal" ]; then
  echo "Building bare-metal version..."
  riscv64-unknown-elf-gcc "${TEST_FLAGS[@]}" -fno-common -fno-builtin-printf -specs=htif_nano.specs -c "$SRC" -o lumixed_matmul_test.o
  riscv64-unknown-elf-gcc -static -specs=htif_nano.specs lumixed_matmul_test.o -o "$OUT"
  echo "Bare-metal build complete: $OUT"

elif [ "$MODE" = "linux" ]; then
  echo "Building Linux version..."
  riscv64-unknown-linux-gnu-gcc "${TEST_FLAGS[@]}" -c "$SRC" -o executable.o
  riscv64-unknown-linux-gnu-gcc -static executable.o -o "$OUT"
  echo "Linux build complete: $OUT"

else
  echo "Unknown mode: $MODE"
  echo "Usage: $0 [baremetal|linux]"
  exit 1
fi
