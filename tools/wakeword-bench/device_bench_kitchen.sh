#!/usr/bin/env bash
# Kitchen / same-device playback A/B: Portal speakers loop music|speech while Mac plays wake clips
# into the room mic. Measures the openWakeWord AEC warning (playback on the capturing device).
#
# Usage:
#   device_bench_kitchen.sh quiet|music|speech <clip_list> <out_csv> [gap_s] [mac_vol_pct] [device_music_pct]
#
# quiet  — no on-device interferer (baseline)
# music  — loop synthesized music from Portal speakers (STREAM_MUSIC)
# speech — loop concatenated background-speech WAV from Portal speakers
set -euo pipefail
MODE="${1:?quiet|music|speech}"; LIST="${2:?}"; OUT="${3:?}"; GAP="${4:-2.0}"; MACVOL="${5:-40}"; DEVOL="${6:-40}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=com.portal.assistant
FILES=/sdcard/Android/data/$PKG/files
INTERFERER_LOCAL="$HERE/interferers"
INTERFERER_DEV="$FILES/wakeinterferer.wav"

mkdir -p "$INTERFERER_LOCAL"

gen_music() {
  local out="$INTERFERER_LOCAL/kitchen_music.wav"
  [ -f "$out" ] && [ -s "$out" ] && { echo "$out"; return; }
  # ~90s stereo-ish mono music bed: layered tones + soft noise (no copyrighted audio).
  ffmpeg -y -hide_banner -loglevel error \
    -f lavfi -i "sine=frequency=110:duration=90" \
    -f lavfi -i "sine=frequency=165:duration=90" \
    -f lavfi -i "sine=frequency=220:duration=90" \
    -f lavfi -i "anoisesrc=color=pink:amplitude=0.05:duration=90" \
    -filter_complex "[0][1][2][3]amix=inputs=4:normalize=0,volume=0.35,aformat=sample_fmts=s16:sample_rates=44100:channel_layouts=mono" \
    "$out"
  echo "$out"
}

gen_speech() {
  local out="$INTERFERER_LOCAL/kitchen_speech.wav"
  [ -f "$out" ] && [ -s "$out" ] && { echo "$out"; return; }
  local bg_dir="/Users/rudys/Documents/Software/portal-apps/portal-wake/benchmark/corpus/background"
  local list="$INTERFERER_LOCAL/speech_concat.txt"
  : > "$list"
  # Repeat background clips to ~60s
  local i=0
  while [ "$i" -lt 20 ]; do
    for f in "$bg_dir"/*.wav; do
      [ -f "$f" ] || continue
      echo "file '$f'" >> "$list"
      i=$((i+1))
      [ "$i" -ge 20 ] && break
    done
  done
  ffmpeg -y -hide_banner -loglevel error -f concat -safe 0 -i "$list" \
    -ac 1 -ar 16000 -sample_fmt s16 "$out"
  echo "$out"
}

osascript -e "set volume output volume $MACVOL" >/dev/null

$ADB shell "mkdir -p $FILES" </dev/null
$ADB shell "rm -f $INTERFERER_DEV $FILES/wakeinterferer_vol" </dev/null || true

case "$MODE" in
  quiet)
    echo "mode=quiet (no on-device interferer)"
    ;;
  music)
    src=$(gen_music)
    echo "mode=music → push $src (device music vol ${DEVOL}%)"
    $ADB push "$src" "$INTERFERER_DEV" >/dev/null
    $ADB shell "echo -n $DEVOL > $FILES/wakeinterferer_vol" </dev/null
    ;;
  speech)
    src=$(gen_speech)
    echo "mode=speech → push $src (device music vol ${DEVOL}%)"
    $ADB push "$src" "$INTERFERER_DEV" >/dev/null
    $ADB shell "echo -n $DEVOL > $FILES/wakeinterferer_vol" </dev/null
    ;;
  *)
    echo "unknown mode: $MODE (want quiet|music|speech)" >&2; exit 2
    ;;
esac

# Force-stop so enterDetection re-reads interferer file / absence
$ADB shell am force-stop $PKG </dev/null
$ADB shell "rm -f $FILES/debug.txt; touch $FILES/debug.txt; touch $FILES/wakebench" </dev/null

exec "$HERE/device_bench_dual.sh" "$LIST" "$OUT" "$GAP"
