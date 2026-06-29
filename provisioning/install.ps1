# Jarvis (portal-assistant) one-click installer for the Meta Portal (Windows).
#
# Finds (or downloads) Android's adb, waits for a connected Portal, then installs Jarvis,
# grants its permissions, walks you through adding your free Google Gemini API key, and
# starts the warm background service. No Android SDK, no build tools, no Node — just this
# script and a USB-C cable.
#
# Usage:
#   .\install.ps1                  install Jarvis on the connected Portal (downloads the latest release)
#   .\install.ps1 -Local           install a locally built APK (the repo's debug build; -Apk <path> to override)
#   .\install.ps1 -Key             (re)enter the Gemini API key on an already-installed Jarvis (interactive)
#   .\install.ps1 -Key -KeyValue K provide the key non-interactively (mirrors install.sh's `--key <K>`)
#   .\install.ps1 -Uninstall       remove Jarvis from the Portal
#   .\install.ps1 -Status          show whether it's installed and whether a key is set
#
# NOTE: this is the Windows mirror of install.sh. The API-key logic and the app contract constants
# (staged file api_key.txt, prefs file assistant.xml, prefs key gemini_api_key, the verify endpoint, the
# 30+ char format check) are shared by setup.sh and install.sh via keyprov.sh — the canonical source.
# PowerShell can't source that bash, so the constants/logic below are a hand-kept copy: change keyprov.sh
# and you must mirror it here.
param([switch]$Uninstall, [switch]$Status, [switch]$Local, [switch]$Key, [string]$Apk, [string]$KeyValue)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# Stock Windows PowerShell 5.1 defaults to old TLS and an IE-based HTML parser, both
# of which break HTTPS downloads from Google / GitHub. Force TLS 1.2 + basic parsing.
try {
  [Net.ServicePointManager]::SecurityProtocol =
      [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch {}
$PSDefaultParameterValues['Invoke-WebRequest:UseBasicParsing'] = $true

function Step($m){ Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m){ Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m){ Write-Host "  [!] $m" -ForegroundColor Yellow }
function Die($m){ Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# ----- load config.env -------------------------------------------------------
if (-not (Test-Path config.env)) { Die "config.env not found next to this script." }
$cfg = @{}
Get-Content config.env | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
    $i = $line.IndexOf("="); $val = $line.Substring($i+1).Trim().Trim('"').Trim("'")
    $cfg[$line.Substring(0,$i).Trim()] = $val
  }
}

# Local-install support (-Local): the repo's standard debug build output. -Apk overrides the path.
$LocalApk = if ($Local) { if ($Apk) { $Apk } else { Join-Path $ScriptDir "..\app\build\outputs\apk\debug\app-debug.apk" } } else { "" }

# ----- resolve adb (bundled -> PATH -> download) -----------------------------
function Resolve-Adb {
  $bundled = Join-Path $ScriptDir "platform-tools\adb.exe"
  if (Test-Path $bundled) { return $bundled }
  $onPath = (Get-Command adb -ErrorAction SilentlyContinue)
  if ($onPath) { return $onPath.Source }
  Step "Android platform-tools (adb) not found - downloading the official package from Google"
  $url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
  $zip = Join-Path $ScriptDir "platform-tools.zip"
  Invoke-WebRequest -Uri $url -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $ScriptDir -Force
  Remove-Item $zip
  if (-not (Test-Path $bundled)) { Die "adb missing after download." }
  Ok "platform-tools installed locally"
  return $bundled
}
$ADB = Resolve-Adb
function A { & $ADB @args }

# ----- wait for an authorized device -----------------------------------------
function Wait-Device {
  Step "Looking for your Portal"
  A start-server | Out-Null
  $plug=$false; $auth=$false
  while ($true) {
    $raw = @(A devices | Select-Object -Skip 1)   # query adb once per poll, then filter it twice below
    $devs = @($raw | Where-Object { $_ -match "^\S+\s+device\b" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devs.Count -gt 1 -and -not $env:ANDROID_SERIAL) { Die "More than one device is connected. Unplug the others and re-run." }
    if ($devs.Count -eq 1) { $env:ANDROID_SERIAL = $devs[0]; $state = "device" }
    else {
      $line = ($raw | Where-Object { $_.Trim() } | Select-Object -First 1)
      $state = if ($line) { ($line -split "\s+")[1] } else { "" }
    }
    switch ($state) {
      "device" { $model = "$(A shell getprop ro.product.model)".Trim(); Ok "Connected: $model"; return }
      "unauthorized" { if (-not $auth) { Warn "On the Portal screen, tap Allow (check 'Always allow from this computer')."; $auth=$true } }
      default { if (-not $plug) { Warn "Plug the Portal into this PC via USB-C. On the Portal: Settings > Debug > ADB Enabled."; $plug=$true } }
    }
    Start-Sleep -Seconds 2
  }
}

# ----- APK install -----------------------------------------------------------
function Resolve-ReleaseApkUrl {
  if ($cfg["RELEASE_APK_URL"]) { return $cfg["RELEASE_APK_URL"] }
  if (-not $cfg["RELEASE_REPO"]) { return $null }
  try {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$($cfg["RELEASE_REPO"])/releases/latest" `
      -Headers @{ "User-Agent" = "portal-assistant-installer"; "Accept" = "application/vnd.github+json" }
    return ($rel.assets | Where-Object { $_.name -like "*.apk" } | Select-Object -First 1).browser_download_url
  } catch { return $null }
}

function Install-App {
  if ($LocalApk) {
    if (-not (Test-Path $LocalApk)) { Die "Local build not found: $LocalApk (run .\gradlew assembleDebug first, or pass -Apk <path>)." }
    $apk = Get-Item $LocalApk
    Step "Using local build: $($apk.FullName)"
  } else {
    $apk = Get-ChildItem -Path $cfg["APK_GLOB"] -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $apk) {
      $url = Resolve-ReleaseApkUrl
      if (-not $url) { Die "No local APK in apks\ and couldn't find a release to download. Connect to the internet, or drop a Jarvis APK in the apks\ folder." }
      Step "Downloading the latest Jarvis release"
      $dir = Split-Path -Parent $cfg["APK_GLOB"]
      New-Item -ItemType Directory -Force -Path $dir | Out-Null
      $dest = Join-Path $dir "portal-assistant.apk"
      Invoke-WebRequest -Uri $url -OutFile $dest
      $apk = Get-Item $dest
      Ok "Downloaded $($apk.Name)"
    }
  }
  Step "Installing Jarvis ($($apk.Name))"
  A install -r -d -g $apk.FullName | Out-Null
  Ok "Installed $($cfg["PKG"])"
}

# ----- permissions -----------------------------------------------------------
function Grant-Permissions {
  Step "Granting permissions"
  A shell pm grant $cfg["PKG"] android.permission.RECORD_AUDIO | Out-Null; Ok "Microphone"
  A shell appops set $cfg["PKG"] SYSTEM_ALERT_WINDOW allow | Out-Null; Ok "Draw-over-apps (the orange recording bar)"
  A shell cmd notification allow_listener $cfg["NOTIF_LISTENER"] | Out-Null; Ok "Notification access (media controls)"
  A shell appops set $cfg["PKG"] WRITE_SETTINGS allow | Out-Null; Ok "Write settings (screen brightness)"
  A shell cmd notification allow_dnd $cfg["PKG"] | Out-Null; Ok "Do Not Disturb access"
}

# ----- Gemini API key --------------------------------------------------------
# Jarvis is "bring your own key": each user supplies their own free Google Gemini key.
# Without a key the app installs and opens, but every conversation fails to connect.
function Key-Present {
  $staged = "$(A shell "[ -f $($cfg["EXTDIR"])/api_key.txt ] && echo yes")".Trim()
  if ($staged -eq "yes") { return $true }
  # 2>/dev/null suppresses the device shell's stderr — NOT 2>`$null, which PowerShell would send to the
  # device as the literal string "2>$null" (an empty/ambiguous redirect on Android sh, so the read fails).
  $prefs = (A shell "run-as $($cfg["PKG"]) cat shared_prefs/assistant.xml 2>/dev/null")
  return ($prefs -match 'name="gemini_api_key"')
}

function Valid-KeyFormat($k){ return ($k -match '^[A-Za-z0-9._-]{30,}$') }

# Live check against the same endpoint the app uses. Returns 'ok' / 'rejected' / 'unknown'.
function Verify-KeyLive($k) {
  try {
    Invoke-WebRequest -Uri "https://generativelanguage.googleapis.com/v1beta/models?key=$k" -TimeoutSec 15 | Out-Null
    Write-Host "      [ok] Key verified with Google" -ForegroundColor Green
    return "ok"
  } catch {
    $code = $null
    try { $code = [int]$_.Exception.Response.StatusCode } catch {}
    if ($code -in 400,401,403) { Write-Host "      [x] Google rejected this key (HTTP $code)" -ForegroundColor Red; return "rejected" }
    Write-Host "      [!] Couldn't reach Google to verify - staging anyway" -ForegroundColor Yellow
    return "unknown"
  }
}

# Step-by-step instructions + a paste prompt; loops until a usable key (or empty to skip). Returns the key ("" = skipped).
function Prompt-ForKey {
  Write-Host ""
  Write-Host "  ------------------------------------------------------------------" -ForegroundColor White
  Write-Host "  Jarvis needs a free Google Gemini API key to work. To create one:" -ForegroundColor White
  Write-Host ""
  Write-Host "    1. On a computer or phone, open:  https://aistudio.google.com/apikey"
  Write-Host "    2. Sign in with your Google account."
  Write-Host "    3. Click  'Create API key'."
  Write-Host "    4. Pick a Google Cloud project (or let it create one), then Create."
  Write-Host "    5. Click the copy icon to copy the key."
  Write-Host ""
  Write-Host "  The key is stored only on your Portal. You can change it later in" -ForegroundColor DarkGray
  Write-Host "  Jarvis > Settings > API key." -ForegroundColor DarkGray
  Write-Host "  ------------------------------------------------------------------" -ForegroundColor White
  while ($true) {
    $reply = (Read-Host "`n  Paste your key and press Enter (or just Enter to skip)").Trim()
    if (-not $reply) {
      Warn "No key entered. Jarvis will install but won't be able to answer until you add a key in Settings > API key (or re-run with -Key)."
      return ""
    }
    if (-not (Valid-KeyFormat $reply)) { Write-Host "      That doesn't look like a key (expected 30+ chars, letters/digits). Try again."; continue }
    if ((Verify-KeyLive $reply) -eq "rejected") { Write-Host "      Let's try again."; continue }
    return $reply
  }
}

function Stage-Key($k) {
  A shell "mkdir -p $($cfg["EXTDIR"])" | Out-Null
  A shell "printf '%s' '$k' > $($cfg["EXTDIR"])/$($cfg["PKG"]).tmp_key && mv $($cfg["EXTDIR"])/$($cfg["PKG"]).tmp_key $($cfg["EXTDIR"])/api_key.txt" | Out-Null
  Ok "Key saved to the Portal ($($k.Length) chars) - Jarvis imports it on launch."
}

# force=$true always (re)asks even when a key is present; otherwise an existing device key is preserved.
# Returns $true when a key is in place afterwards (staged now or already present), $false when skipped.
function Provision-Key([bool]$force) {
  Step "Google Gemini API key"
  $k = $KeyValue
  if ($k) {
    $k = $k.Trim()
    if (-not (Valid-KeyFormat $k)) { Die "Provided key doesn't look valid (expected 30+ chars)." }
    if ((Verify-KeyLive $k) -eq "rejected") { Die "Google rejected the key - aborting." }
  } elseif ((Key-Present) -and (-not $force)) {
    Ok "A key is already set on this Portal - keeping it. (Re-run with -Key to change it.)"
    return $true
  } else {
    $k = Prompt-ForKey
  }
  if ($k) { Stage-Key $k; return $true }
  return $false
}

# ----- launch + standby ------------------------------------------------------
function Launch-App {
  Step "Opening Jarvis once (imports your key, enables auto-start on reboot)"
  A shell "am start -n $($cfg["LAUNCH_ACTIVITY"])" | Out-Null
  Ok "Launched"
}
function Start-Standby {
  Step "Starting Jarvis's background service (stays warm for the next 'hey jarvis')"
  A shell "am broadcast -a $($cfg["STANDBY_ACTION"]) -n $($cfg["RECEIVER"]) -f 0x20" | Out-Null
  Ok "Running in standby"
}

# ----- top-level actions -----------------------------------------------------
if ($Status) {
  Wait-Device
  Step "Current state"
  $inst = (A shell pm list packages $cfg["PKG"]) -match "package:"
  Write-Host "  Jarvis: $(if ($inst) {'installed'} else {'not installed'})"
  Write-Host "  Gemini API key: $(if (Key-Present) {'set'} else {'NOT set - Jarvis cannot answer'})"
  exit 0
}

if ($Uninstall) {
  Write-Host "Jarvis uninstaller`n"
  Wait-Device
  Step "Stopping and removing Jarvis"
  A shell am force-stop $cfg["PKG"] | Out-Null
  A uninstall $cfg["PKG"] | Out-Null
  Write-Host "`n[ok] Done. Jarvis removed." -ForegroundColor Green
  exit 0
}

if ($Key) {
  Write-Host "Jarvis - set the Gemini API key`n"
  Wait-Device
  if (-not ((A shell pm path $cfg["PKG"]) -match "package:")) { Die "Jarvis isn't installed yet - run the installer first." }
  # Only relaunch (to import) and report success if a key was actually set — skipping leaves Jarvis as-is.
  if (Provision-Key $true) {
    A shell am force-stop $cfg["PKG"] | Out-Null   # fresh process re-imports the staged key
    A shell "am start -n $($cfg["LAUNCH_ACTIVITY"])" | Out-Null
    Write-Host "`n[ok] Done. Verify in Jarvis > Settings > API key." -ForegroundColor Green
  }
  exit 0
}

Write-Host "Jarvis installer" -ForegroundColor White
Write-Host "Installs the Jarvis voice assistant on your Portal and starts it.`n" -ForegroundColor DarkGray
Wait-Device
Install-App
Grant-Permissions
[void](Provision-Key $false)   # discard the bool — the final Key-Present check below drives the message
Launch-App
Start-Standby
Write-Host "`n[ok] Done. Jarvis is installed and ready." -ForegroundColor Green
if (Key-Present) {
  Write-Host "Say 'hey jarvis' near the Portal (needs portal-wake installed), or open Jarvis and tap 'Tap to talk'." -ForegroundColor DarkGray
} else {
  Write-Host "No API key set - open Jarvis > Settings > API key to add one, or re-run with -Key. Until then it can't answer." -ForegroundColor Yellow
}
