#!/usr/bin/env bash
#
# Shared key provisioning for Jarvis (portal-assistant): the Gemini API key and the optional Search key.
# Sourced by BOTH the dev tool
# (../setup.sh, via hzdb) and the shipped installer (./install.sh, via raw adb) so the key contract
# lives in exactly one bash file. The Windows port (install.ps1) is the one unavoidable second copy —
# it mirrors the constants and functions below; keep the two in lockstep.
#
# Jarvis is "bring your own key": each user supplies their own free Google Gemini key. Without one the
# app installs and opens, but every conversation fails to connect. The Search key (a free Tavily key) is
# optional: it gives Web search on a model without Google Search.
#
# Contract with the app (com.portal.assistant):
#   - the staged file the app imports-once on launch     → KEY_PROVISION_FILE  (ProvisionedKey.GEMINI_FILE)
#   - the private prefs file + key the import lands in    → KEY_PREFS_FILE / KEY_PREFS_KEY (AppPrefs)
#   - the same two for the Search key                      → SEARCH_PROVISION_FILE (ProvisionedKey.SEARCH_FILE)
#                                                            / SEARCH_PREFS_KEY (AppPrefs.KEY_SEARCH)
# Change either side and you must change the other.
#
# A sourcing script must, before calling these, provide:
#   - $PKG and $EXTDIR (from config.env)
#   - adb_x()  — run an adb command on the target device (e.g. `adb_x() { "$ADB" "$@"; }`)
# It MAY also define step/ok/warn/die for styled output; plain fallbacks are used otherwise.

# ----- contract constants (mirror these in install.ps1) ----------------------
KEY_PROVISION_FILE="api_key.txt"        # ProvisionedKey.GEMINI_FILE — staged in $EXTDIR, imported on launch
KEY_PREFS_FILE="assistant.xml"          # AppPrefs NAME="assistant" → shared_prefs/assistant.xml
KEY_PREFS_KEY="gemini_api_key"          # AppPrefs.KEY_API
KEY_VERIFY_URL="https://generativelanguage.googleapis.com/v1beta/models?key="  # same endpoint the app uses
SEARCH_PROVISION_FILE="search_key.txt"  # ProvisionedKey.SEARCH_FILE — staged in $EXTDIR, imported on launch
SEARCH_PREFS_KEY="search_key"           # AppPrefs.KEY_SEARCH (same prefs file)
SEARCH_VERIFY_URL="https://api.tavily.com/usage"  # same endpoint SearchKeyCheck uses (no search credit spent)

# ----- output fallbacks (styled versions win if the host defines them) --------
declare -f step >/dev/null 2>&1 || step() { printf '==> %s\n' "$1"; }
declare -f ok   >/dev/null 2>&1 || ok()   { printf '  %s\n' "$1"; }
declare -f warn >/dev/null 2>&1 || warn() { printf '  ! %s\n' "$1"; }
declare -f die  >/dev/null 2>&1 || die()  { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# The keys under consideration; callers may pre-seed them (an explicit --key <value>). `${KEY:-}` keeps this
# safe under `set -u`.
KEY="${KEY:-}"
SEARCH_KEY="${SEARCH_KEY:-}"

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
    printf '\n  Paste your key and press Enter %s(or just Enter to skip)%s: ' "${D:-}" "${N:-}"
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

# Write a key to the app's files dir as $2, for import-once on next launch. Pushed from a private host temp
# file, never inside an adb command string (so it isn't in the process list or subject to the device shell's
# quoting) and never on stdin (`hzdb adb shell` doesn't close stdin, so a `cat >` on the device never ends).
# 0 = staged, 1 = failed.
stage_file() {
  local host tmp="$EXTDIR/$PKG.tmp_$2"
  host="$(mktemp)" || return 1
  chmod 600 "$host"
  printf '%s' "$1" > "$host"
  adb_x shell "mkdir -p $EXTDIR" >/dev/null 2>&1 || true
  if adb_x push "$host" "$tmp" >/dev/null 2>&1 && adb_x shell "mv $tmp $EXTDIR/$2" >/dev/null 2>&1; then
    rm -f "$host"; return 0
  fi
  rm -f "$host"; return 1
}

stage_key() {
  if stage_file "$1" "$KEY_PROVISION_FILE"; then
    ok "Key saved to the Portal (${#1} chars) — Jarvis imports it on launch."
  else
    warn "Couldn't save the key to the Portal — add it in Jarvis > Settings > API key."
  fi
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

# ----- Search key (optional) --------------------------------------------------
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
  if stage_file "$1" "$SEARCH_PROVISION_FILE"; then
    ok "Search key saved to the Portal (${#1} chars) — Jarvis imports it on launch."
  else
    warn "Couldn't save the Search key to the Portal — add it in Jarvis > Settings > Search key."
  fi
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
