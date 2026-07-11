# Disclaimer

**Portal-Assistant ("Jarvis") is an independent, community-built project. It is
not affiliated with, authorized by, endorsed by, or sponsored by Meta Platforms,
Inc. or Google LLC.**

"Meta", "Meta Portal", and "Portal" are trademarks of Meta Platforms, Inc., and
"Gemini" is a trademark of Google LLC. They are used here only to identify the
hardware this app is compatible with and the AI service it communicates with
(nominative use). Portal-Assistant is not a Meta or Google product and ships no
Meta or Google code.

## Use at your own risk

Portal-Assistant is a sideloaded app that you build and install yourself. Meta
Portal devices are discontinued and receive no official support. By installing
and running it you accept that:

- Installing and running third-party apps on a device may **void any remaining
  warranty** or violate the device's terms of use.
- Modifying a device or sideloading software always carries some risk. We are
  not aware of this app causing any harm, but **no outcome is guaranteed**.
- The app runs a foreground service that holds the microphone in order to listen
  for and answer your spoken questions. You are responsible for deciding whether
  that is appropriate on your device.

The software is provided "AS IS", without warranty of any kind. To the maximum
extent permitted by law, the authors and contributors accept no liability for
any damage, data loss, or other harm arising from its use.

## Privacy

Portal-Assistant has no analytics and no accounts of its own. Audio is captured
and forwarded **only while a conversation is active** (the orange bar is
showing). Between turns and in standby, no audio leaves the device. Jarvis does
not record or store your audio itself. Where that audio goes depends on
**Settings → Backend**:

- **Gemini (default)** — mic audio streams to **Google's Gemini Live API** under
  **your own** API key (BYOD). The key is stored only on your device and sent
  only to Google to authenticate requests; this project never receives it.
  That traffic is governed by **Google's** terms and privacy policy. Web-grounded
  answers use Google's `googleSearch` on Google's side.
- **Local server** — mic audio streams over your LAN to the voice host you
  configured (`wss://`, self-signed TLS; the app does not verify the cert).
  Nothing is sent to Google on this path. Grounding and model choice are
  whatever that host provides.

Device actions (timers, volume, brightness, etc.) always run on-device.
Hands-free triggering is handled by a separate companion wake-word app, which
detects the wake word **on-device** and hands off the microphone — see that
app's own disclaimer for its privacy notes. The `DebugLog` helper writes a
best-effort local log file (`files/debug.txt`) on the device only; no personal
data is collected by the project.

In short: Gemini means sending spoken questions to Google under your key; Local
means sending them to your own LAN host. Choose the backend that matches your
comfort.

## Reporting issues

If you believe any content here infringes your rights, or you represent Meta or
Google and have concerns, please open an issue or contact the maintainers; we
will respond promptly.
