#!/usr/bin/env bash
# Cell C — Portal file inject: push matrix WAVs → WakeBenchmark (oWW-only) → results CSV.
#
# Usage:
#   bash matrix/run_C_portal_file.sh [clips_list] [out_csv]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# wakeword-bench → tools → portal-assistant → portal-apps/portal-wake
WAKE_ROOT="$(cd "$ROOT/../../../portal-wake" && pwd)"
CLIPS="${1:-$ROOT/matrix/clips_smoke.txt}"
OUT="${2:-$ROOT/results/matrix_C_portal_file.csv}"
PKG=com.portal.wake
DEVICE_BENCH="/sdcard/Android/data/$PKG/files/bench"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
export JAVA_HOME="${JAVA_HOME:-/usr/local/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

[ -f "$CLIPS" ] || { echo "clip list missing: $CLIPS"; exit 1; }
[ -d "$WAKE_ROOT" ] || { echo "portal-wake not found at $WAKE_ROOT"; exit 1; }

echo "=== C: build + install portal-wake (app + androidTest) ==="
( cd "$WAKE_ROOT" && ./gradlew --console=plain installDebug installDebugAndroidTest )

STAGING="$(mktemp -d /tmp/matrix_C_XXXXXX)"
trap 'rm -rf "$STAGING"' EXIT
echo "=== C: stage corpus from $CLIPS ==="
while IFS='|' read -r label category condition relpath; do
  [ -z "${label:-}" ] && continue
  [[ "$label" =~ ^# ]] && continue
  src="$ROOT/$relpath"
  [ -f "$src" ] || { echo "missing $src"; exit 1; }
  mkdir -p "$STAGING/$category"
  cp "$src" "$STAGING/$category/$label"
done < "$CLIPS"
n=$(find "$STAGING" -name '*.wav' | wc -l | tr -d ' ')
echo "staged $n wavs"

echo "=== C: push → $DEVICE_BENCH/corpus ==="
"$ADB" shell "rm -rf $DEVICE_BENCH/corpus" </dev/null || true
"$ADB" shell "mkdir -p $DEVICE_BENCH/corpus" </dev/null
for catdir in "$STAGING"/*; do
  [ -d "$catdir" ] || continue
  cat=$(basename "$catdir")
  "$ADB" push "$catdir" "$DEVICE_BENCH/corpus/$cat" >/dev/null
done
echo "on device: $("$ADB" shell "find $DEVICE_BENCH/corpus -name '*.wav' | wc -l" | tr -d ' \r') wavs"

echo "=== C: run WakeBenchmark ==="
"$ADB" shell am instrument -w \
  -e class "$PKG.benchmark.WakeBenchmark" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$ROOT/results/matrix_C_instrument.log"

RAW="$ROOT/results/matrix_C_raw.csv"
"$ADB" pull "$DEVICE_BENCH/results.csv" "$RAW"

echo "=== C: map to matrix CSV → $OUT ==="
python3 - "$CLIPS" "$RAW" "$OUT" <<'PY'
import csv, sys
from pathlib import Path
clips_path, raw_path, out_path = sys.argv[1:4]
# label -> (category, condition) from clip list
meta = {}
for ln in Path(clips_path).read_text().splitlines():
    if not ln.strip() or ln.startswith("#"): continue
    parts = ln.split("|")
    if len(parts) >= 4:
        label, cat, cond, _ = parts[0], parts[1], parts[2], parts[3]
        meta[label] = (cat, cond)
rows_out = []
with open(raw_path) as f:
    for r in csv.DictReader(f):
        label = r["file"]
        cat, cond = meta.get(label, (r.get("category", ""), ""))
        peak = float(r.get("peak_score") or 0)
        fired = 1 if r.get("fired", "").strip().lower() == "true" else 0
        rows_out.append({
            "label": label,
            "category": cat,
            "condition": cond,
            "peak": f"{peak:.3f}",
            "fired": fired,
            "duration_ms": r.get("duration_ms", ""),
        })
Path(out_path).parent.mkdir(parents=True, exist_ok=True)
with open(out_path, "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=["label","category","condition","peak","fired","duration_ms"])
    w.writeheader()
    w.writerows(rows_out)
print(f"wrote {len(rows_out)} rows → {out_path}")
PY
echo "C done"
