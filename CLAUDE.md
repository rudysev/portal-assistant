# CLAUDE.md — Portal-Assistant

Onboarding for an agent picking up this project. Read `README.md` for the full picture; this is the fast
version.

## What this is

**Jarvis** — a conversational assistant for the Portal+ (model "aloha", Android 9 / API 28), package
`com.portal.assistant`, currently powered by the Gemini Live API (the model is a swappable seam, not the
app's identity). **Triggered by `portal-wake`** (no wake-word/Vosk here). Responds as background
voice (orange bar, no screen takeover); the foreground 2-way chat UI (Phase 3) is built. No skills — those
come later as plugins. minSdk 28 / targetSdk 29 / compileSdk 36. No Google Mobile Services.

## Status — Phase 2.a + 2.b (multi-turn) done; Phase 3 chat UI built

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
  can't reclaim mid-conversation. **Buffer-while-connecting** is now built (`AssistantEngine.connectBuffer`):
  mic frames captured during CONNECTING are flushed at the LISTENING boundary so the foreground connect
  window's opening words aren't clipped (a one-breath *wake* swoop stays gated by portal-wake's detection
  upstream — see Known limitations). Still **deferred**: a **"goodbye" fast-exit**.
- **Phase 3 — chat UI.** ✅ Built. A foreground Compose chat (`ui/ConversationScreen` + `theme/`, with the
  bundled **Inter** typeface in `theme/Type` so the brand doesn't depend on the device font) in our
  warm-orange **"Jarvis"** identity (model name as a subtitle). It renders three center states by
  `(turns, phase)`:
  - **Idle home:** an ambient clock/date, the greeting, and a "Try asking" row of **suggestion chips** —
    dynamic, e.g. "Turn off the lights" appears only when the Kasa tool provider is enabled. A slow
    `AmbientGlow` breathes behind it. **Tapping a chip sends its text as the first turn** (not just opening
    the mic): `LiveClient.sendText` → a `clientContent` text turn, threaded as `initialText` via `SOURCE_CHIP`.
  - **Connecting / listening (pre-transcript):** a center-stage `ConversationStatus` — a large cross-faded
    "Connecting…/Listening…" word above a reactive `ListeningOrb` (indeterminate pulse while connecting; mic-
    level scaling while listening). The orb is the sole audio-reactive element here, so the bottom bar shows
    only the End button.
  - **Transcript:** bottom-anchored turns (user chips + large model text, word-by-word from
    `outputAudioTranscription`), dimmed older turns, runtime-responsive width (`BoxWithConstraints`),
    auto-scroll with pause-on-scroll-up + a scroll-to-bottom FAB, and a **"thinking" dots** indicator that
    fills the model's pre-output gap (Search/tool latency). Model markdown is stripped to plain prose
    (`conversation/Markdown.strip`, applied in `Transcript.appendModel`) so the spoken-answer transcript
    reads cleanly.

  **Active timers** render as live countdown cards above the content in any state (`TimerCards`, fed by the
  process-wide `conversation/tools/TimerStore`; ✕ to cancel). **Failures** (offline / couldn't-connect /
  connection-lost / missing key / mic-permission denied) surface as a dismissible `NoticeBanner` via
  `ConversationHub.notice` (+ `system/NetworkStatus` to tell "offline" from a service error). **Settings**
  (`ui/SettingsScreen`): model picker, default-music-app picker, location override, external-tool toggles.

  The Activity binds to the live conversation via the process-singleton `conversation/ConversationHub`: the
  engine publishes `session: StateFlow<ConversationSession>` (id, phase, turns) + a high-frequency
  `audioLevel` + a transient `notice`; the orange bar is gated by `ui/UiVisibility.inForeground` (a separate
  seam, not on the transcript bus). portal-wake yields to a foreground tap by *detecting* our recording (no
  broadcast). **Session entry model:** the foreground **mic** *continues* the on-screen conversation (resume —
  replays recent turns via `ResumeContext.withHistory`, bounded by `RESUME_MAX_CHARS`); a tapped **chip** or
  the top-right **"+"** starts a **new** session; a **"hey jarvis"** wake always starts **fresh**. Resume is
  gated to the mic tap (`AssistantEngine.start(resume = source == SOURCE_TAP)`). A finished transcript **stays
  visible** but the app **reopens fresh** when idle (`MainActivity.onResume` clears when `phase == IDLE` —
  never a *live* conversation). **Paced word reveal:** the engine computes how many words have been "spoken"
  from the *audio actually played* (`RevealProgress.wordsToShow` = played÷received bytes → words) and stamps
  `Turn.revealedWords`; the UI shows that many words while SPEAKING, then snaps to full text, each new word
  fading in once (`RevealingModelText`, sliced by `RevealProgress.wordRanges`). Looping decorative
  animations hold still under the system "remove animations" setting (`ui/Motion.rememberReduceMotion`).
  **Deferred:** better ASR accuracy; weather glance.

## Architecture (the conversation — Phase 2.a)

A **pure reducer + thin I/O shell** (the same pure-logic pattern as portal-wake's `WakeMatcher`):
- `conversation/ConversationState.kt` — pure `reduce(state, event, multiTurn)` state machine
  (CONNECTING → LISTENING → SPEAKING → ENDED), **fully unit-tested**. Exactly two client timers:
  **no-speech** (release the mic when the user stops; re-armed on transcription) and a **stall
  watchdog** (model took the turn but produced no audio/`turnComplete`). Race-free turn end =
  `turnComplete && playbackIdle && no tools in flight` — replaces the reference app's CAS latch.
  - **Stall = dead-air clock (arm-on-drain).** The server can stream a long answer's audio *ahead of
    realtime*, so it sits queued in `PcmPlayer` (which paces playback via blocking `AudioTrack` writes). The
    reducer arms the stall only when nothing is playing — the pre-first-audio Search gap (`ModelStarted`) or
    when playback **drains** without a `turnComplete` (`PlaybackIdle`) — and **cancels** it while audio flows
    (`PlaybackBusy`). So it never fires mid-playback and never flushes a long answer. Don't "simplify" it back
    to firing on time-since-last-*received* chunk (the old bug that cut long answers off mid-sentence).
- `conversation/AssistantEngine.kt` — the impure shell: wires `LiveClient` + `MicCapture` + `PcmPlayer`
  + the reducer + the two timers + the overlay on **one Handler thread** (every callback posts an event
  → no locks). Half-duplex: the mic is muted while the model speaks. `MIC_GAIN` amplifies the *forwarded*
  audio so the Live server can transcribe room-distance speech (handset mic only — no far-field array;
  device-tuned, single seam in `outgoingAudio()`). Also: an optional `initialText` (chip-send) is sent on
  `Event.Ready` (`sendInitialText`); an unexpected socket drop (`onError`/`onClosed` while not ended) posts a
  user-facing `ConversationHub.notice` (`surfaceDisconnect`; message picked by `connectedOk` + `NetworkStatus`).
- `conversation/Markdown.kt` — pure, unit-tested markdown stripper (bold/italic, headings, bullet/numbered
  list markers, inline code, links), applied in `Transcript.appendModel` (raw kept internally, full
  accumulation re-stripped each delta) so the spoken-answer transcript reads as plain prose, not raw markup.
- `gemini/LiveClient.kt` — Gemini Live WebSocket (OkHttp). `googleSearch` on, **plus function-calling**: the
  `conversation/tools/` `ToolRegistry`'s `functionDeclarations` are sent in `buildSetup`, and `toolCall`/
  `toolCallCancellation` are parsed and answered (`sendToolResponse`). Built-in tools: `portal.get_time`,
  `portal.set_timer`/`list_timers`/`cancel_timer`, `portal.get_volume`/`set_volume`/`adjust_volume`/`set_mute`,
  `portal.get_brightness`/`set_brightness`/`adjust_brightness`, `portal.get_do_not_disturb`/`set_do_not_disturb`,
  `portal.play_music` (play a song/artist/album/playlist by name — **multi-app**: routes to the user's default
  music app, or an app the user names in the request (`app` param) which is honoured over the default; an
  optional `type` hint (song/artist/album/playlist) pins the `MEDIA_PLAY_FROM_SEARCH` focus for apps that
  honour it. Pure
  routing is `MediaRouting` (target selection + per-app play strategy); `MediaControl.play` fires it via a
  fallback chain — Spotify's `spotify:search:<query>` VIEW deep link (device-verified to start the top result
  on the Portal's locked-down Spotify; broad/artist-level on purpose because that Spotify is **Free tier**,
  which can't on-demand-play one exact track but *can* shuffle-play an artist/playlist), else the generic
  targeted `MEDIA_PLAY_FROM_SEARCH` intent, else just launching the app. Installed music apps are discovered
  via `PackageCatalog.musicApps` (runtime: MediaBrowserService ∪ CATEGORY_APP_MUSIC launchers, no hardcoded seed) and the
  default is set in Settings (`AppPrefs.defaultMusicPkg`, self-healing). A named app is matched with the same
  pure `AppMatch` as `open_app`),
  `portal.media_control`/`set_repeat` (repeat one/all/off on the active session, gated on the app advertising
  `ACTION_SET_REPEAT_MODE`)/`now_playing`, `portal.open_app` (launch an installed app by name — `AppLauncher` +
  pure `AppMatch`; app enumeration shared via `PackageCatalog.launchable`). Inbound parsing is the pure, unit-tested `parseServerMessage`; `buildSetup` is unit-tested
  too. A tapped suggestion chip sends a **text turn** via `sendText`/`buildClientText` (`clientContent` +
  `turnComplete` — the model answers without waiting on VAD), also unit-tested.
- `conversation/AfterSpeech.kt` — a tiny queue for side effects that must wait until the assistant stops
  talking (muting the media stream, enabling DND — both silence playback, so they'd eat their own spoken
  confirmation). Controllers `post()` the effect; the reducer emits `Action.FireAfterSpeech` at **turn-end**
  (`turnComplete && playbackIdle && no tools in flight`, after `PcmPlayer`'s hardware-tail drain) and the
  engine runs the queue then. Replaces the old per-controller fixed `Handler` delays (no more guessing 2.5 s
  vs 4 s). A barge-in keeps the turn in SPEAKING, so a queued effect defers to the post-interrupt turn-end.
- `audio/PcmPlayer` (24 kHz native-audio playback), `audio/SpeechAudio`, `audio/PcmGain` (pure, tested),
  `audio/MicCapture` (16 kHz capture), `util/Http` (shared OkHttp singleton).

## Design rules — don't break these

- **No wake-word here.** portal-wake owns detection; we receive `ACTION_WAKE` and run a turn.
- **Send no "done" signal.** portal-wake reclaims by *detecting* we stopped recording (no `WAKE_DONE`).
  Hold the mic only while the conversation is live, release cleanly when finished. (2.b: keep the
  `AudioRecord` open across turns — mute between turns — so portal-wake can't reclaim mid-conversation.)
- **Background = no screen takeover.** A triggered turn must NOT launch the Activity — just the
  `RecordingOverlay` bar. The chat UI (Phase 3) appears only when the user opens the app.
- **Orange bar only while the mic is recording** (LISTENING). Off while the model speaks (mic muted).
- **Don't assume timeouts.** Exactly two client timers, each justified; turn-taking is otherwise the
  Gemini server VAD. Any new delay must be justified, not guessed.
- **Mic config:** `VOICE_RECOGNITION`, 16 kHz mono, no audio effects (matches portal-wake; proven).
- **Gain only the conversation stream** (`PcmGain` on forwarded frames) — never the wake capture (that's
  portal-wake's; gain there masks its dead-mic signal).

## Layout

`wake/WakeHandoffReceiver` (trigger entry) · `service/AssistantBootReceiver` (boot/STANDBY → bring the
resident service up) · `service/AssistantService` (**resident** foreground service: mic-less standby ↔
one-conversation host; `START_STICKY`, returns to standby — not `stopSelf`; `prewarm()` warms OkHttp +
classes + DNS + the tool-provider snapshot) · `conversation/` (`ConversationState` reducer + `AssistantEngine`; `ConversationHub` =
session bridge publishing `ConversationSession` + `audioLevel` + `notice`; `Transcript` + `Markdown`,
`ResumeContext`, `RevealProgress`/`RevealTracker`; `tools/` incl. `TimerScheduler` + the observable
`TimerStore`) · `gemini/LiveClient` · `audio/` (`MicCapture`, `PcmPlayer`, `SpeechAudio`, `PcmGain`) ·
`system/` (`AppPrefs`, `LocationProvider`, `NetworkStatus`) · `ui/` — `ConversationScreen` (idle home +
`ConversationStatus`/`ListeningOrb`/`AmbientGlow`/`TimerCards`/`NoticeBanner`/suggestion chips), `SettingsScreen`,
`AudioVisualizer`, `theme/` (warm-orange `Theme` + bundled-Inter `Type`), `RecordingOverlay` (the background
bar) + `UiVisibility` (foreground flag) · `MainActivity` (launcher + tap-to-talk + chip-send). **Shared code from `portal-commons`** (the sibling `../portal-commons`): pure-JVM
`com.portal:commons` (`DebugLog`, `PcmLevel`, `PcmCaptureSession`/`PcmDevice`, `PcmCaptureFormat`) +
the Android-library `com.portal:commons-android` (`com.portal.commons.audio.AudioRecordPcmDevice`, the
shared mic shell behind `PcmDevice`). Two plugin contracts are **not** shared deps — we mirror their literal strings: the outbound
`ToolContract` is **local to this app** (`conversation/tools/ToolContract`); the inbound wake contract
(`com.portal.wake.action.WAKE` / `…extra.ID`) is **portal-wake's** `WakeContract`, mirrored as literals in
`wake/WakeHandoffReceiver`.

## Build / run

```bash
git submodule update --init --recursive   # from the portal-apps workspace: pull commons + the apps
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew testDebugUnitTest assembleDebug    # Android SDK resolved from the environment (ANDROID_HOME)
./setup.sh   # install + grant mic + draw-over-apps + launch once (clears the "stopped" state)
npx -y @meta-quest/hzdb adb shell "cat /sdcard/Android/data/com.portal.assistant/files/debug.txt"
```

The Gemini API key is **not** baked in — `BuildConfig.GEMINI_API_KEY` is a blank dev seam. Each user
supplies **their own** key (BYOD), set two ways:
- **At install** — `./setup.sh` checks whether a key is already on the device and, if not, walks the user
  through creating one and prompts them to paste it (full setup takes **no** key argument; pass one
  non-interactively only via `./setup.sh --key-only <KEY>`). It writes the key to the app's external files
  dir (`api_key.txt`); the app imports it into prefs on first launch and deletes the file (import-once,
  `AppPrefs.importProvisionedKey`, called from `MainActivity`/`AssistantService` onCreate). So a
  non-technical user is set up with **no typing**.
- **In the app** — **Settings → API key** lets them add or change it manually; **Save & verify** confirms
  the key against the API (`GeminiKeyCheck`) before storing via `AppPrefs.setApiKey`.

`AssistantService` injects `AppPrefs.apiKey() ?: BuildConfig.GEMINI_API_KEY` per conversation, so a key
changed in Settings applies on the next turn with no rebuild. The idle home shows an "Add your Gemini API
key" nudge when neither source has a key. (Note: clipboard "Paste" was dropped — Android clipboards are
per-device, so copying a key on a laptop can't reach the Portal.) Needs `portal-wake` installed/running to
be triggered by "hey jarvis" — or tap **Tap to talk** in the app to start a turn directly (foreground).

## Known limitations

- **First-query clip**: the connect-window buffer (`AssistantEngine.connectBuffer`) now recovers speech
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

`LiveClient`, `PcmPlayer`, `SpeechAudio`, the `RecordingOverlay`, and `MicCapture`'s `AudioRecord` config
were adapted from `portal-gemini-chat` (proven on-device), then **simplified**: no tools/skills, and a
**per-conversation** Live connection (the reference's persistent socket forced session-recycle/stale-guard
machinery + ~10 timers + a CAS latch — all dropped). `ConversationState`, `AssistantEngine`, and `PcmGain`
are new here. `PcmLevel`/`DebugLog` live in `portal-commons`; the wake contract is portal-wake's.
