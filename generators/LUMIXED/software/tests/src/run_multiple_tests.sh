#!/bin/bash
# Run all requested cases sequentially; "on" resumes by skipping passing logs.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CHIPYARD_DIR="$(realpath "$SCRIPT_DIR/../../../../..")"
if [[ -f "$CHIPYARD_DIR/LUMIXED_CHECKPOINT.txt" ]]; then
  CHECKPOINT=$(cat "$CHIPYARD_DIR/LUMIXED_CHECKPOINT.txt")
  [[ ! -e "$CHECKPOINT/STOP" ]] || { echo 'Panic restore has stopped this task.' >&2; exit 2; }
  if [[ "${LUMIXED_TASK_REGISTERED:-0}" != 1 ]]; then
    exec python3 "$CHECKPOINT/run.py" env LUMIXED_TASK_REGISTERED=1 bash "$SCRIPT_DIR/run_multiple_tests.sh" "$@"
  fi
fi
SKIP_EXISTING=${1:-off}
[[ "$SKIP_EXISTING" == off || "$SKIP_EXISTING" == on ]] || { echo "Usage: $0 [on|off]" >&2; exit 2; }
TRIPLETS=("1,4,4" "1,8,8" "1,16,16" "1,32,32" "1,64,64" "1,128,128" "1,256,256")
W_BITS_LIST=(2 4 8)
IN_BITS=16
SCALA_CFG="$SCRIPT_DIR/../../../src/main/scala/Config.scala"
extract_number() { sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p" "$SCALA_CFG" | head -n1; }
XS=$(extract_number x_slice) YS=$(extract_number y_slice) RF=$(extract_number Mem_row_factor)
export LUMIXED_LOG_DIR=${LUMIXED_LOG_DIR:-$SCRIPT_DIR/Log/sweep_$(date +%Y%m%d_%H%M%S)}
LOG_DIR="$LUMIXED_LOG_DIR/XS=${XS}_YS=${YS}_Mem_row_factor=${RF}"
mkdir -p "$LOG_DIR"
SUMMARY="$LUMIXED_LOG_DIR/summary.csv"
printf 'RIN,CIN,COUT,IN_BITS,W_BITS,status,log\n' > "$SUMMARY"
FAILED=0 PASSED=0
# Optional rebuild happens only once, before the first case.
REBUILD_FIRST=${REBUILD:-auto}
for item in "${TRIPLETS[@]}"; do
  IFS=',' read -r RIN CIN COUT <<< "$item"
  for W_BITS in "${W_BITS_LIST[@]}"; do
    LOG="$LOG_DIR/RIN=${RIN}_CIN=${CIN}_COUT=${COUT}_INBITS=${IN_BITS}_WBITS=${W_BITS}.txt"
    if [[ "$SKIP_EXISTING" == on && -f "$LOG" ]] && grep -q '^TEST \[PASS\]$' "$LOG"; then
      STATUS=PASS
      echo "Skipping passing case: $RIN $CIN $COUT $IN_BITS $W_BITS"
    elif REBUILD="$REBUILD_FIRST" bash "$SCRIPT_DIR/run_param_test.sh" "$RIN" "$CIN" "$COUT" "$IN_BITS" "$W_BITS"; then
      STATUS=PASS
    else
      STATUS=FAIL
    fi
    REBUILD_FIRST=0
    if [[ "$STATUS" == PASS ]]; then PASSED=$((PASSED+1)); else FAILED=$((FAILED+1)); fi
    printf '%s,%s,%s,%s,%s,%s,%s\n' "$RIN" "$CIN" "$COUT" "$IN_BITS" "$W_BITS" "$STATUS" "$LOG" >> "$SUMMARY"
  done
done
printf 'Sweep finished: %s PASS, %s FAIL. Summary: %s\n' "$PASSED" "$FAILED" "$SUMMARY"
(( FAILED == 0 ))
