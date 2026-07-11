#!/usr/bin/env bash
# Run the 2×2 wake recall isolation matrix (oWW-only).
#
#   A  Mac file inject
#   B  Mac acoustic (control) — Mac speaker @ MATRIX_VOLUME (default 100)
#   C  Portal file inject (portal-wake WakeBenchmark)
#   D  Portal acoustic (assistant wakebench)
#
# Usage:
#   ./matrix/run_matrix.sh                  # smoke list, all cells that can run
#   ./matrix/run_matrix.sh --full           # rebuild full clip list from manifest
#   ./matrix/run_matrix.sh --cells A,C      # subset
#   ./matrix/run_matrix.sh --quick          # tiny clip list
#   ./matrix/run_matrix.sh --skip-corpus    # assume corpus + clip list already exist
#
# Env:
#   MATRIX_VOLUME   Mac output volume for B/D (default 100)
#   ANDROID_HOME    for C/D
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

CELLS="A,B,C,D"
LIST_KIND=smoke   # smoke | full | quick
SKIP_CORPUS=0
VOLUME="${MATRIX_VOLUME:-100}"
export MATRIX_VOLUME="$VOLUME"

while [ $# -gt 0 ]; do
  case "$1" in
    --cells) CELLS="$2"; shift 2 ;;
    --full) LIST_KIND=full; shift ;;
    --quick) LIST_KIND=quick; shift ;;
    --skip-corpus) SKIP_CORPUS=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

CLIPS="$ROOT/matrix/clips_${LIST_KIND}.txt"
if [ "$LIST_KIND" = "smoke" ] && [ ! -f "$CLIPS" ]; then
  CLIPS="$ROOT/matrix/clips_smoke.txt"
fi

echo "=== wake recall matrix ==="
echo "  cells=$CELLS  list=$LIST_KIND  volume=${VOLUME}%  clips=$CLIPS"

if [ "$SKIP_CORPUS" -eq 0 ]; then
  if [ ! -d .venv ]; then
    python3.12 -m venv .venv 2>/dev/null || python3 -m venv .venv
  fi
  # shellcheck disable=SC1091
  source .venv/bin/activate
  pip install -q -e .
  pip install -q sounddevice || true

  if [ ! -f corpus/test_manifest.jsonl ]; then
    echo "=== generate corpus (say TTS) ==="
    if [ "$LIST_KIND" = "quick" ]; then
      python -m data.manifest --role test --quick
    else
      python -m data.manifest --role test
    fi
  fi
  echo "=== build clip list ($LIST_KIND) ==="
  case "$LIST_KIND" in
    full) python -m matrix.build_clip_list --full ;;
    quick) python -m matrix.build_clip_list --quick ;;
    *) python -m matrix.build_clip_list ;;
  esac
  CLIPS="$ROOT/matrix/clips_${LIST_KIND}.txt"
fi

[ -f "$CLIPS" ] || { echo "clip list missing: $CLIPS"; exit 1; }
mkdir -p results

has_cell() { [[ ",$CELLS," == *",$1,"* ]]; }

if has_cell A; then
  echo "######## CELL A — Mac file inject ########"
  python -m matrix.run_A_mac_file --clips "$CLIPS" --out results/matrix_A_mac_file.csv
fi

if has_cell B; then
  echo "######## CELL B — Mac acoustic (vol=${VOLUME}%) ########"
  python -m matrix.run_B_mac_acoustic --clips "$CLIPS" --out results/matrix_B_mac_acoustic.csv --volume "$VOLUME"
fi

if has_cell C; then
  echo "######## CELL C — Portal file inject ########"
  bash matrix/run_C_portal_file.sh "$CLIPS" results/matrix_C_portal_file.csv
fi

if has_cell D; then
  echo "######## CELL D — Portal acoustic (vol=${VOLUME}%) ########"
  bash matrix/run_D_portal_acoustic.sh "$CLIPS" results/matrix_D_portal_acoustic.csv
fi

echo "######## ANALYZE ########"
python -m matrix.analyze_matrix
echo "=== matrix complete ==="
