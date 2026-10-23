#!/usr/bin/env bash
# On-device setup for portal-assistant.
#
#   ./setup.sh                  full setup: install + grants + provision API key + launch + start service
#   ./setup.sh --key-only [KEY] only (re)provision the Gemini API key on an already-installed app
#   ./setup.sh --help
#
# Bring-your-own-key. Full setup checks whether a key is already on the device — if not, it walks you
# through creating one at Google AI Studio and prompts you to paste it. (Pass a key non-interactively
# only with --key-only.) The key is written to the app's files dir; the app imports it on launch and
# deletes the file (no typing on the Portal). You can always change it later in Settings -> API key.
#
# Uses hzdb (Horizon Debug Bridge) in place of raw adb. Enable ADB on the Portal first
# (Settings -> Debug -> ADB Enabled), connect USB-C, tap "Allow".
set -euo pipefail

# Device contract (PKG, RECEIVER, STANDBY_ACTION, NOTIF_LISTENER, EXTDIR) is shared with the one-click
# installers via provisioning/config.env — one source of truth for these literals.
SETUP_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=provisioning/config.env disable=SC1091
. "$SETUP_DIR/provisioning/config.env"

# setup-specific: this dev tool installs the locally-built debug APK via hzdb (not a downloaded release).
APK="app/build/outputs/apk/debug/app-debug.apk"
ADB="npx -y @meta-quest/hzdb adb"
adb_x() { $ADB "$@"; }   # adb indirection used by provisioning/keyprov.sh

usage() { sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; }

# ---- arg parsing -------------------------------------------------------------
# A key may be passed ONLY with --key-only; full setup always asks interactively when one is needed.
KEY_ONLY=0
KEY=""
ARGKEY=""
for a in "$@"; do
  case "$a" in
    --key-only|-k) KEY_ONLY=1 ;;
    -h|--help)     usage; exit 0 ;;
    -*)            echo "Unknown flag: $a" >&2; usage >&2; exit 1 ;;
    *)             ARGKEY="$a" ;;
  esac
done
if [[ -n "$ARGKEY" && "$KEY_ONLY" != "1" ]]; then
  echo "Full setup doesn't take a key argument." >&2
  echo "Run ./setup.sh and follow the prompt, or pass one with ./setup.sh --key-only <KEY>." >&2
  exit 1
fi
[[ "$KEY_ONLY" == "1" ]] && KEY="$ARGKEY"

# ---- helpers -----------------------------------------------------------------
app_installed() { $ADB shell "pm path $PKG" >/dev/null 2>&1; }

# Gemini API-key provisioning — key_present_on_device, valid_key_format, verify_key_live, prompt_for_key,
# stage_key, provision_key — plus the app contract constants (api_key.txt / assistant.xml / gemini_api_key
# / the verify endpoint) live in keyprov.sh, shared with provisioning/install.sh so the key contract has a
# single source. It calls adb through adb_x() (defined above) and falls back to plain echo output here
# (this dev tool defines no styled step/ok/warn/die).
# shellcheck source=provisioning/keyprov.sh disable=SC1091
. "$SETUP_DIR/provisioning/keyprov.sh"

# ---- key-only path -----------------------------------------------------------
if [[ "$KEY_ONLY" == "1" ]]; then
  app_installed || { echo "App not installed — run ./setup.sh first." >&2; exit 1; }
  echo "Provisioning the Gemini API key..."
  provision_key 1
  # Force a fresh process so onCreate re-runs and imports the staged key right away.
  $ADB shell "am force-stop $PKG" || true
  npx -y @meta-quest/hzdb app launch "$PKG" >/dev/null 2>&1 || $ADB shell "am start -n $PKG/.MainActivity"
  echo "Done. (Verify in Settings -> API key, or watch debug.txt.)"
  exit 0
fi

# ---- full setup --------------------------------------------------------------
if [[ ! -f "$APK" ]]; then
  echo "APK not found at $APK -- run: JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug" >&2
  exit 1
fi

echo "1/5  Installing $APK..."
npx -y @meta-quest/hzdb app install -r "$APK"

echo "2/5  Granting RECORD_AUDIO..."
$ADB shell "pm grant $PKG android.permission.RECORD_AUDIO" || true

echo "3/5  Granting draw-over-apps (the orange recording bar)..."
$ADB shell "appops set $PKG SYSTEM_ALERT_WINDOW allow" || true

echo "3b/5  Granting notification access (gates media-session control for portal.media_control)..."
$ADB shell "cmd notification allow_listener $NOTIF_LISTENER" || true

echo "3c/5  Granting WRITE_SETTINGS (display brightness for portal.set_brightness)..."
$ADB shell "appops set $PKG WRITE_SETTINGS allow" || true

echo "3d/5  Granting Do Not Disturb policy access (for portal.set_do_not_disturb)..."
# Notification-policy access for DND. If this subcommand is unavailable on this build, grant on-device:
# Settings -> Apps -> special access -> Do Not Disturb access -> enable for Jarvis.
$ADB shell "cmd notification allow_dnd $PKG" || true

echo "3e/5  Provisioning the Gemini API key..."
provision_key 0   # written BEFORE launch so MainActivity picks it up immediately

echo "4/5  Launching once (clears the 'stopped' state so BOOT_COMPLETED is delivered after reboot)..."
npx -y @meta-quest/hzdb app launch "$PKG" || \
  $ADB shell "am start -n $PKG/.MainActivity"

echo "5/5  Starting the resident service (warm for the next 'hey jarvis')..."
# -f 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES — reaches the app even if still in stopped state.
$ADB shell "am broadcast -a $STANDBY_ACTION -n $RECEIVER -f 0x20"

echo
echo "Done. The assistant stays warm in the background (no mic until a wake/tap)."
