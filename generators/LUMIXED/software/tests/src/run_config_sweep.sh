#!/usr/bin/env bash
# Six hardware configurations, 30 correctness tests each; --reference python and --keep-design are optional.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CHIPYARD_DIR="$(realpath "$SCRIPT_DIR/../../../../..")"
BACKGROUND=0
OUTPUT=""
SHOW_HELP=0
ARGS=()
while (( $# )); do
  case "$1" in
    --background) BACKGROUND=1; shift ;;
    --output)
      (( $# >= 2 )) || { echo '--output requires a directory.' >&2; exit 2; }
      OUTPUT=$(realpath -m -- "$2")
      ARGS+=(--output "$OUTPUT")
      shift 2 ;;
    --output=*)
      [[ -n "${1#--output=}" ]] || { echo '--output requires a directory.' >&2; exit 2; }
      OUTPUT=$(realpath -m -- "${1#--output=}")
      ARGS+=(--output "$OUTPUT")
      shift ;;
    -h|--help) SHOW_HELP=1; ARGS+=("$1"); shift ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
if [[ "$SHOW_HELP" == 1 ]]; then
  printf 'Launcher option: --background detaches from SSH and saves console output and PID.\n'
elif [[ "$BACKGROUND" == 1 ]]; then
  command -v nohup >/dev/null && command -v setsid >/dev/null || { echo 'Background mode requires nohup and setsid.' >&2; exit 2; }
  if [[ -z "$OUTPUT" ]]; then
    OUTPUT="$SCRIPT_DIR/Log/run_$(date +%Y%m%d_%H%M%S)"
    ARGS+=(--output "$OUTPUT")
  fi
  mkdir -p -- "$(dirname -- "$OUTPUT")"
  nohup setsid bash "$SCRIPT_DIR/run_config_sweep.sh" "${ARGS[@]}" >> "$OUTPUT.console.log" 2>&1 < /dev/null &
  RUN_PID=$!
  printf '%s\n' "$RUN_PID" > "$OUTPUT.pid"
  printf 'Started background sweep (PID %s).\nResults: %s\nConsole: %s.console.log\nPID file: %s.pid\n' "$RUN_PID" "$OUTPUT" "$OUTPUT" "$OUTPUT"
  exit 0
fi
if [[ -f "$CHIPYARD_DIR/LUMIXED_CHECKPOINT.txt" ]]; then
  CHECKPOINT=$(cat "$CHIPYARD_DIR/LUMIXED_CHECKPOINT.txt")
  [[ ! -e "$CHECKPOINT/STOP" ]] || { echo 'Panic restore has stopped this task.' >&2; exit 2; }
  if [[ "${LUMIXED_TASK_REGISTERED:-0}" != 1 ]]; then
    exec python3 "$CHECKPOINT/run.py" env LUMIXED_TASK_REGISTERED=1 bash "$SCRIPT_DIR/run_config_sweep.sh" "${ARGS[@]}"
  fi
fi
# Conda activation hooks read optional variables before setting them.
set +u
if ! command -v conda >/dev/null; then
  CONDA_INIT="${LUMIXED_CONDA_INIT:-/home/kioannou/miniforge3/etc/profile.d/conda.sh}"
  source "$CONDA_INIT"
fi
cd "$CHIPYARD_DIR"
source ./env.sh
set -u
exec python3 "$SCRIPT_DIR/config_sweep.py" "${ARGS[@]}"
