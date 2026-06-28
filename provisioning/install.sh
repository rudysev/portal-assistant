#!/usr/bin/env bash
#
# Jarvis (portal-assistant) one-click installer for the Meta Portal (macOS / Linux).
#
# Finds (or downloads) Android's adb, waits for a connected Portal, then installs Jarvis,
# grants its permissions, walks you through adding your free Google Gemini API key, and
# starts the warm background service. No Android SDK, no build tools, no Node — just this
# script and a USB-C cable.
#
# Usage:
#   ./install.sh             install Jarvis on the connected Portal (downloads the latest release)
#   ./install.sh --local     install a locally built APK (the repo's debug build; pass a path to override)
#   ./install.sh --key       (re)enter the Gemini API key on an already-installed Jarvis
#   ./install.sh --uninstall remove Jarvis from the Portal
#   ./install.sh --status    show whether it's installed and whether a key is set

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ----- pretty output ---------------------------------------------------------
if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; D=$'\033[2m'; N=$'\033[0m'; else B=; G=; Y=; R=; D=; N=; fi
step() { printf "%s==>%s %s\n" "$B" "$N" "$1"; }
ok()   { printf "  %s✓%s %s\n" "$G" "$N" "$1"; }
warn() { printf "  %s!%s %s\n" "$Y" "$N" "$1"; }
die()  { printf "%sERROR:%s %s\n" "$R" "$N" "$1" >&2; exit 1; }

# ----- load config -----------------------------------------------------------
[ -f config.env ] || die "config.env not found next to this script."
# shellcheck disable=SC1091
set -a; . ./config.env; set +a

# Local-install support (--local): the repo's standard debug build output, used when
# installing a build instead of downloading a release. Override with --local <apk>.
DEFAULT_BUILD_APK="$SCRIPT_DIR/../app/build/outputs/apk/debug/app-debug.apk"
LOCAL_APK=""

# ----- resolve adb (bundled -> PATH -> download) -----------------------------
resolve_adb() {
  if [ -x "$SCRIPT_DIR/platform-tools/adb" ]; then ADB="$SCRIPT_DIR/platform-tools/adb"; return; fi
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return; fi
  step "Android platform-tools (adb) not found — downloading the official package from Google"
  local os zip url
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux)  os=linux ;;
    *) die "Unsupported OS for auto-download. Install Android platform-tools and re-run." ;;
  esac
  url="https://dl.google.com/android/repository/platform-tools-latest-${os}.zip"
  zip="$SCRIPT_DIR/platform-tools.zip"
  curl -fL "$url" -o "$zip" || die "Download failed. Check your internet connection."
  unzip -oq "$zip" -d "$SCRIPT_DIR" || die "Could not unzip platform-tools."
  rm -f "$zip"
  [ -x "$SCRIPT_DIR/platform-tools/adb" ] || die "adb missing after download."
  ADB="$SCRIPT_DIR/platform-tools/adb"
  ok "platform-tools installed locally"
}
a() { "$ADB" "$@"; }

# ----- wait for an authorized device -----------------------------------------
wait_for_device() {
  step "Looking for your Portal"
  a start-server >/dev/null 2>&1
  local printed_plug=0 printed_auth=0
  while true; do
    local raw devs n state line
    raw="$(a devices)"   # query adb once per poll, then parse it for both the serial list and the state line
    devs="$(printf "%s\n" "$raw" | awk 'NR>1 && $2=="device"{print $1}')"
    n="$(printf "%s" "$devs" | grep -c . || true)"
    if [ "$n" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
      die "More than one device is connected. Unplug the others (or set ANDROID_SERIAL=<serial>) and re-run."
    elif [ "$n" = 1 ]; then
      ANDROID_SERIAL="$devs"; export ANDROID_SERIAL; state="device"
    else
      line="$(printf "%s\n" "$raw" | awk 'NR>1 && NF{print; exit}')"
      state="$(printf "%s" "$line" | awk '{print $2}')"
    fi
    case "$state" in
      device)
        local model; model="$(a shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
        ok "Connected: ${model:-device}"
        return ;;
      unauthorized)
        if [ "$printed_auth" = 0 ]; then
          printf "  %sOn the Portal screen, tap %sAllow%s (check \"Always allow from this computer\").%s\n" "$Y" "$B" "$N$Y" "$N"
          printed_auth=1
        fi ;;
      *)
        if [ "$printed_plug" = 0 ]; then
          printf "  %sPlug the Portal into this computer with a USB-C cable.%s\n" "$Y" "$N"
          printf "  %sOn the Portal: Settings > Debug > ADB Enabled.%s\n" "$D" "$N"
          printed_plug=1
        fi ;;
    esac
    sleep 2
  done
}

# ----- APK install -----------------------------------------------------------
# Resolve a download URL for the release APK: an explicit RELEASE_APK_URL wins,
# else ask GitHub for the latest release's first .apk asset on RELEASE_REPO (so a
# versioned asset name like portal-assistant-1.0.apk keeps working across releases).
resolve_release_apk_url() {
  if [ -n "${RELEASE_APK_URL:-}" ]; then printf '%s\n' "$RELEASE_APK_URL"; return 0; fi
  [ -n "${RELEASE_REPO:-}" ] || return 1
  curl -fsSL -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${RELEASE_REPO}/releases/latest" 2>/dev/null \
    | grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*\.apk"' \
    | head -1 \
    | sed 's/.*"\(https[^"]*\)".*/\1/'
}

install_app() {
  local apk
  if [ -n "$LOCAL_APK" ]; then
    [ -f "$LOCAL_APK" ] || die "Local build not found: $LOCAL_APK (run ./gradlew assembleDebug first, or pass a path: --local <apk>)."
    apk="$LOCAL_APK"
    step "Using local build: $apk"
  else
    apk="$(ls $APK_GLOB 2>/dev/null | head -1)"
    if [ -z "$apk" ]; then
      local url; url="$(resolve_release_apk_url)"
      [ -n "$url" ] || die "No local APK in apks/ and couldn't find a release to download. Connect to the internet, drop a Jarvis APK in apks/, or use --local."
      step "Downloading the latest Jarvis release"
      mkdir -p "$(dirname "$APK_GLOB")"
      apk="$(dirname "$APK_GLOB")/portal-assistant.apk"
      curl -fL "$url" -o "$apk" || die "Could not download the release APK. Check your connection."
      ok "Downloaded $(basename "$apk")"
    fi
  fi
  step "Installing Jarvis ($(basename "$apk"))"
  # -r keep data on update, -d allow downgrade/same-version reinstall, -g pre-grant runtime perms.
  a install -r -d -g "$apk" >/dev/null 2>&1 && ok "Installed $PKG" || die "Install failed."
}

# ----- permissions -----------------------------------------------------------
grant_permissions() {
  step "Granting permissions"
  a shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 && ok "Microphone" || warn "Couldn't grant the microphone — Jarvis can't hear you until you grant it (Settings > Apps > Jarvis > Permissions)."
  a shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 && ok "Draw-over-apps (the orange recording bar)" || warn "Couldn't grant draw-over-apps — the orange listening bar won't show."
  # Optional power-user grants — Jarvis runs without them; they just unlock a few tools.
  a shell cmd notification allow_listener "$NOTIF_LISTENER" >/dev/null 2>&1 && ok "Notification access (media controls)" || warn "Media-control tool unavailable until you grant notification access on-device."
  a shell appops set "$PKG" WRITE_SETTINGS allow >/dev/null 2>&1 && ok "Write settings (screen brightness)" || true
  a shell cmd notification allow_dnd "$PKG" >/dev/null 2>&1 && ok "Do Not Disturb access" || true
}

# ----- Gemini API key --------------------------------------------------------
# The key-provisioning logic + the app contract constants live in keyprov.sh, shared with ../setup.sh.
# It calls adb via adb_x() and uses our styled step/ok/warn/die + colour vars (B/D/N/Y) automatically.
adb_x() { a "$@"; }
# shellcheck source=keyprov.sh disable=SC1091
. "$SCRIPT_DIR/keyprov.sh"

# ----- launch + standby ------------------------------------------------------
launch_app() {
  # Launch once so onCreate imports the staged key and Android clears the "stopped"
  # state (so BOOT_COMPLETED is delivered after a reboot).
  step "Opening Jarvis once (imports your key, enables auto-start on reboot)"
  a shell "am start -n $LAUNCH_ACTIVITY" >/dev/null 2>&1 && ok "Launched" || warn "Couldn't launch — open Jarvis from the app grid once."
}

start_standby() {
  # Bring the resident service up to warm standby (no mic, no bar) so the next "hey
  # jarvis" / tap hits a warm process. -f 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES.
  step "Starting Jarvis's background service (stays warm for the next 'hey jarvis')"
  a shell "am broadcast -a $STANDBY_ACTION -n $RECEIVER -f 0x20" >/dev/null 2>&1
  ok "Running in standby"
}

# ----- top-level actions -----------------------------------------------------
do_install() {
  printf "%sJarvis installer%s\n" "$B" "$N"
  printf "%sInstalls the Jarvis voice assistant on your Portal and starts it.%s\n\n" "$D" "$N"
  resolve_adb
  wait_for_device
  install_app
  grant_permissions
  provision_key 0   # staged BEFORE launch so the first onCreate imports it
  launch_app
  start_standby
  printf "\n%s✓ Done. Jarvis is installed and ready.%s\n" "$G$B" "$N"
  if key_present_on_device; then
    printf "%sSay \"hey jarvis\" near the Portal (needs portal-wake installed), or open Jarvis and tap \"Tap to talk\".%s\n" "$D" "$N"
  else
    printf "%s%sNo API key set — open Jarvis > Settings > API key to add one, or re-run with --key. Until then it can't answer.%s\n" "$Y" "$B" "$N"
  fi
}

do_key() {
  printf "%sJarvis — set the Gemini API key%s\n" "$B" "$N"
  resolve_adb
  wait_for_device
  a shell "pm path $PKG" >/dev/null 2>&1 || die "Jarvis isn't installed yet — run the installer first."
  provision_key 1   # force: always (re)ask
  if [ -n "$KEY" ]; then
    a shell "am force-stop $PKG" >/dev/null 2>&1 || true   # fresh process re-imports the staged key
    a shell "am start -n $LAUNCH_ACTIVITY" >/dev/null 2>&1 || true
    printf "\n%s✓ Done. Verify in Jarvis > Settings > API key.%s\n" "$G$B" "$N"
  fi
}

do_uninstall() {
  printf "%sJarvis uninstaller%s\n\n" "$B" "$N"
  resolve_adb
  wait_for_device
  step "Stopping and removing Jarvis"
  a shell am force-stop "$PKG" >/dev/null 2>&1
  a uninstall "$PKG" >/dev/null 2>&1 && ok "Uninstalled $PKG" || warn "Jarvis was not installed."
  printf "\n%s✓ Done. Jarvis removed.%s\n" "$G$B" "$N"
}

do_status() {
  resolve_adb; wait_for_device
  step "Current state"
  printf "  Jarvis: %s\n" "$(a shell pm list packages "$PKG" 2>/dev/null | tr -d '\r' | grep -q . && echo installed || echo 'not installed')"
  printf "  Gemini API key: %s\n" "$(key_present_on_device && echo 'set' || echo 'NOT set — Jarvis cannot answer')"
}

case "${1:-}" in
  --uninstall|-u) do_uninstall ;;
  --status|-s)    do_status ;;
  --key|-k)       KEY="${2:-}"; do_key ;;
  --local|-l)     LOCAL_APK="${2:-$DEFAULT_BUILD_APK}"; do_install ;;
  --help|-h)      sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//' ;;
  "")             do_install ;;
  *)              die "Unknown option: $1 (use --local, --key, --uninstall, --status, or no argument)" ;;
esac
