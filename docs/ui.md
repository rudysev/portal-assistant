# Chat UI (Phase 3) — Portal-Assistant

**Phase 3 — chat UI.** ✅ Built. A foreground Compose chat (`ui/ConversationScreen` + `theme/`, with the
  bundled **Inter** typeface in `theme/Type` so the brand doesn't depend on the device font) in our
  warm-orange **"Jarvis"** identity (the Model's name — `GeminiModel.Model.name`, e.g. "Gemini 3.8" — as a
  subtitle). It renders three center states by
  `(turns, phase)`:
  - **Idle home:** an ambient clock/date, the greeting, and a short row of **static suggestion chips**
    ("What's the weather?", "Set a 10-minute timer") — a calm, clock-first glance, not a command menu.
    Enabled external providers (Kasa, calendar, …) are described in the **session system prompt** at connect
    time, not as extra chips. A slow `AmbientGlow` breathes behind it. **Tapping a chip sends its text as the
    first turn** (not just opening the mic): `VoiceBackend.sendText` → `GeminiWireFormat.userText`, a `realtimeInput.text` turn — the
    `Trigger.SuggestionChip`'s text becomes the opening's `firstText`. On a model without Google Search with no
    Search key (pure `ui/showsSearchKeyNudge`, Gemini only), a quiet accent "Add a search key …" line under the
    chips opens Settings.
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
  (`ui/SettingsScreen`): model picker (the `gemini/GeminiModel` catalog, listed by name), voice picker (Gemini
  only — the Chosen speaker from the `gemini/GeminiSpeaker` catalog, or Server default; `AppPrefs.speakerId` → `BackendConfig.speaker` →
  `GeminiWireFormat.setup`'s `speechConfig`, omitted for Server default; tapping a dialog row also **previews**
  it — `gemini/SpeakerPreview` (pure, unit-tested reducer + the preview's session config, built by `GeminiBackend.Factory` like a conversation's) driven by the thin
  `gemini/SpeakerPreviewPlayer` shell: a short Live session with the selected model + candidate Speaker, one
  "introduce yourself" text turn, its own `PcmPlayer`; one preview at a time, stopped when the dialog closes,
  paused while a conversation is live — independent of the engine; a preview that played to completion is
  kept in memory for that dialog open, so re-tapping its row replays it with no session), API key and **Search
  key** sections (Gemini only; one shared Save & verify form, `ui/ConnectionSettings.UserKeySection`, checked by
  `GeminiKeyCheck` / `SearchKeyCheck` → `util/KeyVerdict`, "Save anyway" after a network error), a **Sounds** section
  right after the backend/API-key sections, shown for both backends (the Listening tone switch —
  `AppPrefs.listeningToneEnabled`, default on; switching it on previews the tone via `ListeningTonePlayer`, off
  is silent, and no preview plays while a conversation is live — pure `ListeningTone.previewsOnSwitch`), location
  override (with the linked "Weather data by Open-Meteo.com" credit beneath it; the link does nothing if no browser
  can open it), default-music-app picker, external-tool toggles (shared `SettingsSwitchRow`).

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

