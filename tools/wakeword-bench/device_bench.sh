#!/usr/bin/env bash
# On-device acoustic benchmark: play corpus clips out loud, capture the detector's response.
#
# Two modes:
#   oww   — the openWakeWord build in bench mode (files/wakebench present): captures the PEAK
#           per-frame score per clip from "oww-score jarvis <s>" log lines (no firing).
#   vosk  — the old Vosk build (normal mode): records fired/not from "wake detected" log lines
#           (its shipped production decision). Spaces clips so the post-fire conversation re-arms.
#
# Usage: device_bench.sh <oww|vosk> <clip_list_file> <out_csv>
set -u
MODE="$1"; LIST="$2"; OUT="$3"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=com.portal.assistant
DBG=/sdcard/Android/data/$PKG/files/debug.txt

osascript -e 'set volume output volume 62' >/dev/null
# Arm ONCE up front and wait for the detector to load (never relaunch mid-run — bench mode never pauses).
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 </dev/null
echo "arming detector…"; sleep 8

echo "label,category,condition,peak,fired,peak_rms" > "$OUT"
n=0; total=$(wc -l < "$LIST" | tr -d ' ')
while IFS='|' read -r path category condition <&3; do
  [ -z "$path" ] && continue
  n=$((n+1))
  # Keep the screen awake every ~20 clips — Android 10 silences the mic when the display dozes.
  if [ $((n % 20)) -eq 1 ]; then $ADB shell input keyevent KEYCODE_WAKEUP </dev/null; fi
  base=$($ADB shell "wc -l < $DBG" </dev/null | tr -d '\r ')
  afplay "$path" </dev/null
  sleep 1.6
  new=$($ADB shell "tail -n +$((base+1)) $DBG" </dev/null)
  prms=""
  if [ "$MODE" = oww ]; then
    peak=$(echo "$new" | grep -oE 'oww-score jarvis [0-9.]+' | awk '{print $3}' | sort -rn | head -1)
    [ -z "$peak" ] && peak=0.000
    prms=$(echo "$new" | grep -oE 'rms=[0-9]+' | sed 's/rms=//' | sort -rn | head -1)
    [ -z "$prms" ] && prms=0
    fired=""
  else
    peak=""
    if echo "$new" | grep -q 'wake detected'; then fired=1; else fired=0; fi
    sleep 4   # let any triggered conversation end + re-arm before the next clip
  fi
  label=$(basename "$path")
  echo "$label,$category,$condition,$peak,$fired,$prms" >> "$OUT"
  printf "  [%s] %d/%d  %-34s peak=%s rms=%s fired=%s\n" "$MODE" "$n" "$total" "$label" "${peak:--}" "${prms:--}" "${fired:--}"
done 3< "$LIST"
echo "wrote $OUT"
