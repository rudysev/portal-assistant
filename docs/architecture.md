# Architecture — Portal-Assistant

Detail behind the map in `CLAUDE.md`. The hard rules live there, not here.


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

