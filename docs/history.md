# History & limitations — Portal-Assistant

## Phases
- **Phase 1 — mic hand-off harness.** ✅ Done (replaced by the real engine).
- **Phase 2.a — single-turn Gemini Live voice.** ✅ Built + device-validated (close range **and** 4 m).
  portal-wake → `WakeHandoffReceiver` → `AssistantService` → `AssistantEngine`: connect to the Gemini
  Live API, stream mic audio, the model answers in its own voice (orange bar only while the mic is live),
  the conversation **ends after that one answer** (single-turn) or on silence/error, then the mic is
  released so portal-wake reclaims by detection. Model `gemini-2.5-flash-native-audio-latest`;
  `googleSearch` grounding (function-calling + built-in tools came later — see the architecture section).
- **Latency: resident pre-warmed standby.** ✅ `AssistantService` is a long-lived foreground service
  (`START_STICKY`) started at boot by `AssistantBootReceiver` (`RECEIVE_BOOT_COMPLETED`) and re-warmed at app
  launch by `MainActivity` (so a first foreground tap after install also hits a warm process); after a turn it
  returns to **standby** (mic-less, no bar) instead of `stopSelf`, and `prewarm()` pre-builds OkHttp +
  conversation classes + Live-host DNS + the installed tool-provider snapshot. So a wake hand-off hits a warm process — dropping the cold first
  "hey jarvis" → orange bar latency substantially (`detected → bar`).
- **Phase 2.b — multi-turn (ongoing conversation).** ✅ Built (`AssistantEngine.MULTI_TURN = true`): after
  each answer the mic re-opens for a follow-up and the conversation ends only when the user goes silent
  (the no-speech timer). The mic stays open across turns (frames dropped while SPEAKING) so portal-wake
  can't reclaim mid-conversation. The **connect buffer** is now built (in the Mic gate, `conversation/MicGate`):
  mic frames captured during CONNECTING are flushed at the LISTENING boundary so the foreground connect
  window's opening words aren't clipped (a one-breath *wake* swoop stays gated by portal-wake's detection
  upstream — see Known limitations). The **"goodbye" fast-exit** later shipped as the **final turn**:
  `portal.end_conversation` makes the current turn end the conversation instead of re-listening.

## Known limitations

- **First-query clip**: the connect buffer (in `MicGate`) now recovers speech
  spoken while the socket is connecting on a **voice** query (wake or tap), so the foreground tap path
  ("Connecting…" on screen) keeps its opening words. (A tapped chip is a text query, so it doesn't buffer.)
  But a one-breath *wake* "hey jarvis what's the weather" is **not** fixed by it: portal-wake
  gates on "hey jarvis" as a discrete phrase, so a swoop never triggers the hand-off and the assistant never
  starts — the buffer can only help conversations that actually began. Don't mis-attribute the swoop to
  server VAD tuning or to the buffer.
- **Two persistent notifications**: the assistant's resident standby service and portal-wake each show a
  low-importance foreground-service notification — the accepted cost of staying warm (a plain background
  service would be evicted and cold-start again).
- **Room distance** relies on `MIC_GAIN` (sideloaded apps get only the handset mic). Works at ~4 m.
- **tap-to-talk churns** with portal-wake (both use `VOICE_RECOGNITION`); fine when portal-wake isn't
  running. Our own app → low priority (`#8`); a future explicit yield.
- **Transcript markdown is reduced to plain text**, not rendered — deliberate (it must stay word-sliceable
  for the paced reveal, and it's a spoken-answer transcript). A stray literal `*` in an answer is dropped;
  accepted.

## Provenance

The Gemini Live client (now `GeminiWireFormat` on the shared transport), `PcmPlayer`, `SpeechAudio`, the `RecordingOverlay`, and `MicCapture`'s `AudioRecord` config
were adapted from `portal-gemini-chat` (proven on-device), then **simplified**: no tools/skills, and a
**per-conversation** Live connection (the reference's persistent socket forced session-recycle/stale-guard
machinery + ~10 timers + a CAS latch — all dropped). `ConversationState`, `AssistantEngine`, and `PcmGain`
are new here. `PcmLevel`/`DebugLog` live in `portal-commons`; the wake contract is portal-wake's.


## API key provisioning
The Gemini API key is **not** baked in — `BuildConfig.GEMINI_API_KEY` is a blank dev seam. Each user
supplies **their own** key (BYOD), set two ways:
- **At install** — `./setup.sh` checks whether a key is already on the device and, if not, walks the user
  through creating one and prompts them to paste it (full setup takes **no** key argument; pass one
  non-interactively only via `./setup.sh --key-only <KEY>`). It writes the key to the app's external files
  dir (`api_key.txt`); the app imports it into prefs on first launch and deletes the file (import-once,
  `AppPrefs.importProvisionedKeys` → `ProvisionedKey.importOnce`, called from `MainActivity`/`AssistantService`
  onCreate). So a non-technical user is set up with **no typing**.
- **In the app** — **Settings → API key** lets them add or change it manually; **Save & verify** confirms
  the key against the API (`GeminiKeyCheck`) before storing via `AppPrefs.setApiKey`.

The **Search key** (the user's own free Tavily key for Web search on a model without Google Search) is a second,
**optional** BYOD key, never built in, provisioned the same two ways:
- **At install** — full `./setup.sh` offers it after the Gemini key (Enter skips; a key already on the device is
  kept); `./setup.sh --tavily-key-only <KEY>` (re)provisions it non-interactively. The one-click installers offer
  it the same way: during install, from `--key` / `-Key` when asked interactively (so the double-click
  `Set-Jarvis-Key` covers both keys), and alone via `install.sh --search-key [KEY]` /
  `install.ps1 -SearchKey [-SearchKeyValue K]`. All check it against Tavily's usage endpoint (no credit spent)
  and stage it as `search_key.txt`, imported once by the same `AppPrefs.importProvisionedKeys` into prefs
  `search_key`. The bash logic lives once in `provisioning/keyprov.sh` (shared by `setup.sh` and `install.sh`;
  `install.ps1` mirrors it).
- **Staging** — both keys are `adb push`ed from a private host temp file and moved into place, never put in an
  adb command string (process list, device-shell quoting) nor sent on stdin (`hzdb adb shell` never closes
  stdin, so a device-side `cat >` hangs). Each script uses one device tool throughout: `setup.sh` hzdb's adb,
  the installers plain adb (downloaded if missing).
- **In the app** — **Settings → Search key**, the same Save & verify form (`SearchKeyCheck`, a pure status →
  `KeyVerdict` mapping shared in shape with `GeminiKeyCheck`), stored via `AppPrefs.setSearchKey`. The opening
  reads `AppPrefs.hasSearchKey` once per conversation, so a new key applies from the next one. On a model without
  Google Search with no Search key, the idle home shows a quiet "Add a search key" line that opens Settings.

`AssistantService` injects `Backends.resolve()` per conversation (`credential` + factory + backend kind), so a
key, backend, or local-host change in Settings applies on the next turn with no restart. The idle home shows
an "Add your Gemini API key" nudge when Gemini is selected and neither source has a key; Local shows a host
address nudge instead. (Note: clipboard "Paste" was dropped — Android clipboards are per-device, so copying a
key on a laptop can't reach the Portal.) Needs `portal-wake` installed/running to
be triggered by "hey jarvis" — or tap **Tap to talk** in the app to start a turn directly (foreground).

