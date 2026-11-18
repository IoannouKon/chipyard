#!/bin/bash
# Usage: ./run_param_test.sh RIN CIN COUT [IN_BITS] [W_BITS] [debug]
# Rebuild stale/missing hardware once; use REBUILD=1 to request a build or 0 to reuse.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CHIPYARD_DIR="$(realpath "$SCRIPT_DIR/../../../../..")"
GEN_DIR="$(realpath "$SCRIPT_DIR/../../..")"
SCALA_CFG="$GEN_DIR/src/main/scala/Config.scala"
CONFIG=${CONFIG:-LUMIXEDROcketConfig}
SIM_DIR="$CHIPYARD_DIR/sims/verilator"
if [[ -f "$CHIPYARD_DIR/LUMIXED_CHECKPOINT.txt" ]]; then
  CHECKPOINT=$(cat "$CHIPYARD_DIR/LUMIXED_CHECKPOINT.txt")
  [[ ! -e "$CHECKPOINT/STOP" ]] || { echo 'Panic restore has stopped this task.' >&2; exit 2; }
  if [[ "${LUMIXED_TASK_REGISTERED:-0}" != 1 ]]; then
    exec python3 "$CHECKPOINT/run.py" env LUMIXED_TASK_REGISTERED=1 bash "$SCRIPT_DIR/run_param_test.sh" "$@"
  fi
fi
if (( $# < 3 || $# > 6 )); then
  echo "Usage: $0 RIN CIN COUT [IN_BITS=16] [W_BITS=8] [debug]" >&2; exit 2
fi
RIN=$1 CIN=$2 COUT=$3 IN_BITS=${4:-16} W_BITS=${5:-8} DEBUG_FLAG=${6:-}
for value in "$RIN" "$CIN" "$COUT"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { echo 'Dimensions must be positive integers.' >&2; exit 2; }
done
[[ "$IN_BITS" == 8 || "$IN_BITS" == 16 ]] || { echo 'IN_BITS must be 8 or 16.' >&2; exit 2; }
[[ "$W_BITS" == 2 || "$W_BITS" == 4 || "$W_BITS" == 8 ]] || { echo 'W_BITS must be 2, 4, or 8.' >&2; exit 2; }
[[ -z "$DEBUG_FLAG" || "$DEBUG_FLAG" == debug ]] || { echo 'Sixth argument must be debug.' >&2; exit 2; }
command -v riscv64-unknown-elf-gcc >/dev/null || { echo 'Source Chipyard env.sh first.' >&2; exit 2; }
extract_number() {
  sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p" "$SCALA_CFG" | head -n1
}
XS=$(extract_number x_slice) YS=$(extract_number y_slice) Mem_row_factor=$(extract_number Mem_row_factor)
[[ -n "$XS" && -n "$YS" && -n "$Mem_row_factor" ]] || { echo 'Cannot read hardware geometry.' >&2; exit 2; }
SIM="${SIM_BIN:-$SIM_DIR/simulator-chipyard.harness-$CONFIG}"
BUILD_TARGET=default
[[ "$DEBUG_FLAG" != debug ]] || { SIM+=-debug; BUILD_TARGET=debug; QUIET_TEST=${QUIET_TEST:-0}; }
REBUILD_MODE=${REBUILD:-auto}
NEEDS_BUILD=0
if [[ ! -x "$SIM" ]]; then NEEDS_BUILD=1; fi
if [[ "$REBUILD_MODE" == auto && -x "$SIM" ]]; then
  for rtl in "$GEN_DIR"/src/main/scala/*.scala; do
    if [[ "$rtl" -nt "$SIM" ]]; then NEEDS_BUILD=1; break; fi
  done
  SOC_CFG="$CHIPYARD_DIR/generators/chipyard/src/main/scala/config/LUMIXEDConfigs.scala"
  if [[ "$SOC_CFG" -nt "$SIM" || "$CHIPYARD_DIR/build.sbt" -nt "$SIM" ]]; then NEEDS_BUILD=1; fi
fi
if [[ "$REBUILD_MODE" == 1 || ( "$REBUILD_MODE" == auto && "$NEEDS_BUILD" == 1 ) ]]; then
  if [[ "$BUILD_TARGET" == debug ]]; then
    make -C "$SIM_DIR" -j"${BUILD_JOBS:-8}" CONFIG="$CONFIG" sim_debug="$SIM" debug
  else
    make -C "$SIM_DIR" -j"${BUILD_JOBS:-8}" CONFIG="$CONFIG" sim="$SIM"
  fi
fi
[[ -x "$SIM" ]] || { echo "Missing simulator: $SIM. Build with REBUILD=1." >&2; exit 2; }
C_FILE="$SCRIPT_DIR/Linear-sw.c"
update_define() { sed -i -E "s/^#define[[:space:]]+$1[[:space:]].*/#define $1 $2/" "$C_FILE"; }
update_define RIN_MAX "$RIN"; update_define CIN_MAX "$CIN"; update_define COUT_MAX "$COUT"
update_define IN_BITS "$IN_BITS"; update_define W_BITS "$W_BITS"
update_define XS "$XS"; update_define YS "$YS"; update_define Mem_row_factor "$Mem_row_factor"
update_define BAREMETAl_NEW 0
cd "$SCRIPT_DIR"
VECTOR_FLAGS=""
FAST_VECTORS=${FAST_VECTORS:-0}
if [[ "${REQUIRE_RISCV_REFERENCE:-0}" == 1 ]]; then
  [[ "$FAST_VECTORS" == 0 ]] || { echo 'This test requires a pure-C reference on RISC-V.' >&2; exit 2; }
  python3 - "$C_FILE" <<'PY_CHECK_REFERENCE'
from pathlib import Path
import re, sys
source = Path(sys.argv[1]).read_text()
for name in ('fast_mode', 'ultra_fast_mode'):
    if not re.search(r'(?m)^\s*bool\s+' + name + r'\s*=\s*false\s*;', source):
        raise SystemExit(f'{name} must be false for CPU-versus-LUMIXED correctness tests')
PY_CHECK_REFERENCE
  [[ "${TEST_CFLAGS:--O2}" != *LUMIXED_PREGENERATED_TEST* ]] || { echo 'Host-reference compiler flag is incompatible with this test.' >&2; exit 2; }
fi
if [[ "$FAST_VECTORS" == 1 ]]; then
  python3 "$SCRIPT_DIR/prepare_test_vectors.py" "$RIN" "$CIN" "$COUT" "$IN_BITS" "$W_BITS" --seed "${TEST_SEED:-1}" --pattern "${TEST_PATTERN:-0}" --output "$SCRIPT_DIR/lumixed_test_vectors.h"
  VECTOR_FLAGS="-DLUMIXED_PREGENERATED_TEST=1"
fi
TEST_CFLAGS="${TEST_CFLAGS:--O2} $VECTOR_FLAGS -DLUMIXED_QUIET_TEST=${QUIET_TEST:-1} -DLUMIXED_TEST_SEED=${TEST_SEED:-1} -DLUMIXED_TEST_PATTERN=${TEST_PATTERN:-0}" bash ./build.sh baremetal
LOG_BASE=${LUMIXED_LOG_DIR:-$SCRIPT_DIR/Log}
mkdir -p "$LOG_BASE"
LOG_BASE=$(realpath "$LOG_BASE")
if [[ "${LUMIXED_LOG_FLAT:-0}" == 1 ]]; then
  SUB_LOG_DIR="$LOG_BASE"
  LOG_FILE="$SUB_LOG_DIR/X${RIN}x${CIN}_W${CIN}x${COUT}_A${IN_BITS}_W${W_BITS}.log"
else
  SUB_LOG_DIR="$LOG_BASE/XS=${XS}_YS=${YS}_Mem_row_factor=${Mem_row_factor}"
  LOG_FILE="$SUB_LOG_DIR/RIN=${RIN}_CIN=${CIN}_COUT=${COUT}_INBITS=${IN_BITS}_WBITS=${W_BITS}.txt"
fi
mkdir -p "$SUB_LOG_DIR"
{
  printf 'CONFIG=%s XS=%s YS=%s RF=%s RIN=%s CIN=%s COUT=%s IN=%s W=%s\n' "$CONFIG" "$XS" "$YS" "$Mem_row_factor" "$RIN" "$CIN" "$COUT" "$IN_BITS" "$W_BITS"
  printf "TEST_SEED=%s QUIET_TEST=%s TEST_PATTERN=%s\n" "${TEST_SEED:-1}" "${QUIET_TEST:-1}" "${TEST_PATTERN:-0}"
  printf "FAST_VECTORS=%s\n" "$FAST_VECTORS"
  if [[ "$FAST_VECTORS" == 0 ]]; then printf 'REFERENCE=RISCV_C\n'; else printf 'REFERENCE=HOST_PYTHON\n'; fi
  sha256sum "$SIM" "$C_FILE" "$SCALA_CFG"
  if [[ "$FAST_VECTORS" == 1 ]]; then sha256sum "$SCRIPT_DIR/prepare_test_vectors.py" "$SCRIPT_DIR/lumixed_test_vectors.h"; fi
} > "$LOG_FILE"
ARGS=(+permissive +max-cycles="${TIMEOUT_CYCLES:-100000000}" +loadmem="$SCRIPT_DIR/Linear-sw.riscv")
# Preserve the original script's default memory model. DRAMSIM=1 selects DRAMSim.
if [[ "${DRAMSIM:-0}" == 1 ]]; then
  ARGS+=(+dramsim +dramsim_ini_dir="$CHIPYARD_DIR/generators/testchipip/src/main/resources/dramsim2_ini")
fi
if [[ "$DEBUG_FLAG" == debug ]]; then
  ARGS+=(+vcdfile="${LOG_FILE%.txt}.vcd")
  if [[ "${TRACE_INSTRUCTIONS:-0}" == 1 ]]; then ARGS+=(+verbose); fi
fi
ARGS+=(+permissive-off "$SCRIPT_DIR/Linear-sw.riscv")
cd "$SIM_DIR"
set +e
timeout --foreground --signal=TERM --kill-after=5s "${TIMEOUT_SECONDS:-3600}" "$SIM" "${ARGS[@]}" 2>&1 | tee -a "$LOG_FILE"
PIPE_STATUSES=("${PIPESTATUS[@]}")
SIM_STATUS=${PIPE_STATUSES[0]}
LOG_STATUS=${PIPE_STATUSES[1]}
set -e
if (( SIM_STATUS == 0 && LOG_STATUS == 0 )) && grep -q '^PASS (All elements' "$LOG_FILE" && ! grep -q '^FAIL ' "$LOG_FILE"; then
  echo 'TEST [PASS]' | tee -a "$LOG_FILE"
else
  printf 'TEST [FAIL] simulator_exit=%s log_exit=%s\n' "$SIM_STATUS" "$LOG_STATUS" | tee -a "$LOG_FILE"
  exit 1
fi
