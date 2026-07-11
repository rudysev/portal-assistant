#!/usr/bin/env bash
# Speaker→mic acoustic A/B: play corpus clips out loud; capture oWW + Vosk fires from debug.txt.
#
# Requires:
#   - Portal connected, assistant installed with Vosk shadow model present
#   - files/wakebench marker (detection-only; no conversation handoff; cooldown=0 so both can fire)
#   - App foreground (gen2 mic only works while on screen)
#   - Mac speaker volume 0–50% (caller sets via osascript)
#
# Usage: device_bench_dual.sh <clip_list_file> <out_csv> [gap_s]
# clip_list lines: path|category|condition
set -euo pipefail
LIST="$1"; OUT="$2"; GAP="${3:-2.0}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=com.portal.assistant
FILES=/sdcard/Android/data/$PKG/files
DBG=$FILES/debug.txt

$ADB shell "touch $FILES/wakebench" </dev/null
# Keep screen on + launch foreground so gen2 mic is live
$ADB shell "svc power stayon true" </dev/null || true
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 </dev/null
echo "arming detector (wakebench)…"; sleep 10

echo "label,category,condition,oww_fired,vosk_fired,oww_detail,vosk_detail" > "$OUT"
n=0; total=$(wc -l < "$LIST" | tr -d ' ')
while IFS='|' read -r path category condition <&3; do
  [ -z "${path:-}" ] && continue
  [ ! -f "$path" ] && { echo "missing $path"; continue; }
  n=$((n+1))
  base=$($ADB shell "wc -l < $DBG" </dev/null | tr -d '\r ')
  afplay "$path" </dev/null
  sleep "$GAP"
  new=$($ADB shell "tail -n +$((base+1)) $DBG" </dev/null)
  oww_line=$(echo "$new" | grep -E 'wake detected \(oww\)' | tail -1 || true)
  vosk_line=$(echo "$new" | grep -E 'wake detected \(vosk\)' | tail -1 || true)
  oww_fired=0; [ -n "$oww_line" ] && oww_fired=1
  vosk_fired=0; [ -n "$vosk_line" ] && vosk_fired=1
  # strip commas for CSV
  oww_detail=$(echo "$oww_line" | tr ',' ';' | tr -d '\r')
  vosk_detail=$(echo "$vosk_line" | tr ',' ';' | tr -d '\r')
  label=$(basename "$path")
  echo "$label,$category,$condition,$oww_fired,$vosk_fired,$oww_detail,$vosk_detail" >> "$OUT"
  printf "  [%3d/%d] %-40s oww=%s vosk=%s\n" "$n" "$total" "$label" "$oww_fired" "$vosk_fired"
done 3< "$LIST"
echo "wrote $OUT"
