#!/usr/bin/env bash
# On-device setup for portal-assistant.
#
#   ./setup.sh                         full setup: install + grants + provision keys + launch + start service
#   ./setup.sh --key-only [KEY]        only (re)provision the Gemini API key on an already-installed app
#   ./setup.sh --tavily-key-only [KEY] only (re)provision the Search key on an already-installed app
#   ./setup.sh --help
#
# Bring-your-own-key. Full setup checks whether a key is already on the device — if not, it walks you
# through creating one at Google AI Studio and prompts you to paste it. It then offers an optional Search
# key (a free Tavily key: Web search on a model without Google Search). (Pass a key non-interactively only
# with --key-only / --tavily-key-only.) Each key is written to the app's files dir; the app imports it on
# launch and deletes the file (no typing on the Portal). Change them later in Settings -> API key / Search key.
#
# Uses hzdb (Horizon Debug Bridge) in place of raw adb. Enable ADB on the Portal first
# (Settings -> Debug -> ADB Enabled), connect USB-C, tap "Allow".
set -euo pipefail

# Device contract (PKG, RECEIVER, STANDBY_ACTION, NOTIF_LISTENER, EXTDIR) is shared with the one-click
# installers via provisioning/config.env — one source of truth for these literals.
SETUP_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=provisioning/config.env
. "$SETUP_DIR/provisioning/config.env"

# setup-specific: this dev tool installs the locally-built debug APK via hzdb (not a downloaded release).
APK="app/build/outputs/apk/debug/app-debug.apk"
ADB="npx -y @meta-quest/hzdb adb"
adb_x() { $ADB "$@"; }   # adb indirection used by provisioning/keyprov.sh

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; }

# ---- arg parsing -------------------------------------------------------------
# A key may be passed ONLY with --key-only / --tavily-key-only; full setup always asks interactively.
KEY_ONLY=0
SEARCH_KEY_ONLY=0
KEY=""
SEARCH_KEY=""
ARGKEY=""
for a in "$@"; do
  case "$a" in
    --key-only|-k)   KEY_ONLY=1 ;;
    --tavily-key-only) SEARCH_KEY_ONLY=1 ;;
    -h|--help)       usage; exit 0 ;;
    -*)              echo "Unknown flag: $a" >&2; usage >&2; exit 1 ;;
    *)               ARGKEY="$a" ;;
  esac
done
if [[ "$KEY_ONLY" == "1" && "$SEARCH_KEY_ONLY" == "1" ]]; then
  echo "Pass one of --key-only or --tavily-key-only, not both." >&2
  exit 1
fi
if [[ -n "$ARGKEY" && "$KEY_ONLY" != "1" && "$SEARCH_KEY_ONLY" != "1" ]]; then
  echo "Full setup doesn't take a key argument." >&2
  echo "Run ./setup.sh and follow the prompt, or pass one with --key-only <KEY> / --tavily-key-only <KEY>." >&2
  exit 1
fi
[[ "$KEY_ONLY" == "1" ]] && KEY="$ARGKEY"
[[ "$SEARCH_KEY_ONLY" == "1" ]] && SEARCH_KEY="$ARGKEY"

# ---- helpers -----------------------------------------------------------------
app_installed() { $ADB shell "pm path $PKG" >/dev/null 2>&1; }

# Gemini API-key provisioning — key_present_on_device, valid_key_format, verify_key_live, prompt_for_key,
# stage_key, provision_key — plus the app contract constants (api_key.txt / assistant.xml / gemini_api_key
# / the verify endpoint) live in keyprov.sh, shared with provisioning/install.sh so the key contract has a
# single source. It calls adb through adb_x() (defined above) and falls back to plain echo output here
# (this dev tool defines no styled step/ok/warn/die).
# shellcheck source=provisioning/keyprov.sh
. "$SETUP_DIR/provisioning/keyprov.sh"

# ---- Search key (optional) ---------------------------------------------------
# The user's own Tavily key for Web search on a model without Google Search. Same import-once contract as
# the Gemini key: staged here, imported into prefs by the app on launch. Change either side, change both.
SEARCH_PROVISION_FILE="search_key.txt"   # ProvisionedKey.SEARCH_FILE
SEARCH_PREFS_KEY="search_key"            # AppPrefs.KEY_SEARCH
SEARCH_VERIFY_URL="https://api.tavily.com/usage"  # same endpoint SearchKeyCheck uses (no credit spent)

search_key_present_on_device() {
  [ "$(adb_x shell "[ -f $EXTDIR/$SEARCH_PROVISION_FILE ] && echo yes" 2>/dev/null | tr -d '\r')" = "yes" ] && return 0
  adb_x shell "run-as $PKG cat shared_prefs/$KEY_PREFS_FILE 2>/dev/null" 2>/dev/null | grep -q "name=\"$SEARCH_PREFS_KEY\""
}

# Cheap sanity check: letters/digits/._- and 20+ chars (Tavily keys look like tvly-...).
valid_search_key_format() { case "$1" in *[!A-Za-z0-9._-]*) return 1 ;; esac; [ "${#1}" -ge 20 ]; }

# Live check, mirroring SearchKeyCheck.classify. 0 = verified, 1 = rejected, 2 = couldn't verify. The key goes
# in a header read from stdin, so it never shows in the process list.
verify_search_key_live() {
  command -v curl >/dev/null 2>&1 || { echo "      (curl not found — skipping the online check)"; return 2; }
  local code
  code="$(printf 'Authorization: Bearer %s\n' "$1" |
    curl -s -m 15 -o /dev/null -w '%{http_code}' -H @- "$SEARCH_VERIFY_URL" || echo 000)"
  case "$code" in
    2*)      echo "      ✓ Search key verified (HTTP $code)";                          return 0 ;;
    401|403) echo "      ✗ The search service rejected this key (HTTP $code)";         return 1 ;;
    *)       echo "      ⚠ Couldn't verify the search key (HTTP $code) — staging anyway"; return 2 ;;
  esac
}

# Optional: instructions + a paste prompt; Enter skips. Sets SEARCH_KEY (empty when skipped).
prompt_for_search_key() {
  cat <<INSTR

  Optional: a free Search key lets Jarvis look up news, scores, prices and opening hours
  on a model without Google Search (Gemini 3.8). Without one it can still check the weather.

    1. Open:  https://app.tavily.com  and sign up (free, no card).
    2. Copy the API key shown on the overview page (starts with tvly-).

  You can add or change it later in Jarvis > Settings > Search key.
INSTR
  local reply rc
  while true; do
    printf '\n  Paste your Search key and press Enter (or just Enter to skip): '
    read -r reply || reply=""
    reply="$(printf '%s' "$reply" | tr -d '[:space:]')"
    if [ -z "$reply" ]; then SEARCH_KEY=""; ok "Skipped the Search key."; return 0; fi
    if ! valid_search_key_format "$reply"; then
      echo "      That doesn't look like a key (expected 20+ chars, letters/digits). Try again."
      continue
    fi
    rc=0; verify_search_key_live "$reply" || rc=$?
    if [ "$rc" -eq 1 ]; then echo "      Let's try again."; continue; fi
    SEARCH_KEY="$reply"; return 0
  done
}

stage_search_key() {
  adb_x shell "mkdir -p $EXTDIR" >/dev/null 2>&1 || true
  adb_x shell "printf '%s' '$1' > $EXTDIR/$PKG.tmp_search_key && mv $EXTDIR/$PKG.tmp_search_key $EXTDIR/$SEARCH_PROVISION_FILE" >/dev/null 2>&1
  ok "Search key saved to the Portal (${#1} chars) — Jarvis imports it on launch."
}

# Orchestrate the Search key step, like provision_key: force=1 always asks; otherwise a key already on the
# device is kept. Always returns 0 (a skipped key must not fail a `set -e` caller).
provision_search_key() {
  local force="${1:-0}"
  step "Search key (optional)"
  if [ -n "${SEARCH_KEY:-}" ]; then
    SEARCH_KEY="$(printf '%s' "$SEARCH_KEY" | tr -d '[:space:]')"
    valid_search_key_format "$SEARCH_KEY" || die "Provided Search key doesn't look valid (expected 20+ chars)."
    local rc=0; verify_search_key_live "$SEARCH_KEY" || rc=$?
    [ "$rc" -eq 1 ] && die "The search service rejected the key — aborting."
  elif search_key_present_on_device && [ "$force" != "1" ]; then
    ok "A Search key is already set on this Portal — keeping it."
    return 0
  else
    prompt_for_search_key
  fi
  [ -n "${SEARCH_KEY:-}" ] && stage_search_key "$SEARCH_KEY"
  return 0
}

# Force a fresh process so onCreate re-runs and imports a staged key right away.
relaunch_app() {
  $ADB shell "am force-stop $PKG" || true
  npx -y @meta-quest/hzdb app launch "$PKG" >/dev/null 2>&1 || $ADB shell "am start -n $PKG/.MainActivity"
}

# ---- Search-key-only path ----------------------------------------------------
if [[ "$SEARCH_KEY_ONLY" == "1" ]]; then
  app_installed || { echo "App not installed — run ./setup.sh first." >&2; exit 1; }
  echo "Provisioning the Search key..."
  provision_search_key 1
  relaunch_app
  echo "Done. (Verify in Settings -> Search key, or watch debug.txt.)"
  exit 0
fi

# ---- key-only path -----------------------------------------------------------
if [[ "$KEY_ONLY" == "1" ]]; then
  app_installed || { echo "App not installed — run ./setup.sh first." >&2; exit 1; }
  echo "Provisioning the Gemini API key..."
  provision_key 1
  relaunch_app
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

echo "3f/5  Provisioning the Search key (optional)..."
provision_search_key 0   # likewise before launch

echo "4/5  Launching once (clears the 'stopped' state so BOOT_COMPLETED is delivered after reboot)..."
npx -y @meta-quest/hzdb app launch "$PKG" || \
  $ADB shell "am start -n $PKG/.MainActivity"

echo "5/5  Starting the resident service (warm for the next 'hey jarvis')..."
# -f 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES — reaches the app even if still in stopped state.
$ADB shell "am broadcast -a $STANDBY_ACTION -n $RECEIVER -f 0x20"

echo
echo "Done. The assistant stays warm in the background (no mic until a wake/tap)."
