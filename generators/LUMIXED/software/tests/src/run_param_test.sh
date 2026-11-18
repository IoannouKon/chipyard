#!/bin/bash
# Usage: ./run_param_test.sh RIN CIN COUT [IN_BITS] [W_BITS] [debug]
#
# Reference modes:
#   FAST_VECTORS=1 (default): Python generates test vectors and expected outputs.
#   FAST_VECTORS=0          : RISC-V C generates and checks the reference.
#
# Simulator build:
#   REBUILD=auto (default): Rebuild when RTL/configuration is newer than simulator.
#   REBUILD=1             : Always rebuild.
#   REBUILD=0             : Reuse the existing simulator.
#   BUILD_JOBS=1 (default): Parallel make job count.

set -euo pipefail

# -----------------------------------------------------------------------------
# 1. Project paths and simulator configuration
# -----------------------------------------------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CHIPYARD_DIR="$(realpath "$SCRIPT_DIR/../../../../..")"
GEN_DIR="$(realpath "$SCRIPT_DIR/../../..")"
SCALA_CFG="$GEN_DIR/src/main/scala/Config.scala"
SIM_DIR="$CHIPYARD_DIR/sims/verilator"
CONFIG=${CONFIG:-LUMIXEDROcketConfig}

# -----------------------------------------------------------------------------
# 2. Command-line arguments and validation
# -----------------------------------------------------------------------------
if (( $# < 3 || $# > 6 )); then
  echo "Usage: $0 RIN CIN COUT [IN_BITS=16] [W_BITS=8] [debug]" >&2
  exit 2
fi

RIN=$1
CIN=$2
COUT=$3
IN_BITS=${4:-16}
W_BITS=${5:-8}
DEBUG_FLAG=${6:-}

for value in "$RIN" "$CIN" "$COUT"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || {
    echo 'Dimensions must be positive integers.' >&2
    exit 2
  }
done

[[ "$IN_BITS" == 8 || "$IN_BITS" == 16 ]] || {
  echo 'IN_BITS must be 8 or 16.' >&2
  exit 2
}

[[ "$W_BITS" == 2 || "$W_BITS" == 4 || "$W_BITS" == 8 ]] || {
  echo 'W_BITS must be 2, 4, or 8.' >&2
  exit 2
}

[[ -z "$DEBUG_FLAG" || "$DEBUG_FLAG" == debug ]] || {
  echo 'Sixth argument must be debug.' >&2
  exit 2
}

# -----------------------------------------------------------------------------
# 3. Test-reference mode and input compatibility checks
# -----------------------------------------------------------------------------
FAST_VECTORS=${FAST_VECTORS:-1}

[[ "$FAST_VECTORS" == 0 || "$FAST_VECTORS" == 1 ]] || {
  echo 'FAST_VECTORS must be 0 or 1.' >&2
  exit 2
}

# Python supports integer, FP16, and both finite group-scaled FP16 modes.
# Packed W2/W4 tails are allocated with ceil-byte capacity in Linear-sw.c.
command -v riscv64-unknown-elf-gcc >/dev/null || {
  echo 'Source Chipyard env.sh first.' >&2
  exit 2
}

# -----------------------------------------------------------------------------
# 4. Read hardware geometry from Config.scala
# -----------------------------------------------------------------------------
extract_number() {
  sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p" "$SCALA_CFG" | head -n1
}

XS=$(extract_number banks_per_row)
YS=$(extract_number parallel_rows)
Mem_row_factor=$(extract_number table_capacity_factor)

[[ -n "$XS" && -n "$YS" && -n "$Mem_row_factor" ]] || {
  echo 'Cannot read hardware geometry.' >&2
  exit 2
}

# Mathematical mode comes from hardware Config.scala, not from the user CLI.
# ALIGNED and DEFERRED are distinct RTL schedules of the SAME group-scaled math.
extract_bool() {
  sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*(true|false).*/\1/p" "$SCALA_CFG" | head -n1
}
FP16_FLAG=$(extract_bool FP16_ACTIVATIONS)
ALIGNED_FLAG=$(extract_bool GROUP_SCALE_ALIGNED)
DEFERRED_FLAG=$(extract_bool GROUP_SCALE_DEFERRED)

[[ "$FP16_FLAG" == true || "$FP16_FLAG" == false ]] || {
  echo 'Cannot read FP16_ACTIVATIONS from Config.scala.' >&2
  exit 2
}
FP16_MODE=0
[[ "$FP16_FLAG" != true ]] || FP16_MODE=1

GROUP_SCALE_MODE=0
if [[ "$ALIGNED_FLAG" == true ]]; then GROUP_SCALE_MODE=1; fi
if [[ "$DEFERRED_FLAG" == true ]]; then
  [[ "$GROUP_SCALE_MODE" == 0 ]] || {
    echo 'GROUP_SCALE_ALIGNED and GROUP_SCALE_DEFERRED cannot both be enabled.' >&2
    exit 2
  }
  GROUP_SCALE_MODE=2
fi

GROUP_SIZE=${GROUP_SIZE:-64}
[[ "$GROUP_SIZE" == 32 || "$GROUP_SIZE" == 64 || "$GROUP_SIZE" == 128 ]] || {
  echo 'GROUP_SIZE must be 32, 64, or 128.' >&2
  exit 2
}

if (( FP16_MODE )) && [[ "$IN_BITS" != 16 ]]; then
  echo 'FP16_ACTIVATIONS=true requires IN_BITS=16.' >&2
  exit 2
fi
if (( GROUP_SCALE_MODE )) && { [[ "$FP16_MODE" != 1 ]] || [[ "$W_BITS" != 2 && "$W_BITS" != 4 ]]; }; then
  echo 'Group scaling requires FP16_ACTIVATIONS=true and W_BITS=2 or 4.' >&2
  exit 2
fi
if (( GROUP_SCALE_MODE )) && [[ "$FAST_VECTORS" != 1 ]]; then
  echo 'Group-scaling tests require FAST_VECTORS=1; C reference has no per-group scaling.' >&2
  exit 2
fi

# -----------------------------------------------------------------------------
# 5. Select and rebuild the Verilator simulator if necessary
# -----------------------------------------------------------------------------
SIM="${SIM_BIN:-$SIM_DIR/simulator-chipyard.harness-$CONFIG}"
BUILD_TARGET=default

if [[ "$DEBUG_FLAG" == debug ]]; then
  SIM+=-debug
  BUILD_TARGET=debug
  QUIET_TEST=${QUIET_TEST:-0}
fi

REBUILD_MODE=${REBUILD:-auto}
NEEDS_BUILD=0

if [[ ! -x "$SIM" ]]; then
  NEEDS_BUILD=1
fi

if [[ "$REBUILD_MODE" == auto && -x "$SIM" ]]; then
  for rtl in "$GEN_DIR"/src/main/scala/*.scala; do
    if [[ "$rtl" -nt "$SIM" ]]; then
      NEEDS_BUILD=1
      break
    fi
  done

  SOC_CFG="$CHIPYARD_DIR/generators/chipyard/src/main/scala/config/LUMIXEDConfigs.scala"
  if [[ "$SOC_CFG" -nt "$SIM" || "$CHIPYARD_DIR/build.sbt" -nt "$SIM" ]]; then
    NEEDS_BUILD=1
  fi
fi

if [[ "$REBUILD_MODE" == 1 || ( "$REBUILD_MODE" == auto && "$NEEDS_BUILD" == 1 ) ]]; then
  if [[ "$BUILD_TARGET" == debug ]]; then
    make -C "$SIM_DIR" -j"${BUILD_JOBS:-1}" CONFIG="$CONFIG" sim_debug="$SIM" debug
  else
    make -C "$SIM_DIR" -j"${BUILD_JOBS:-1}" CONFIG="$CONFIG" sim="$SIM"
  fi
fi

[[ -x "$SIM" ]] || {
  echo "Missing simulator: $SIM. Build with REBUILD=1." >&2
  exit 2
}

# -----------------------------------------------------------------------------
# 6. Update Linear-sw.c dimensions, precisions, and hardware geometry
# -----------------------------------------------------------------------------
C_FILE="$SCRIPT_DIR/Linear-sw.c"

update_define() {
  sed -i -E "s/^#define[[:space:]]+$1[[:space:]].*/#define $1 $2/" "$C_FILE"
}

update_define RIN_MAX "$RIN"
update_define CIN_MAX "$CIN"
update_define COUT_MAX "$COUT"
update_define IN_BITS "$IN_BITS"
update_define W_BITS "$W_BITS"
update_define XS "$XS"
update_define YS "$YS"
update_define Mem_row_factor "$Mem_row_factor"

# Legacy SCALE=1 is a separate integer-only math mode not yet covered.
if [[ "$FAST_VECTORS" == 1 ]] && grep -Eq '^#define[[:space:]]+SCALE[[:space:]]+1([[:space:]]|$)' "$C_FILE"; then
  echo 'Python reference does not cover legacy SCALE=1; use SCALE=0 or FAST_VECTORS=0.' >&2
  exit 2
fi

# -----------------------------------------------------------------------------
# 7. Generate Python reference (optional) and compile the RISC-V C test
# -----------------------------------------------------------------------------
cd "$SCRIPT_DIR"

VECTOR_FLAGS=""

if [[ "$FAST_VECTORS" == 1 ]]; then
  python3 "$SCRIPT_DIR/prepare_test_vectors.py" \
    "$RIN" "$CIN" "$COUT" "$IN_BITS" "$W_BITS" \
    --seed "${TEST_SEED:-1}" \
    --pattern "${TEST_PATTERN:-0}" \
    --fp16-activations "$FP16_MODE" \
    --group-scale-mode "$GROUP_SCALE_MODE" \
    --group-size "$GROUP_SIZE" \
    --output "$SCRIPT_DIR/lumixed_test_vectors.h"

  VECTOR_FLAGS="-DLUMIXED_PREGENERATED_TEST=1"
fi

TEST_CFLAGS="${TEST_CFLAGS:--O2} $VECTOR_FLAGS -DLUMIXED_FP16_ACTIVATIONS=$FP16_MODE -DLUMIXED_GROUP_SCALE_MODE=$GROUP_SCALE_MODE -DLUMIXED_QUIET_TEST=${QUIET_TEST:-1} -DLUMIXED_TEST_SEED=${TEST_SEED:-1} -DLUMIXED_TEST_PATTERN=${TEST_PATTERN:-0}" \
  bash ./build.sh baremetal

# -----------------------------------------------------------------------------
# 8. Choose the log path and write test metadata
# -----------------------------------------------------------------------------
LOG_BASE=${LUMIXED_LOG_DIR:-$SCRIPT_DIR/Log}
mkdir -p "$LOG_BASE"
LOG_BASE=$(realpath "$LOG_BASE")

if [[ "${LUMIXED_LOG_FLAT:-0}" == 1 ]]; then
  SUB_LOG_DIR="$LOG_BASE"
  LOG_FILE="$SUB_LOG_DIR/X${RIN}x${CIN}_W${CIN}x${COUT}_A${IN_BITS}_W${W_BITS}_FP16${FP16_MODE}_GS${GROUP_SCALE_MODE}_G${GROUP_SIZE}.log"
else
  SUB_LOG_DIR="$LOG_BASE/XS=${XS}_YS=${YS}_Mem_row_factor=${Mem_row_factor}"
  LOG_FILE="$SUB_LOG_DIR/RIN=${RIN}_CIN=${CIN}_COUT=${COUT}_INBITS=${IN_BITS}_WBITS=${W_BITS}_FP16=${FP16_MODE}_GROUPMODE=${GROUP_SCALE_MODE}_G=${GROUP_SIZE}.txt"
fi

mkdir -p "$SUB_LOG_DIR"

{
  printf 'CONFIG=%s XS=%s YS=%s RF=%s RIN=%s CIN=%s COUT=%s IN=%s W=%s\n' \
    "$CONFIG" "$XS" "$YS" "$Mem_row_factor" "$RIN" "$CIN" "$COUT" "$IN_BITS" "$W_BITS"
  printf 'TEST_SEED=%s QUIET_TEST=%s TEST_PATTERN=%s\n' \
    "${TEST_SEED:-1}" "${QUIET_TEST:-1}" "${TEST_PATTERN:-0}"
  printf 'FAST_VECTORS=%s\n' "$FAST_VECTORS"
  printf 'FP16_ACTIVATIONS=%s GROUP_SCALE_MODE=%s GROUP_SIZE=%s\n' \
    "$FP16_MODE" "$GROUP_SCALE_MODE" "$GROUP_SIZE"

  if [[ "$FAST_VECTORS" == 0 ]]; then
    printf 'REFERENCE=RISCV_C\n'
  else
    printf 'REFERENCE=HOST_PYTHON\n'
  fi

  sha256sum "$SIM" "$C_FILE" "$SCALA_CFG"

  if [[ "$FAST_VECTORS" == 1 ]]; then
    sha256sum "$SCRIPT_DIR/prepare_test_vectors.py" "$SCRIPT_DIR/lumixed_test_vectors.h"
  fi
} > "$LOG_FILE"

# -----------------------------------------------------------------------------
# 9. Prepare Verilator command-line arguments
# -----------------------------------------------------------------------------
ARGS=(
  +permissive
  +max-cycles="${TIMEOUT_CYCLES:-100000000}"
  +loadmem="$SCRIPT_DIR/Linear-sw.riscv"
)

# Preserve the original memory model unless DRAMSIM=1 is selected.
if [[ "${DRAMSIM:-0}" == 1 ]]; then
  ARGS+=(
    +dramsim
    +dramsim_ini_dir="$CHIPYARD_DIR/generators/testchipip/src/main/resources/dramsim2_ini"
  )
fi

if [[ "$DEBUG_FLAG" == debug ]]; then
  ARGS+=(+vcdfile="${LOG_FILE%.txt}.vcd")

  if [[ "${TRACE_INSTRUCTIONS:-0}" == 1 ]]; then
    ARGS+=(+verbose)
  fi
fi

ARGS+=(+permissive-off "$SCRIPT_DIR/Linear-sw.riscv")

# -----------------------------------------------------------------------------
# 10. Run Verilator and preserve both simulator and logging exit statuses
# -----------------------------------------------------------------------------
cd "$SIM_DIR"

set +e
timeout --foreground --signal=TERM --kill-after=5s \
  "${TIMEOUT_SECONDS:-3600}" "$SIM" "${ARGS[@]}" 2>&1 | tee -a "$LOG_FILE"
PIPE_STATUSES=("${PIPESTATUS[@]}")
SIM_STATUS=${PIPE_STATUSES[0]}
LOG_STATUS=${PIPE_STATUSES[1]}
set -e

# -----------------------------------------------------------------------------
# 11. Report PASS/FAIL based on the C reference comparison
# -----------------------------------------------------------------------------
if (( SIM_STATUS == 0 && LOG_STATUS == 0 )) && \
   grep -q '^PASS (All elements' "$LOG_FILE" && \
   ! grep -q '^FAIL ' "$LOG_FILE"; then
  echo 'TEST [PASS]' | tee -a "$LOG_FILE"
else
  printf 'TEST [FAIL] simulator_exit=%s log_exit=%s\n' \
    "$SIM_STATUS" "$LOG_STATUS" | tee -a "$LOG_FILE"
  exit 1
fi
