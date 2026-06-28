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

Portal-Assistant has no analytics and no accounts of its own. **It is not
on-device only.** To answer you, Jarvis streams your
microphone audio — only while a conversation is live — to **Google's Gemini Live
API**, where it is transcribed and answered by the model. That network
communication, and any data Google receives, is governed by **Google's** terms
of service and privacy policy, not this project's.

- You supply **your own** Gemini API key (BYOD). It is stored only on your device
  (in app preferences) and is sent only to Google to authenticate your requests.
  The project never receives it.
- Audio is captured and forwarded **only while a conversation is active** (the
  orange bar is showing). Between turns and in standby, no audio leaves the
  device. Jarvis does not record or store your audio itself.
- Web-grounded answers are produced by Google's `googleSearch` tool on Google's
  side; device actions (timers, volume, brightness, etc.) run locally.
- Hands-free triggering is handled by a separate companion wake-word app, which
  detects the wake word **on-device** and hands off the microphone — see that
  app's own disclaimer for its privacy notes.
- The `DebugLog` helper writes a best-effort local log file
  (`files/debug.txt`) on the device only. No personal data is collected by the
  project.

In short: deciding to use Jarvis means deciding to send your spoken questions to
Google's Gemini service under your own key. If that is not acceptable for your
use, do not install it.

## Reporting issues

If you believe any content here infringes your rights, or you represent Meta or
Google and have concerns, please open an issue or contact the maintainers; we
will respond promptly.
