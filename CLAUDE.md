# CLAUDE.md — Portal-Assistant

Onboarding for an agent picking up this project. Read `README.md` for the full picture; this is the fast
version.

## What this is

**Jarvis** — a conversational assistant for the Meta Portal (gen1 Android 9 + gen2 Android 10), package
`com.portal.assistant`, currently powered by the Gemini Live API behind a model-neutral
`conversation/backend/VoiceBackend` seam — the reducer + engine are fully decoupled from the `gemini`
package (see the architecture section). **Triggered by `portal-wake`** in the background, plus its **own
foreground** Vosk detector on gen2 (see the wake note below for why the split). Responds as background
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
  can't reclaim mid-conversation. The **connect buffer** is now built (in the Mic gate, `conversation/MicGate`):
  mic frames captured during CONNECTING are flushed at the LISTENING boundary so the foreground connect
  window's opening words aren't clipped (a one-breath *wake* swoop stays gated by portal-wake's detection
  upstream — see Known limitations). Still **deferred**: a **"goodbye" fast-exit**.
- **Phase 3 — chat UI.** ✅ Built. A foreground Compose chat (`ui/ConversationScreen` + `theme/`, with the
  bundled **Inter** typeface in `theme/Type` so the brand doesn't depend on the device font) in our
  warm-orange **"Jarvis"** identity (model name as a subtitle). It renders three center states by
  `(turns, phase)`:
  - **Idle home:** an ambient clock/date, the greeting, and a short row of **static suggestion chips**
    ("What's the weather?", "Set a 10-minute timer") — a calm, clock-first glance, not a command menu.
    Enabled external providers (Kasa, calendar, …) are described in the **session system prompt** at connect
    time, not as extra chips. A slow `AmbientGlow` breathes behind it. **Tapping a chip sends its text as the
    first turn** (not just opening the mic): `VoiceBackend.sendText` → `GeminiWireFormat.userText`, a `clientContent` text turn — the
    `Trigger.SuggestionChip`'s text becomes the opening's `firstText`.
  - **Connecting / listening (pre-transcript):** a center-stage `ConversationStatus` — a large cross-faded
    "Connecting…/Listening…" word above a reactive `ListeningOrb` (indeterminate pulse while connecting; mic-
    level scaling while listening). The orb is the sole audio-reactive element here, so the bottom bar shows
    only the End button.
  - **Transcript:** bottom-anchored turns (user chips + large model text, word-by-word from
    `outputAudioTranscription`), dimmed older turns, runtime-responsive width (`BoxWithConstraints`),
    auto-scroll with pause-on-scroll-up + a scroll-to-bottom FAB, and a **"thinking" dots** indicator that
    fills the model's pre-output gap (Search/tool latency). Model markdown is stripped to plain prose
    (`conversation/Markdown.strip`, applied by `Transcript` to each Jarvis delta) so the spoken-answer transcript
    reads cleanly.

  **Active timers** render as live countdown cards above the content in any state (`TimerCards`, fed by the
  process-wide `conversation/tools/TimerStore`; ✕ to cancel). **Failures** (offline / couldn't-connect /
  connection-lost / missing key / mic-permission denied) surface as a dismissible `NoticeBanner` via
  `ConversationHub.notice` (+ `system/NetworkStatus` to tell "offline" from a service error). **Settings**
  (`ui/SettingsScreen`): model picker, voice picker (Gemini only — the Chosen speaker from the
  `gemini/GeminiSpeaker` catalog, or Server default; `AppPrefs.speakerId` → `BackendConfig.speaker` →
  `GeminiWireFormat.setup`'s `speechConfig`, omitted for Server default; tapping a dialog row also **previews**
  it — `gemini/SpeakerPreview` (pure, unit-tested reducer + the preview's session config, built by `GeminiBackend.Factory` like a conversation's) driven by the thin
  `gemini/SpeakerPreviewPlayer` shell: a short Live session with the selected model + candidate Speaker, one
  "introduce yourself" text turn, its own `PcmPlayer`; one preview at a time, stopped when the dialog closes,
  paused while a conversation is live — independent of the engine; a preview that played to completion is
  kept in memory for that dialog open, so re-tapping its row replays it with no session), a **Sounds** section
  right after the backend/API-key sections, shown for both backends (the Listening tone switch —
  `AppPrefs.listeningToneEnabled`, default on; switching it on previews the tone via `ListeningTonePlayer`, off
  is silent, and no preview plays while a conversation is live — pure `ListeningTone.previewsOnSwitch`), location
  override, default-music-app picker, external-tool toggles (shared `SettingsSwitchRow`).

  The Activity binds to the live conversation via the process-singleton `conversation/ConversationHub`: the
  engine publishes `session: StateFlow<ConversationSession>` (id, phase, turns) + a high-frequency
  `audioLevel` + a transient `notice`; the orange bar is gated by `ui/UiVisibility.inForeground` (a separate
  seam, not on the transcript bus). portal-wake yields to a foreground tap by *detecting* our recording (no
  broadcast). **Session entry model:** the foreground **mic** *continues* the on-screen conversation (resume —
  replays recent turns via `ResumeContext.withHistory`, bounded by `ConversationOpening.RESUME_MAX_CHARS`); a
  tapped **chip** or the top-right **"+"** starts a **new** conversation; a **"hey jarvis"** wake always starts
  **fresh**. Resume is gated to the mic tap (`Trigger.MicTap`, decided by `ConversationOpening`). A finished transcript **stays
  visible** but the app **reopens fresh** when idle (`MainActivity.onResume` clears when `phase == IDLE` —
  never a *live* conversation). **Paced word reveal:** the pure `conversation/Transcript` owns the turns and the
  reveal, driven by six events (`on(event)`: listening started, interrupted, user/Jarvis text, audio received/played
  — the engine fills in `PcmPlayer`'s played bytes and publishes `turns` only when they change). It computes how
  many words have been "spoken" from the *audio actually played* (`RevealProgress.wordsToShow` = played÷received
  bytes → words), restarting at listening start and on an interruption (so a tool-call answer reveals from its
  first word too), and stamps `Turn.revealedWords` on the active Jarvis turn; the UI shows that many words while
  SPEAKING, then snaps to the turn's text, each new word fading in once (`RevealingModelText`, sliced by
  `RevealProgress.wordRanges`). At a restart the ending turn keeps its full text if all its audio played, or its
  text is **cut at the words heard** if some never did (an interruption flushed it) — dropped if none were. Looping decorative animations hold still under the system "remove animations" setting (`ui/Motion.rememberReduceMotion`); the
  Voice dialog's loading spinner is information, not decoration, so `ui/Motion.LoadingSpinner` turns on the
  raw frame clock, which that setting (which freezes Material's indeterminate spinner) doesn't scale. The
  audio-reactive orbs (`ListeningOrb`, `AudioVisualizer`) are information too: their size eases via
  `ui/Motion.animateFloatOnFrameClock` (Compose's default spring stepped by hand — pure, JVM-tested
  `FrameClockSpring`), because that setting would otherwise snap the spring and the orb would jump.
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
    reducer arms/cancels the stall only via `stallDecision(state, event)` (in `conversation/StallDecision.kt`) —
    nothing playing **and** no tools
    in flight for the pre-first-audio gap (`ModelActivity`), further `ModelActivity` while still idle (local
    LLM/tool think), playback **drain** without a `turnComplete` (`PlaybackIdle`), and tool-flight drain. It
    **cancels** while audio flows (`PlaybackBusy`) or a mid-turn tool starts. Never re-arm from
    transcript/generating while audio is queued — that cuts long answers mid-sentence. Don't "simplify" it
    back to time-since-last-*received* chunk either (same class of bug).
- `conversation/backend/VoiceBackend.kt` — the **model-neutral seam** the engine depends on (interface:
  `connect`/`sendAudio`/`sendText`/`sendToolResponse`/`close` + a 12-callback `Listener`, with a documented
  16 kHz-in / 24 kHz-out PCM audio contract). `BackendConfig` carries the session inputs (Gemini's API key
  becomes the generic `credential`); `VoiceBackendFactory` builds one. `conversation/backend/Backends.kt` is
  the **single composition point** that picks the concrete backend — deliberately the only file in
  `conversation/` that imports `gemini` (and `local`). **Both backends run on one transport**,
  `conversation/backend/WebSocketVoiceBackend` (the only code that owns a socket: dial, setup frame on open, the
  setup gate holding back audio/text/tool results until ready, the first-audio guarantee, typed errors — one labelled
  `BackendError.Transport` on failure, a close reason the wire format finds a user message in
  (`closeMessage` — Gemini's, e.g. a rejected key) or its error frame as a `BackendError.Server` — closed/closing, idempotent close); each backend supplies only a pure `WireFormat` (encode setup / audio /
  user text / tool results, decode text + binary frames into `BackendEvent`s, plus its "Live"/"Local" error label),
  with tool calls parsed and results framed by the shared `ToolCallWire`. Each backend is a top-level package whose
  factory object hands the transport its wire format, connect address and TLS client: `gemini/GeminiBackend`
  (`GeminiWireFormat` — JSON text frames, base64 audio, binary frames read as UTF-8 JSON, `goAway` → closing soon;
  the configured endpoint or `DEFAULT_ENDPOINT` + the URL-encoded `key`; system trust) and the opt-in LAN backend
  `local/LocalBackend` (`LocalWireFormat` — JSON control + raw PCM binary frames; Pipecat voice host over `wss://`,
  trust-all TLS, encryption without cert setup — plus its tool-schema mapping `LocalToolMapping` and host address
  parsing `LocalVoiceHost`). `conversation/backend/` holds
  no concrete backend: only the seam (incl. the per-backend wire options, slices of `BackendConfig`), the shared
  transport (`BackendEvent`, `WireFormat`, `WebSocketVoiceBackend`, `ToolCallWire`) and the composition point.
  `Backends.resolve()` picks the factory + credential (API key vs host URL) per `AppPrefs.voiceBackendKind`;
  `Backends.warmDns()` pre-resolves the selected host. The reducer + engine never touch `gemini` or `local` directly. Seam contract is unit-tested
  (`conversation/backend/VoiceBackendTest`, `conversation/backend/WebSocketVoiceBackendTest`,
  `gemini/GeminiWireFormatTest`, `local/LocalWireFormatTest`).
- `conversation/Trigger.kt` + `conversation/ConversationOpening.kt` — **how a conversation opens**. A closed
  `Trigger` (`WakeWord(wakeId)` / `MicTap` / `SuggestionChip(text)`) is typed end to end; the only untyped hop is
  the Intent into the service (encoded + decoded side by side in `AssistantService`'s companion; an undecodable
  Intent starts nothing). Pure `ConversationOpening.decide(trigger, OpeningInputs)` returns an `Opening` or a
  `Refusal`: `OpeningInputs` is the plain-value snapshot the engine gathers (backend choice, model, Chosen speaker,
  Listening tone setting, `now`, location override + IP-geo, on-screen turns, tool declarations + external prompt
  lines); `Opening` is the transcript seed (fresh / resume with turns shown + replayed, within `RESUME_MAX_CHARS`),
  the `BackendConfig` with the finished session prompt (assembled here via pure `SessionPrompt`: built-in
  `SystemPrompt` + enabled external-provider bullets from the same `ExternalToolSpec.parse` pass as the tool
  declarations + `SystemContext` time/location — override > IP-geo > unknown — + `ResumeContext` on a resume),
  voice query (false only for a chip), Listening tone plays (setting on ∧ voice query) and first text.
  `refusalFor(choice)` is the **only** credential rule (blank credential → the backend's "isn't set up yet"
  notice); `decide` calls it first and the idle home's setup nudge calls it too. No Android, no I/O, no clock
  reads — fully unit-tested (`ConversationOpeningTest`).
- `conversation/AssistantEngine.kt` — the impure shell: `start(trigger)` gathers `OpeningInputs`, calls
  `ConversationOpening.decide`, and returns the `Refusal` (nothing opened) or starts: wires the `VoiceBackend`
  (factory from `Backends.Choice`, config from the opening) + `MicCapture` + `PcmPlayer`
  + the reducer + the two timers + the overlay on **one Handler thread** (every callback posts an event
  → no locks). Every mic frame goes through the **Mic gate** (below); the capture callback only applies its
  outcome. Tools are the **Tool runner**'s (below): the engine only maps the reducer's tool actions onto it.
  Also: the opening's `firstText` (chip-send) is sent on `Event.Ready` (`sendInitialText`); an unexpected socket
  drop (`onError`/`onClosed` while not ended) posts a user-facing `ConversationHub.notice` (`surfaceDisconnect`;
  pure `DisconnectNotice`: offline copy first, then a server message as is, else "Connection lost" / "Couldn't
  reach Jarvis" by `connectedOk` — raw socket text is only logged).
  The **Listening tone** plays at the conversation's mic open in `start()` (only there — never on a follow-up
  re-listen) when the opening says so, played async by `ListeningTonePlayer` on `SpeechAudio`'s attributes (one
  track built at prewarm and replayed — `audio/PrebuiltTrack`); the setting is `AppPrefs.listeningToneEnabled`
  (default on), read once in the snapshot; the tone's start/failure callbacks feed the Mic gate.
- `conversation/MicGate.kt` — the **Mic gate**, the whole mic frame path: built once per conversation at mic open
  (voice query? tone plays?), fed listening started/stopped from the handler and tone started/failed from the tone
  player, it turns each frame (+ its capture time — it never reads a clock) into a plain `FrameOutcome`: the ordered
  audio to send, the indicator (none / bar at rest / level → Hub level + orange bar, gated on
  `UiVisibility.inForeground` by the engine), speech heard (→ the no-speech grace's last-local-speech stamp) and log
  lines. Inside, in order: **Listening tone suppression** (frames captured while the tone is pending — a failed tone
  ends the wait — or before its window ends, tone start + `DURATION_MS` + device-measured `ECHO_TAIL_MS`, are
  dropped: not connect-buffered, sent, or levelled; while listening they show the bar at rest so the tone never
  delays it; one drop report per conversation); the **half-duplex mute**; the **connect buffer** (voice query only,
  capped at 50 frames drop-oldest, flushed in capture order before the first listening frame, ended when listening
  stops); **`MIC_GAIN`** on the sent audio so the Live server can transcribe room-distance speech (handset mic only —
  no far-field array; device-tuned, the single seam for outbound audio); and **speech heard**, measured on the gained
  frame. Pure and fully JVM-tested by frame sequences (`MicGateTest`), including the tone × connect-buffer
  interaction (the tone's window outlasts the ~440 ms connect, so with the tone on the buffer holds little or nothing).
- `conversation/Markdown.kt` — pure, unit-tested markdown stripper (bold/italic, headings, bullet/numbered
  list markers, inline code, links), applied by `Transcript` to Jarvis's text (raw kept internally, full
  accumulation re-stripped each delta) so the spoken-answer transcript reads as plain prose, not raw markup.
- `gemini/GeminiWireFormat.kt` — Gemini Live's frames (the socket is the shared transport's). `googleSearch` on,
  **plus function-calling**: the `conversation/tools/` `ToolRegistry`'s `functionDeclarations` are sent in `setup`,
  and `toolCall`/`toolCallCancellation` are decoded and answered (`toolResults`). Built-in tools: `portal.get_time`,
  `portal.set_timer`/`list_timers`/`cancel_timer`, `portal.get_volume`/`set_volume`/`adjust_volume`/`set_mute`,
  `portal.get_brightness`/`set_brightness`/`adjust_brightness`, `portal.get_do_not_disturb`/`set_do_not_disturb`,
  `portal.play_music` (play a song/artist/album/playlist by name — **multi-app**: routes to the user's default
  music app, or an app the user names in the request (`app` param) which is honoured over the default; an
  optional `type` hint (song/artist/album/playlist) pins the `MEDIA_PLAY_FROM_SEARCH` focus for apps that
  honour it. Pure
  routing is `MediaRouting` (target selection + per-app play strategy); `MusicLauncher.play` (behind
  `MediaControl.play`) fires it via a
  fallback chain — Spotify's `spotify:search:<query>` VIEW deep link (device-verified to start the top result
  on the Portal's locked-down Spotify; broad/artist-level on purpose because that Spotify is **Free tier**,
  which can't on-demand-play one exact track but *can* shuffle-play an artist/playlist), else the generic
  targeted `MEDIA_PLAY_FROM_SEARCH` intent, else just launching the app. Installed music apps are discovered
  via `PackageCatalog.musicApps` (runtime: MediaBrowserService ∪ CATEGORY_APP_MUSIC launchers, no hardcoded seed) and the
  default is set in Settings (`AppPrefs.defaultMusicPkg`, self-healing). A named app is matched with the same
  pure `AppMatch` as `open_app`),
  `portal.media_control`/`set_repeat` (repeat one/all/off on the active session, gated on the app advertising
  `ACTION_SET_REPEAT_MODE`)/`now_playing`, `portal.open_app` (launch an installed app by name — `AppLauncher` +
  pure `AppMatch`; app enumeration shared via `PackageCatalog.launchable`). Every frame — `setup`, `audio`, `userText`, `toolResults`
  and `decode` — is pure and unit-tested (`GeminiWireFormatTest`). A tapped suggestion chip sends a **text turn**
  via `sendText`/`userText` (`clientContent` + `turnComplete` — the model answers without waiting on VAD).
- `conversation/tools/ToolRunner.kt` — the **Tool runner**, how one conversation's tools run: it owns the
  `ToolSet` (interface: declarations, external prompt lines, `invoke`, `dispose` — `ToolRegistry` is the production
  one, routing unchanged: built-ins win, then external providers, then `unknown tool`), the `AfterSpeech` queue, the
  single-thread `tool-exec` executor and each batch's bookkeeping. The engine maps `ExecuteTools` → `run`,
  `FireAfterSpeech` → `turnEnded`, hands it `state.toolsInFlight` after every event (`keepOnly` cancels whatever
  the reducer no longer waits on — never run if queued, interrupted if running) and calls `dispose()` once on
  teardown; the Opening's inputs read declarations + prompt lines from it. **Batch promise:** N calls in, one
  ordered list of N results out, delivered once on the engine's handler — cancelled → `{"error":"cancelled"}`,
  thrown → `{"error": message}`, each call settled exactly once (a cancel counts it down, so a partially
  cancelled batch can't wedge the turn in SPEAKING) — and nothing after `dispose()`. Built per conversation by
  `create(context, deliver)`; JVM-tested with a fake tool set and a hand-stepped executor (`ToolRunnerTest`).
- `conversation/tools/AfterSpeech.kt` — a tiny queue for side effects that must wait until the assistant stops
  talking (muting the media stream, enabling DND — both silence playback, so they'd eat their own spoken
  confirmation). Both controllers queue it through one shared `DeferredToggle` (report the intended state now —
  a pending effect counts as on — cancel on an explicit change, drop on dispose; JVM-tested); the reducer
  emits `Action.FireAfterSpeech` at **turn-end** (`turnComplete && playbackIdle && no tools in flight`, after
  `PcmPlayer`'s hardware-tail drain) and the Tool runner runs the queue then (`turnEnded`). Replaces the old per-controller fixed `Handler` delays (no more
  guessing 2.5 s vs 4 s). A barge-in keeps the turn in SPEAKING, so a queued effect defers to the post-interrupt turn-end.
- `audio/PcmPlayer` (24 kHz native-audio playback), `audio/SpeechAudio`, `audio/PcmGain` (pure, tested),
  `audio/PrebuiltTrack` (build-once, replay-per-play track with self-healing rebuild; JVM-tested),
  `audio/MicCapture` (16 kHz capture), `util/Http` (shared OkHttp singleton + `lanVoice` trust-all client for
  the local backend's `wss://` socket).

## Design rules — don't break these

- **Service = the resident host; engine = one conversation.** `AssistantService` owns everything that outlives a
  conversation: staying warm (standby, prewarm, the foreground notification), receiving Triggers (portal-wake
  hand-off, the app, gen2 foreground wake detection), allowing at most one conversation at a time, reacting to a
  refusal (post the notice, re-arm detection), and returning to standby. It never decides how a conversation opens
  or runs. `AssistantEngine` is one conversation from Trigger to end: built per Trigger and discarded after, with no
  state carried between conversations. It gathers the conversation's inputs, decides the opening (or refuses), runs
  the I/O on one handler thread driven by the reducer (voice backend, mic + Mic gate, player, bar, the two timers,
  tools), surfaces mid-conversation failures (e.g. a dropped connection), ends itself and tells the service. It never
  touches wake detection, notifications or standby. Settings that shape the opening (backend, model, Chosen
  speaker, Listening tone, location) are read once per conversation; a tool reads its own setting when it runs
  (e.g. the default music app at `play_music` time).
- **Runs on gen1 AND gen2; the wake path differs by device.** On gen1 (API 28) portal-wake owns detection
  and hands off via `ACTION_WAKE` in any app state. On gen2 (API 29+) portal-wake is inert (Android 10
  silences a background mic), so the assistant runs its **own foreground** Vosk detector while on screen
  (**Foreground wake**: `wake/ForegroundWake` owns the detector, the one-time model download, the setup status
  and the retry/give-up rules behind two ports — `WakeModel` (`WakeModelInstaller`) and `Detector` (`VoskDetector`,
  the shared `WakeMicEngine` + `WakeDetectors.vosk(modelDir)` from portal-commons) — JVM-tested in
  `ForegroundWakeTest`; the service only forwards foregrounded/backgrounded, conversation starting/ended and
  close); a match routes through the same `AssistantService.startConversation(...)`. On gen1 the two apps share the single mic via portal-wake's
  contention/reclaim (no signal). Keep the detector foreground-only and gen2-only — a background mic gets silenced.
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

`wake/` — `WakeHandoffReceiver` (trigger entry), `ForegroundWake` (gen2 Foreground wake) + its ports
`WakeModel`/`Detector` and their adapters `WakeModelInstaller`/`VoskDetector`, `WakeSetupStatus` (the model
setup the idle home shows) · `service/AssistantBootReceiver` (boot/STANDBY → bring the
resident service up) · `service/AssistantService` (**resident** foreground service: mic-less standby ↔
one-conversation host; `START_STICKY`, returns to standby — not `stopSelf`; `prewarm()` warms OkHttp +
classes + DNS + the tool-provider snapshot) · `conversation/` (`ConversationState` reducer + `AssistantEngine` + the `MicGate` frame path; `Trigger` +
`ConversationOpening` (the pure opening decision); `ConversationHub` =
session bridge publishing `ConversationSession` + `audioLevel` + `notice`; `Transcript` + `Markdown`,
`ResumeContext`, `RevealProgress`; `tools/` incl. the `ToolRunner` + `ToolSet`/`ToolRegistry` + `AfterSpeech` + `DeferredToggle`,
`TimerScheduler` + the observable `TimerStore`; `backend/` = the `VoiceBackend` seam + the shared `WebSocketVoiceBackend` transport, `WireFormat`, `BackendEvent`, `ToolCallWire`, `Backends`) · `gemini/` (`GeminiBackend`, `GeminiWireFormat`, `GeminiKeyCheck`, model + Speaker catalogs, Speaker preview) · `local/` (`LocalBackend`, `LocalWireFormat`, `LocalToolMapping`, `LocalVoiceHost`) · `audio/` (`MicCapture`, `PcmPlayer`, `SpeechAudio`, `PcmGain`, `PrebuiltTrack`) ·
`system/` (`AppPrefs`, `LocationProvider`, `NetworkStatus`) · `ui/` — `ConversationScreen` (+ `ConversationStatus`/
`ListeningOrb`/`NoticeBanner`; `IdleHome.kt` has the idle home, `AmbientGlow`, clock and suggestion chips,
`TranscriptView.kt` the transcript, `TimerCards.kt` and `ConversationControls.kt` the rest), `SettingsScreen` (its
sections, with `SpeakerSettings.kt`, `ConnectionSettings.kt` and the shared `SettingsComponents.kt`),
`AudioVisualizer`, `theme/` (warm-orange `Theme` + bundled-Inter `Type`), `RecordingOverlay` (the background
bar) + `UiVisibility` (foreground flag, read only for the orange bar) · `MainActivity` (launcher + tap-to-talk + chip-send). **Shared code from `portal-commons`** (the sibling `../portal-commons`): pure-JVM
`com.portal:commons` (`DebugLog`, `PcmLevel`, `PcmCaptureSession`/`PcmDevice`, `PcmCaptureFormat`) +
the Android-library `com.portal:commons-android` (`com.portal.commons.audio.AudioRecordPcmDevice`, the
shared mic shell, plus `WakeMicEngine` / `WakeMicConfig` / `WakeDetector` / `WakeDetectors.vosk()` for the
gen2 foreground detector). Two plugin contracts are **not** shared deps — we mirror their literal strings: the outbound
`ToolContract` is **local to this app** (`conversation/tools/ToolContract`); the inbound wake contract
(`com.portal.wake.action.WAKE` / `…extra.ID`) is **portal-wake's** `WakeContract`, mirrored as literals in
`wake/WakeHandoffReceiver`.

## Build / run

```bash
git submodule update --init --recursive   # from the portal-apps workspace: pull commons + the apps
# The gen2 "hey jarvis" Vosk model is NOT bundled — the app downloads it at runtime on first gen2 use
#   (wake/WakeModelInstaller → filesDir), so the APK stays lean and gen1 ships no dead weight. Nothing to
#   place at build time. gen1 is unaffected (it uses portal-wake, which bundles its own model).
./gradlew check            # verify: unit tests + Android Lint + detekt + ktlint (kotlinter); formatKotlin fixes style
./gradlew assembleDebug    # needs a local JDK 21 and the Android SDK (JAVA_HOME / ANDROID_HOME)
./setup.sh   # install + grant mic + draw-over-apps + launch once (clears the "stopped" state); also enables .githooks
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

`AssistantService` injects `Backends.resolve()` per conversation (`credential` + factory + backend kind), so a
key, backend, or local-host change in Settings applies on the next turn with no restart. The idle home shows
an "Add your Gemini API key" nudge when Gemini is selected and neither source has a key; Local shows a host
address nudge instead. (Note: clipboard "Paste" was dropped — Android clipboards are per-device, so copying a
key on a laptop can't reach the Portal.) Needs `portal-wake` installed/running to
be triggered by "hey jarvis" — or tap **Tap to talk** in the app to start a turn directly (foreground).

## Android Lint

`check` runs Android Lint on the debug and release variants with `warningsAsErrors` and `abortOnError`, so any
warning fails it (and so pre-push and CI). There is no `lint-baseline.xml`: suppress inline only (`@SuppressLint`,
`tools:ignore`, `//noinspection`), each with a reason comment. The dependency-freshness checks
(`AndroidGradlePluginVersion`, `GradleDependency`, `NewerVersionAvailable`) are disabled, because they query the
network and fail whenever upstream ships a release. Report: `app/build/reports/lint-results-debug.html`.

## detekt

`check` also runs detekt 2.0.0-alpha.6 (`dev.detekt`; still alpha, because detekt 1.x doesn't support AGP 9;
quality-tooling ticket 08 moves it to stable) with type resolution: `detektMain` and `detektTest`, every variant. It
also runs compose-rules 0.6.8 (`io.nlopez.compose.rules:detekt`), which is built against one detekt alpha, so bump the
two together (`gradle/libs.versions.toml`). The config is detekt's defaults plus `config/detekt/detekt.yml`, identical
in portal-commons, portal-wake and portal-assistant (change all three together): the coroutine opt-ins
(`GlobalCoroutineUsage`, `InjectDispatcher`, `SleepInsteadOfDelay`, `SuspendFunSwallowedCancellation`), any
TODO/FIXME/STOPSHIP comment forbidden, `@Composable` exempt from `FunctionNaming`, and warnings as errors. There is no
baseline: suppress inline only (`@Suppress("RuleId")`), each with a reason comment. Lines are capped at 120: detekt's
default `MaxLineLength`, and `max_line_length = 120` in `.editorconfig` for ktlint. Reports:
`app/build/reports/detekt/`.

With KGP 2.4, a compiler warning (`allWarningsAsErrors`) fails the build with only "warnings found and -Werror
specified"; run the build with `--info` to see the warnings themselves.

## Hooks

`git config core.hooksPath .githooks` (done by `./setup.sh`). `pre-commit` runs the `ktlint` and `detekt-cli` CLIs on
staged `.kt`/`.kts` files and `shellcheck` on staged shell scripts (`*.sh`, `*.command`, `.githooks/*`), and rejects on
findings (never auto-fixes; `brew install ktlint shellcheck`). For ktlint it refuses a CLI other than ktlint 1.8.0, the
version kotlinter 5.7.0 bundles (`gradle/libs.versions.toml`), so bump both together. detekt runs there without type
resolution (about a second); the type-resolved tasks and compose-rules run in `check`. The hook refuses a `detekt-cli`
other than the pinned `DETEKT_VERSION` (the Gradle plugin's version, so bump both together). Homebrew only ships detekt
1.x: install the CLI by unzipping
`https://github.com/detekt/detekt/releases/download/v2.0.0-alpha.6/detekt-cli-2.0.0-alpha.6.zip` and putting its `bin/`
on your `PATH`. `pre-push` runs `./gradlew check`. `--no-verify` is the deliberate escape hatch. ktlint config is
`.editorconfig` only (including the `@Composable` function-naming exemption).

## CI

`.github/workflows/ci.yml` runs `./gradlew check assembleDebug` on every push to `main`, on `workflow_dispatch`, and
when a green `portal-commons` run dispatches `portal-commons-green`. It checks out `portal-assistant` and
`portal-commons@main` side by side so that `includeBuild("../portal-commons")` resolves.

A `shellcheck` job lints every tracked shell script (the `provisioning/` installer that ships to end users,
`setup.sh`, `scripts/`, the hooks) with the runner's preinstalled shellcheck. shellcheck isn't part of
`./gradlew check`: the hook and this job cover it. `.shellcheckrc` makes it follow sourced files
(`config.env`, `keyprov.sh`) relative to each script, so a variable set in one file and read in another isn't
reported as unused. `.githooks/pre-commit` and `.shellcheckrc` are identical copies in portal-commons,
portal-wake and portal-assistant; change all of them together.

A separate `build-health` job runs `./gradlew buildHealth` (the `com.autonomousapps.dependency-analysis` plugin)
and fails on any dependency advice: unused, used-but-undeclared or wrongly-configured dependencies. It is not part
of `check` or the hooks, because the advice changes rarely and the analysis is slow. It analyses only this build's
`:app`; the included `../portal-commons` build has its own job. Run `./gradlew buildHealth` yourself after changing
dependencies; the report is at `build/reports/dependency-analysis/build-health-report.txt`.

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

## Agent skills

### Issue tracker

Local markdown under `.scratch/<feature>/` (one file per ticket in `issues/`). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each ticket. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Grilling (`/grill-with-docs`)

Ask **one question at a time, then STOP** and wait for the user's answer. Don't answer your own questions,
don't generate the next round ahead of the answer, and don't begin implementation without the user's
explicit permission.
