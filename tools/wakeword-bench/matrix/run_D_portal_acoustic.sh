#!/usr/bin/env bash
# Cell D — Portal acoustic: afplay matrix clips → Portal mic (wakebench score mode).
#
# Arms assistant with files/wakebench, plays at ACOUSTIC_VOLUME (65), scores oww-score peaks.
#
# Usage:
#   bash matrix/run_D_portal_acoustic.sh [clips_list] [out_csv]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CLIPS="${1:-$ROOT/matrix/clips_smoke.txt}"
OUT="${2:-$ROOT/results/matrix_D_portal_acoustic.csv}"
VOLUME="${MATRIX_VOLUME:-65}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PKG=com.portal.assistant
FILES=/sdcard/Android/data/$PKG/files
DBG=$FILES/debug.txt

[ -f "$CLIPS" ] || { echo "clip list missing: $CLIPS"; exit 1; }

osascript -e "set volume output volume $VOLUME" >/dev/null
echo "D Portal acoustic — volume=${VOLUME}%  clips=$(grep -cve '^#\|^$' "$CLIPS" || true)"

# Keep screen awake (A10 silences mic when display dozes).
"$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null || true
"$ADB" shell svc power stayon true </dev/null || true
"$ADB" shell "settings put system screen_off_timeout 1800000" </dev/null || true

echo "=== D: arm wakebench (force-stop → marker → launch) ==="
"$ADB" shell am force-stop "$PKG" </dev/null
sleep 1
"$ADB" shell "touch $FILES/wakebench; rm -f $FILES/wakedump" </dev/null
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 </dev/null
echo "arming detector…"; sleep 8
ready=$("$ADB" shell "grep 'wake detector ready' $DBG | tail -1" </dev/null || true)
echo "  $ready"

# Convert matrix list → absolute paths for afplay loop (reuse device_bench logic inline).
LIST_ABS="$(mktemp /tmp/matrix_D_XXXXXX.txt)"
trap 'rm -f "$LIST_ABS"' EXIT
while IFS='|' read -r label category condition relpath; do
  [ -z "${label:-}" ] && continue
  [[ "$label" =~ ^# ]] && continue
  src="$ROOT/$relpath"
  [ -f "$src" ] || { echo "missing $src"; exit 1; }
  echo "${src}|${category}|${condition}" >> "$LIST_ABS"
done < "$CLIPS"

mkdir -p "$(dirname "$OUT")"
echo "label,category,condition,peak,fired,peak_rms" > "$OUT"
n=0
total=$(wc -l < "$LIST_ABS" | tr -d ' ')
while IFS='|' read -r path category condition <&3; do
  [ -z "$path" ] && continue
  n=$((n+1))
  if [ $((n % 20)) -eq 1 ]; then "$ADB" shell input keyevent KEYCODE_WAKEUP </dev/null; fi
  base=$("$ADB" shell "wc -l < $DBG" </dev/null | tr -d '\r ')
  afplay "$path" </dev/null
  sleep 1.6
  new=$("$ADB" shell "tail -n +$((base+1)) $DBG" </dev/null)
  peak=$(echo "$new" | grep -oE 'oww-score jarvis [0-9.]+' | awk '{print $3}' | sort -rn | head -1)
  [ -z "$peak" ] && peak=0.000
  prms=$(echo "$new" | grep -oE 'rms=[0-9]+' | sed 's/rms=//' | sort -rn | head -1)
  [ -z "$prms" ] && prms=0
  # fired at product threshold 0.5
  fired=$(python3 -c "print(1 if float('$peak') >= 0.5 else 0)")
  label=$(basename "$path")
  echo "$label,$category,$condition,$peak,$fired,$prms" >> "$OUT"
  printf "  D [%d/%d] %-34s peak=%s fired=%s\n" "$n" "$total" "$label" "$peak" "$fired"
done 3< "$LIST_ABS"
echo "D done → $OUT"
