# Install Jarvis on your Portal

No building, no command line, no developer tools. You just need the Portal, a USB‑C
cable, a computer, and a **free Google Gemini API key** (the installer walks you through
getting one).

## Before you start: get a free Gemini API key

Jarvis uses Google's Gemini to listen and talk, so it needs **your own** free API key.
The installer will prompt you for it, but you can grab it first:

1. On a computer or phone, open **https://aistudio.google.com/apikey**
2. Sign in with your Google account.
3. Click **Create API key**, pick a project (or let it create one), then **Create**.
4. Click the copy icon to copy the key. Keep it handy to paste during install.

> The key is stored **only on your Portal**. You can add or change it anytime in
> **Jarvis → Settings → API key**.

## Steps

1. **On the Portal:** open **Settings → Debug** and turn on **ADB Enabled**.
2. **Connect** the Portal to your computer with a **USB‑C cable**.
3. **Double-click** the installer for your computer:
   - **macOS:** `Install-Jarvis.command`
   - **Windows:** `Install-Jarvis.bat`
4. When the Portal screen shows **"Allow USB debugging?"**, tap **Allow** (tick
   "Always allow from this computer").
5. **When prompted, paste your Gemini API key** and press Enter. The installer checks it
   with Google before saving.
6. Wait for **"Done."** — open **Jarvis** from the Portal's app grid, or say
   **"hey jarvis"**. On a **Gen 2** Portal (Android 10), "hey jarvis" works on its own while
   Jarvis is on screen — no companion app. On a **Gen 1** Portal (Android 9), that hands‑free
   trigger needs the companion **portal-wake** app.

The installer does everything else automatically: it downloads Android's `adb` if you
don't have it, downloads the app, installs it, grants the microphone and the other
permissions, saves your key, and starts Jarvis warm in the background.

## About the API key (important)

- **No key = Jarvis can't answer.** You can finish the install *without* a key (just
  press Enter at the prompt to skip), but every conversation will fail to connect until
  you add one. The installer tells you this clearly and leaves Jarvis installed.
- **Add or change it later** anytime: re-run with **`Set-Jarvis-Key`**
  (`.command` on macOS, `.bat` on Windows), or on the Portal in
  **Jarvis → Settings → API key**.
- **Updating Jarvis?** If a key is already on the Portal, the installer **keeps it** and
  won't ask again — your conversations and key survive the update. Use `Set-Jarvis-Key`
  if you actually want to change it.

## To remove it

Double-click **`Uninstall-Jarvis`** (`.command` on macOS, `.bat` on Windows).

## Notes & troubleshooting

- **Windows "blocked files":** Windows marks files downloaded from the internet as
  blocked. If a script won't run, right-click it → **Properties** → tick **Unblock** →
  **OK**, then try again.
- **macOS "unidentified developer":** if double-clicking is blocked, right-click
  `Install-Jarvis.command` → **Open** → **Open**.
- **"More than one device is connected":** unplug other Android devices and re-run.
- **"hey jarvis" does nothing:** it depends on your Portal. On **Gen 2** (Android 10) "hey jarvis"
  works on its own **only while Jarvis is on screen** (Android 10 silences a background mic) — when
  it's in the background, tap **Tap to talk**. On **Gen 1** (Android 9) the hands-free wake word is
  provided by the separate **portal-wake** app — install that too. Either way you can always open
  Jarvis and tap **Tap to talk**.
- **Advanced:** the scripts (`install.sh` / `install.ps1`) also accept `--local`
  (`-Local`) to install a locally built APK, `--key` (`-Key`) to re-enter the API key,
  plus `--uninstall` (`-Uninstall`) and `--status` (`-Status`). `--local` uses the repo's
  debug build (`app/build/.../app-debug.apk`), or pass a path (`--local <apk>` /
  `-Apk <path>`); you can also drop an `.apk` into an `apks/` folder next to the scripts.
  Otherwise the latest published release is downloaded. Settings live in `config.env`.
