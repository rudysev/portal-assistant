# Architecture — Portal-Assistant

Detail behind the map in `CLAUDE.md`. The hard rules live there, not here.


A **pure reducer + thin I/O shell** (the same pure-logic pattern as portal-wake's `WakeMatcher`):
- `conversation/ConversationState.kt` — pure `reduce(state, event, multiTurn)` state machine
  (CONNECTING → LISTENING → SPEAKING → ENDED), **fully unit-tested**. Exactly two client timers:
  **no-speech** (release the mic when the user stops; re-armed on transcription) and a **stall
  watchdog** (model took the turn but produced no audio/`turnComplete`). Race-free turn end =
  `turnComplete && playbackIdle && no tools in flight` — replaces the reference app's CAS latch.
  - **Final turn.** `Event.FinalTurnRequested` (posted by a tool through the `requestFinalTurn` port the engine hands
    the tool-runner factory, always before that batch's `ToolResultsReady`) sets `ConversationState.finalTurn`; the
    turn end (normal or stalled) then ends the conversation instead of re-listening. `Interrupted` keeps it. Results
    sent during a final turn clear `turnComplete` (`awaitsSignOff`): the turn ends on the sign-off's own
    `turnComplete`, or the stall if the model never replies.
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
  `connect`/`sendAudio`/`sendText`/`sendToolResponse`/`close` + a one-method `Listener.onEvent(BackendEvent)`,
  with a documented 16 kHz-in / 24 kHz-out PCM audio contract and the **first-audio invariant**: a turn's
  `BackendEvent.ModelGenerating` always arrives before its first `BackendEvent.Audio`).
  `BackendConfig` carries the session inputs (Gemini's API key
  becomes the generic `credential`); `VoiceBackendFactory` builds one. `conversation/backend/Backends.kt` is
  the **single composition point** that picks the concrete backend — deliberately the only file in
  `conversation/` that imports `gemini` (and `local`). **Both backends run on one transport**,
  `conversation/backend/WebSocketVoiceBackend` (the only code that owns a socket: dial, setup frame on open, the
  setup gate holding back audio/text/tool results until ready, the first-audio guarantee — it injects
  `ModelGenerating` before a turn's first `Audio` when the wire format didn't decode one — and otherwise hands each
  decoded `BackendEvent` on as is, plus its own socket outcomes: `Failed(BackendError.Transport)` on failure,
  `Failed(BackendError.Server)` for a close reason the wire format finds a user message in (`closeMessage` —
  Gemini's, e.g. a rejected key), then `Closed`; a wire format's own error frame decodes to
  `Failed(BackendError.Server)`; idempotent close);
  each backend supplies only a pure `WireFormat` (encode setup / audio /
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
  the Intent into the service (pure `service/TriggerExtras` encodes it to string extras and decodes them back, null
  for a partial one, which starts nothing; `TriggerExtrasTest`). Pure `ConversationOpening.refusalFor(choice)` says
  whether the backend choice refuses, and
  pure `ConversationOpening.open(trigger, OpeningInputs)` says how a conversation it let through opens, as an
  `Opening`: `OpeningInputs` is the plain-value snapshot the engine gathers (backend choice, model, Chosen speaker,
  Listening tone setting, `now`, location override + IP-geo, on-screen turns, tool declarations + external prompt
  lines); `Opening` is the transcript seed (fresh / resume with turns shown + replayed, within `RESUME_MAX_CHARS`),
  the `BackendConfig` with the finished session prompt (assembled here via pure `SessionPrompt`: built-in
  `SystemPrompt` + enabled external-provider bullets from the same `ExternalToolSpec.parse` pass as the tool
  declarations + `SystemContext` time/location — override > IP-geo > unknown — + `ResumeContext` on a resume),
  voice query (false only for a chip), Listening tone plays (setting on ∧ voice query) and first text.
  `refusalFor(choice)` is the **only** credential rule (blank credential → the backend's "isn't set up yet"
  notice); the engine asks it before it builds anything, and the idle home's setup nudge asks it too. No Android, no
  I/O, no clock reads — fully unit-tested (`ConversationOpeningTest`).
- `conversation/AssistantEngine.kt` — the impure shell, one conversation from Trigger to end. Every collaborator is
  injected: four role ports (`conversation/EnginePorts.kt`) — `EngineClock` (the one thread every event is posted
  onto, the one monotonic clock, and the two timers), `EngineAudio` (mic, player, Listening tone, hush),
  `ConversationSink` (the `ConversationHub` writes and the orange bar) and `OpeningReads` (settings, location,
  on-screen turns, the online check) — plus the `VoiceBackendFactory` (from `Backends.Choice`) and a `ToolRunner`
  factory. `AssistantEngine.create(context, choice, onEnded)` wires the production adapters
  (`conversation/AndroidEnginePorts.kt`: `MainLooperClock`, `DeviceAudio` over one long-lived `PcmCapture` + `PcmPlayer` +
  `ListeningTonePlayer` + the conversation's `AudioFocusHush`, `HubSink`, `PrefsOpeningReads`); the service only
  calls that factory. `HubSink` owns the `UiVisibility.inForeground` check, so the engine never sees it.
  `start(trigger)` asks `ConversationOpening.refusalFor` first and returns the `Refusal` with nothing built (no tool
  runner, no socket, no Hub write); otherwise it builds the tool runner, gathers `OpeningInputs`, calls
  `ConversationOpening.open`, then connects, opens the mic and starts the player, in that order. All state lives on
  the clock's thread (main looper in production; every callback posts an event → no locks). Backend events arrive on the
  one `onEvent`: `Audio` is enqueued on the player synchronously on the socket thread (latency), then every event is
  posted; on the engine's thread, `onBackendEvent` holds every reaction to it — the `Transcript.Event` (applied first),
  the reducer `Event` (an output transcript is `ModelActivity` only while already SPEAKING), `connectedOk` and the first
  text on Ready, and the disconnect notice.
  Every mic frame goes through the **Mic gate** (below), straight from the capture thread; the capture callback only
  applies its outcome. Tools are the **Tool runner**'s (below): the engine only maps the reducer's tool actions onto
  it. Also: the opening's `firstText` (chip-send) is sent once `Event.Ready` is dispatched (`sendInitialText`); an
  unexpected socket drop (`Failed`/`Closed` while not ended) posts one user-facing `ConversationHub.notice`
  (`postDisconnectNotice`; internal `DisconnectNotice`: offline copy first, then a server message as is, else
  "Connection lost" / "Couldn't reach Jarvis" by `connectedOk` — raw socket text is only logged). The **no-speech
  grace** (internal `NoSpeechGrace`) defers the no-speech end while the mic still hears local speech, up to
  `MAX_GRACES` re-arms of the same timer; a server transcript re-arms it in full and resets the budget.
  `AssistantEngineTest` drives all of this on the test thread with recording fakes (`AssistantEngineFakes.kt`): a
  hand-stepped clock, a backend and a mic that call in synchronously.
  The **Listening tone** plays at the conversation's mic open in `start()` (only there — never on a follow-up
  re-listen) when the opening says so, played async by `ListeningTonePlayer` on `SpeechAudio`'s attributes (one
  track built at prewarm and replayed — `audio/PrebuiltTrack`); the setting is `AppPrefs.listeningToneEnabled`
  (default on), read once in the snapshot; the tone's start/failure callbacks feed the Mic gate.
  The **Hush** (ADR 0001) quiets other apps' audio for the conversation: `start()` calls `EngineAudio.startHush`
  after the refusal check, before the mic opens and the tone plays (every trigger, resume included; a refused
  trigger never reaches it); teardown calls `liftHush` right after the mic closes, on every ending (`endNow` and
  `stop()`). The engine never reads whether the hush is held, so a denied or lost hush changes nothing about the
  conversation; no timer, no setting. One `audio/Hush` per conversation, made by `AssistantEngine.create`
  (`audio/AudioFocusHush`: `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` on `SpeechAudio`'s attributes, abandoned on lift;
  any negative focus change marks it no longer held and it never re-requests; keep-paused escalates the held focus
  to a permanent `AUDIOFOCUS_GAIN`, so the apps it paused drop their resume). Not unit-tested: checked on device.
- `service/ConversationHost.kt` — the **service's own rules**, standby ↔ at most one conversation: a trigger while
  one is live starts nothing; a refused trigger posts its notice and re-arms wake (the engine never calls `onEnded`
  on a refusal); the engine's own end, STOP and New conversation all return to standby (idle notification, re-arm
  wake), New conversation clearing the transcript only after the synchronous stop. It holds the engine through the
  narrow `conversation/Conversation` port (`start`/`stop`), built per trigger by the service's factory
  (`Backends.resolve` + `AssistantEngine.create`), and reaches out through `ConversationWake` (`ForegroundWake`),
  `ConversationNotices` (the hub) and `onActiveChanged` (the notification). `ConversationHostTest` drives it with
  recording fakes on one shared log; it never touches the global hub. `AssistantService` keeps only the Android
  wiring: notifications, Intent dispatch, `prewarm()`.
- `conversation/MicGate.kt` — the **Mic gate**, the whole mic frame path: built once per conversation at mic open
  (voice query? tone plays?), fed listening started/stopped from the engine's thread and tone started/failed from the tone
  player, it turns each frame (+ its capture time — it never reads a clock) into a plain `FrameOutcome`: the ordered
  audio to send, the indicator (none / bar at rest / level → Hub level + orange bar, gated on
  `UiVisibility.inForeground` by `HubSink`), speech heard (→ the no-speech grace's last-local-speech stamp) and log
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
  routing is `MediaRouting` (target selection + per-app play strategy); `MusicLauncher.play` (called by
  `PlayMusicTool` directly) fires it via a
  fallback chain — Spotify's `spotify:search:<query>` VIEW deep link (device-verified to start the top result
  on the Portal's locked-down Spotify; broad/artist-level on purpose because that Spotify is **Free tier**,
  which can't on-demand-play one exact track but *can* shuffle-play an artist/playlist), else the generic
  targeted `MEDIA_PLAY_FROM_SEARCH` intent, else just launching the app. Installed music apps are discovered
  via `PackageCatalog.musicApps` (runtime: MediaBrowserService ∪ CATEGORY_APP_MUSIC launchers, no hardcoded seed) and the
  default is set in Settings (`AppPrefs.defaultMusicPkg`, self-healing). A named app is matched with the same
  pure `AppMatch` as `open_app`),
  `portal.media_control`/`set_repeat` (repeat one/all/off on the active session, gated on the app advertising
  `ACTION_SET_REPEAT_MODE`)/`now_playing`, `portal.open_app` (launch an installed app by name — `AppLauncher` +
  pure `AppMatch`; app enumeration shared via `PackageCatalog.launchable`), `portal.end_conversation` (the user asked
  to end: makes this turn a **final turn** via `requestFinalTurn`). The **music commands** `play_music`,
  `media_control` and `set_repeat` (not `now_playing`, which expects an answer) are each wrapped in
  `FinalTurnOnSuccess` where `ToolRegistry` builds them: a result with neither `error` nor `searchUnsupported` also
  makes the turn final, whatever else the turn held, and gains an `instruction` (briefly confirm, no follow-up
  question); a failed one leaves the conversation open. Either request counts only if its call settles with its own
  result, not as cancelled (see the Tool runner). Two **hush** decorators get the conversation's `Hush` (passed by
  `AssistantEngine.create` → `ToolRunner.create` → `ToolRegistry`): `PauseUnderHush` wraps `media_control` *inside*
  `FinalTurnOnSuccess`, so pause/stop while the hush is held calls `Hush.keepPaused` instead of the media session (no
  notification access needed) and returns `{"action":"pause"}`, a working music command and so a final turn; every
  other action, a pause with no hush held, or a pause whose keep-paused was denied, passes through. `HushedFlag`
  wraps `now_playing`: while the hush is held and not kept paused, a found track reports `"playing": true` with
  `"hushed": true` (a bare `hushed` beside `"playing": false` still read as paused on a gen2). Both pure, tested
  with a fake `Hush` (`PauseUnderHushTest`, `HushedFlagTest`). Every frame — `setup`, `audio`, `userText`,
  `toolResults` and `decode` — is pure and unit-tested (`GeminiWireFormatTest`). A tapped suggestion chip sends a **text turn**
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
  cancelled batch can't wedge the turn in SPEAKING) — and nothing after `dispose()`. **Final-turn requests** go
  through `FinalTurnRequests`, scoped to the call that made them: granted (posted before the batch's results) only
  when that call settles with its own result, so a call cancelled mid-run by a barge-in ends nothing even if it had
  already asked. Built per conversation by `create(context, deliver, requestFinalTurn)`; JVM-tested with a fake tool set and a hand-stepped executor (`ToolRunnerTest`).
- `conversation/tools/AfterSpeech.kt` — a tiny queue for side effects that must wait until the assistant stops
  talking (muting the media stream, enabling DND — both silence playback, so they'd eat their own spoken
  confirmation). Both controllers queue it through one shared `DeferredToggle` (report the intended state now —
  a pending effect counts as on — cancel on an explicit change, drop on dispose; JVM-tested); the reducer
  emits `Action.FireAfterSpeech` at **turn-end** (`turnComplete && playbackIdle && no tools in flight`, after
  `PcmPlayer`'s hardware-tail drain) and the Tool runner runs the queue then (`turnEnded`). Replaces the old per-controller fixed `Handler` delays (no more
  guessing 2.5 s vs 4 s). A barge-in keeps the turn in SPEAKING, so a queued effect defers to the post-interrupt turn-end.
- `audio/PcmPlayer` (24 kHz native-audio playback), `audio/SpeechAudio`, `audio/Hush` + `audio/AudioFocusHush`,
  `audio/PcmGain` (pure, tested), `audio/PrebuiltTrack` (build-once, replay-per-play track with self-healing rebuild; JVM-tested),
  `util/Http` (shared OkHttp singleton + `lanVoice` trust-all client for
  the local backend's `wss://` socket).


## Layout

`wake/` — `WakeHandoffReceiver` (trigger entry), `ForegroundWake` (gen2 Foreground wake) + its ports
`WakeModel`/`Detector` and their adapters `WakeModelInstaller`/`VoskDetector`, `WakeSetupStatus` (the model
setup the idle home shows) · `service/AssistantBootReceiver` (boot/STANDBY → bring the
resident service up) · `service/AssistantService` (**resident** foreground service: mic-less standby ↔
one conversation, its rules in `ConversationHost`, the start Intent's extras in `TriggerExtras`; `START_STICKY`,
returns to standby — not `stopSelf`; `prewarm()` warms OkHttp +
classes + DNS + the tool-provider snapshot) · `conversation/` (`ConversationState` reducer + `AssistantEngine` + the `MicGate` frame path; `Trigger` +
`ConversationOpening` (the pure opening decision); `ConversationHub` =
session bridge publishing `ConversationSession` + `audioLevel` + `notice`; `Transcript` + `Markdown`,
`ResumeContext`, `RevealProgress`; `tools/` incl. the `ToolRunner` + `ToolSet`/`ToolRegistry` + `AfterSpeech` + `DeferredToggle`,
`TimerScheduler` + the observable `TimerStore`; `backend/` = the `VoiceBackend` seam + the shared `WebSocketVoiceBackend` transport, `WireFormat`, `BackendEvent`, `ToolCallWire`, `Backends`) · `gemini/` (`GeminiBackend`, `GeminiWireFormat`, `GeminiKeyCheck`, model + Speaker catalogs, Speaker preview) · `local/` (`LocalBackend`, `LocalWireFormat`, `LocalToolMapping`, `LocalVoiceHost`) · `audio/` (`PcmPlayer`, `SpeechAudio`, `Hush`, `AudioFocusHush`, `PcmGain`, `PrebuiltTrack`) ·
`system/` (`AppPrefs`, `LocationProvider`, `NetworkStatus`) · `ui/` — `ConversationScreen` (+ `ConversationStatus`/
`ListeningOrb`/`NoticeBanner`; `IdleHome.kt` has the idle home, `AmbientGlow`, clock and suggestion chips,
`TranscriptView.kt` the transcript, `TimerCards.kt` and `ConversationControls.kt` the rest), `SettingsScreen` (its
sections, with `SpeakerSettings.kt`, `ConnectionSettings.kt` and the shared `SettingsComponents.kt`),
`AudioVisualizer`, `theme/` (warm-orange `Theme` + bundled-Inter `Type`), `RecordingOverlay` (the background
bar) + `UiVisibility` (foreground flag, read only for the orange bar) · `MainActivity` (launcher + tap-to-talk + chip-send). **Shared code from `portal-commons`** (the sibling `../portal-commons`): pure-JVM
`com.portal:commons` (`DebugLog`, `PcmLevel`, `PcmCapture`/`PcmDevice`, `PcmCaptureFormat`) +
the Android-library `com.portal:commons-android` (`com.portal.commons.audio.AudioRecordPcmDevice`, the
shared mic shell, plus `WakeMicEngine` / `WakeMicConfig` / `WakeDetector` / `WakeDetectors.vosk()` for the
gen2 foreground detector). Two plugin contracts are **not** shared deps — we mirror their literal strings: the outbound
`ToolContract` is **local to this app** (`conversation/tools/ToolContract`); the inbound wake contract
(`com.portal.wake.action.WAKE` / `…extra.ID`) is **portal-wake's** `WakeContract`, mirrored as literals in
`wake/WakeHandoffReceiver`.

