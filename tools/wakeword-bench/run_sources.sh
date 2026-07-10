#!/usr/bin/env bash
# Drive capture configs through the on-device benchmark. Per config: keep screen awake (A10 silences the
# mic when the display is off), force-stop, set markers, cold-launch, VERIFY the source/gain applied, then
# device_bench.sh scores the 97 clips. UNPROCESSED is dropped — the Portal doesn't support it (falls back
# to VOICE_RECOGNITION).
set -u
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
FILES=/sdcard/Android/data/com.portal.assistant/files
DBG=$FILES/debug.txt
PKG=com.portal.assistant
cd "$(dirname "$0")"

awake() { $ADB shell input keyevent KEYCODE_WAKEUP </dev/null; $ADB shell svc power stayon true </dev/null; }
$ADB shell "settings put system screen_off_timeout 1800000" </dev/null
$ADB shell dumpsys deviceidle disable </dev/null >/dev/null 2>&1

run() { # name  wakesrc  wakegain  expect_src_int
  local name="$1" src="$2" gain="$3" esrc="$4"
  echo "############### CONFIG: $name (src=$src gain=$gain) ###############"
  awake
  local tries=0
  while [ $tries -lt 3 ]; do
    $ADB shell am force-stop $PKG </dev/null; sleep 1
    $ADB shell "echo -n $src > $FILES/wakesrc; echo -n $gain > $FILES/wakegain; touch $FILES/wakebench; rm -f $FILES/wakedump" </dev/null
    awake
    $ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 </dev/null; sleep 8
    local ready=$($ADB shell "grep 'recognizer ready (source' $DBG | tail -1" </dev/null)
    if echo "$ready" | grep -q "source=$esrc gain=$gain"; then
      echo "  armed OK: $ready"; break
    fi
    echo "  arm mismatch (want source=$esrc gain=$gain): $ready — retry"; tries=$((tries+1))
  done
  bash device_bench.sh oww device_clips.txt "results/device_$name.csv"
}

run voicerec       voicerec 1.0 6
run mic            mic      1.0 1
run voicerec_gain15 voicerec 1.5 6
run voicerec_gain2 voicerec 2.0 6
run voicerec_gain3 voicerec 3.0 6
echo "ALL CONFIGS DONE"
