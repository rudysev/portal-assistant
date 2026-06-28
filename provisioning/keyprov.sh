#!/usr/bin/env bash
#
# Shared Gemini API-key provisioning for Jarvis (portal-assistant). Sourced by BOTH the dev tool
# (../setup.sh, via hzdb) and the shipped installer (./install.sh, via raw adb) so the key contract
# lives in exactly one bash file. The Windows port (install.ps1) is the one unavoidable second copy —
# it mirrors the constants and functions below; keep the two in lockstep.
#
# Jarvis is "bring your own key": each user supplies their own free Google Gemini key. Without one the
# app installs and opens, but every conversation fails to connect.
#
# Contract with the app (com.portal.assistant):
#   - the staged file the app imports-once on launch     → KEY_PROVISION_FILE  (AppPrefs.PROVISION_FILE)
#   - the private prefs file + key the import lands in    → KEY_PREFS_FILE / KEY_PREFS_KEY (AppPrefs)
# Change either side and you must change the other.
#
# A sourcing script must, before calling these, provide:
#   - $PKG and $EXTDIR (from config.env)
#   - adb_x()  — run an adb command on the target device (e.g. `adb_x() { "$ADB" "$@"; }`)
# It MAY also define step/ok/warn/die for styled output; plain fallbacks are used otherwise.

# ----- contract constants (mirror these in install.ps1) ----------------------
KEY_PROVISION_FILE="api_key.txt"        # AppPrefs.PROVISION_FILE — staged in $EXTDIR, imported on launch
KEY_PREFS_FILE="assistant.xml"          # AppPrefs NAME="assistant" → shared_prefs/assistant.xml
KEY_PREFS_KEY="gemini_api_key"          # AppPrefs.KEY_API
KEY_VERIFY_URL="https://generativelanguage.googleapis.com/v1beta/models?key="  # same endpoint the app uses

# ----- output fallbacks (styled versions win if the host defines them) --------
declare -f step >/dev/null 2>&1 || step() { printf '==> %s\n' "$1"; }
declare -f ok   >/dev/null 2>&1 || ok()   { printf '  %s\n' "$1"; }
declare -f warn >/dev/null 2>&1 || warn() { printf '  ! %s\n' "$1"; }
declare -f die  >/dev/null 2>&1 || die()  { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# The key under consideration; callers may pre-seed it (an explicit --key <value>). `${KEY:-}` keeps this
# safe under `set -u`.
KEY="${KEY:-}"

# A key counts as "present" if it's staged (pending import) or already imported into prefs. The release
# APK is a debug build, so `run-as` can read the app's private prefs to check.
key_present_on_device() {
  [ "$(adb_x shell "[ -f $EXTDIR/$KEY_PROVISION_FILE ] && echo yes" 2>/dev/null | tr -d '\r')" = "yes" ] && return 0
  adb_x shell "run-as $PKG cat shared_prefs/$KEY_PREFS_FILE 2>/dev/null" 2>/dev/null | grep -q "name=\"$KEY_PREFS_KEY\""
}

# Cheap sanity check (both classic AIza... and newer AQ.... keys are letters/digits/._- and 30+ chars).
valid_key_format() { case "$1" in *[!A-Za-z0-9._-]*) return 1 ;; esac; [ "${#1}" -ge 30 ]; }

# Live check against the same endpoint the app uses. 0 = verified, 1 = rejected, 2 = couldn't verify.
verify_key_live() {
  command -v curl >/dev/null 2>&1 || { echo "      (curl not found — skipping the online check)"; return 2; }
  local code
  code="$(curl -s -m 15 -o /dev/null -w '%{http_code}' "${KEY_VERIFY_URL}$1" || echo 000)"
  case "$code" in
    2*)          echo "      ✓ Key verified with Google (HTTP $code)";              return 0 ;;
    400|401|403) echo "      ✗ Google rejected this key (HTTP $code)";              return 1 ;;
    *)           echo "      ⚠ Couldn't reach Google to verify (HTTP $code) — staging anyway"; return 2 ;;
  esac
}

# Step-by-step instructions + a paste prompt; loops until a usable key (or empty to skip). Sets KEY.
# Colour vars (${B}/${D}/${N}/${Y}) are used if the host defined them, else expand empty (plain text).
prompt_for_key() {
  cat <<INSTR

  ${B:-}──────────────────────────────────────────────────────────────────${N:-}
  ${B:-}Jarvis needs a free Google Gemini API key to work.${N:-} To create one:

    1. On a computer or phone, open:  ${B:-}https://aistudio.google.com/apikey${N:-}
    2. Sign in with your Google account.
    3. Click  "Create API key".
    4. Pick a Google Cloud project (or let it create one), then Create.
    5. Click the copy icon to copy the key.

  ${D:-}The key is stored only on your Portal. You can change it later in${N:-}
  ${D:-}Jarvis > Settings > API key.${N:-}
  ${B:-}──────────────────────────────────────────────────────────────────${N:-}
INSTR
  local reply rc
  while true; do
    printf "\n  Paste your key and press Enter ${D:-}(or just Enter to skip)${N:-}: "
    read -r reply || reply=""
    reply="$(printf '%s' "$reply" | tr -d '[:space:]')"
    if [ -z "$reply" ]; then
      KEY=""
      warn "No key entered. Jarvis will install but ${B:-}won't be able to answer${N:-}${Y:-} until you add a key in Settings > API key."
      return 0
    fi
    if ! valid_key_format "$reply"; then
      echo "      That doesn't look like a key (expected 30+ chars, letters/digits). Try again."
      continue
    fi
    rc=0; verify_key_live "$reply" || rc=$?
    if [ "$rc" -eq 1 ]; then echo "      Let's try again."; continue; fi
    KEY="$reply"; return 0   # verified (0) or couldn't-verify (2): accept
  done
}

# Write the key to the app's files dir for import-once on next launch.
stage_key() {
  adb_x shell "mkdir -p $EXTDIR" >/dev/null 2>&1 || true
  adb_x shell "printf '%s' '$1' > $EXTDIR/$PKG.tmp_key && mv $EXTDIR/$PKG.tmp_key $EXTDIR/$KEY_PROVISION_FILE" >/dev/null 2>&1
  ok "Key saved to the Portal (${#1} chars) — Jarvis imports it on launch."
}

# Orchestrate the key step. force=1 always (re)asks even when a key is already present; otherwise an
# existing device key is preserved (important on update — never clobber it). Always returns 0 (the
# trailing `&&` must not leak a non-zero status to a `set -e` caller when the key is skipped).
provision_key() {
  local force="${1:-0}"
  step "Google Gemini API key"
  if [ -n "${KEY:-}" ]; then
    KEY="$(printf '%s' "$KEY" | tr -d '[:space:]')"
    valid_key_format "$KEY" || die "Provided key doesn't look valid (expected 30+ chars)."
    local rc=0; verify_key_live "$KEY" || rc=$?
    [ "$rc" -eq 1 ] && die "Google rejected the key — aborting."
  elif key_present_on_device && [ "$force" != "1" ]; then
    ok "A key is already set on this Portal — keeping it. (Re-run to change it.)"
    return 0
  else
    prompt_for_key
  fi
  [ -n "${KEY:-}" ] && stage_key "$KEY"
  return 0
}
