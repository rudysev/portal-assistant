# CLAUDE.md — Portal-Assistant

**Jarvis**: voice assistant for Meta Portal, `com.portal.assistant`. gen1 = API 28, gen2 = API 29; minSdk 28 /
targetSdk 29 / compileSdk 36; no GMS. Gemini Live (`gemini-2.5-flash-native-audio-latest`, `googleSearch` +
function calling) or an opt-in LAN Pipecat host, behind the `VoiceBackend` seam. Multi-turn voice + Compose chat UI
built, incl. a **final turn** (`portal.end_conversation`: the user asks to end → ends at turn end). Deferred:
better ASR, weather glance. Human docs: `README.md`.

## Read on demand

- `docs/architecture.md` — before changing `conversation/`, `gemini/`, `local/`, `audio/`, tools or the service:
  per-component contracts, built-in tool list, `play_music` routing, full layout.
- `docs/ui.md` — before changing `ui/`: idle/connecting/transcript states, session entry model, paced word
  reveal, Settings (voice preview, Sounds), reduce-motion handling.
- `docs/tooling.md` — when Lint/detekt/ktlint/hooks/CI fail, or bumping a tool version.
- `docs/history.md` — phase history, provenance, known-limitation detail, API key provisioning flow.
- `docs/agents/` — issue tracker (`.scratch/<feature>/issues/`), triage labels (`Status:` line), domain docs
  (`GLOSSARY.md`, `docs/adr/`).

## Commands

```bash
git submodule update --init --recursive   # from the portal-apps workspace (needs ../portal-commons)
./gradlew check           # unit tests + Lint + detekt + ktlint; pre-push runs this
./gradlew formatKotlin    # fix ktlint style
./gradlew assembleDebug   # JDK 21 + Android SDK
./gradlew buildHealth     # after any dependency change (CI job, not in check)
./setup.sh                # install, grant mic + overlay, prompt for API key; --key-only <KEY> non-interactive
npx -y @meta-quest/hzdb adb shell "cat /sdcard/Android/data/com.portal.assistant/files/debug.txt"
```

A bare "warnings found and -Werror specified" is a Kotlin compiler warning: rerun with `--info` to see it.

## Architecture map

Pure reducer + thin I/O shell. Logic lives in pure, JVM-unit-tested classes; Android/I/O shells stay thin.

| Piece | Contract |
|---|---|
| `service/AssistantService` | Resident `START_STICKY` foreground host: `prewarm()`, notification, Intent dispatch (`TriggerExtras`). Its rules live in pure `ConversationHost`: standby ↔ one conversation, posts refusals, re-arms wake. Never decides how a conversation opens/runs. |
| `conversation/AssistantEngine` | One conversation, Trigger → end, discarded after. Collaborators injected via four ports (`EnginePorts.kt`: clock, audio, sink, opening reads; adapters in `AndroidEnginePorts.kt`, wired by `create`). All state on **one thread** (`EngineClock`); every callback posts an event (no locks). Every `BackendEvent` reaction lives in `onBackendEvent`. Never touches wake, notifications, standby. |
| `ConversationState.reduce(state, event, multiTurn)` | CONNECTING → LISTENING → SPEAKING → ENDED. Turn end = `turnComplete && playbackIdle && no tools in flight`. |
| `ConversationOpening.refusalFor` + `open(Trigger, OpeningInputs)` | `refusalFor` is the **only** credential rule, asked before the engine builds anything; `open` → `Opening`. Pure (no clock/I/O). Mic tap resumes; chip / "+" / wake start fresh. |
| `MicGate` | Entire mic frame path: tone suppression → half-duplex mute → connect buffer (50 frames) → `MIC_GAIN` → speech-heard. Pure, never reads a clock. |
| `backend/VoiceBackend` + `Backends.kt` | Model-neutral seam: one `Listener.onEvent(BackendEvent)` (incl. `Failed`/`Closed`); the engine's `onBackendEvent` says what each means to the conversation. `Backends.kt` is the only `conversation/` file importing `gemini`/`local`. `WebSocketVoiceBackend` is the only socket owner (setup gate, `ModelGenerating` before a turn's first `Audio`); each backend supplies a pure `WireFormat`. |
| `tools/ToolRunner` | N calls in → one ordered list of N results out, each settled once (cancel → `{"error":"cancelled"}`), nothing after `dispose()`. Routing: built-ins, then external providers. |
| `tools/AfterSpeech` + `DeferredToggle` | Effects that silence playback (mute, DND) fire at turn end, not on a delay. |
| `ConversationHub` | Session/`audioLevel`/`notice` bridge to UI. Orange bar gated by `ui/UiVisibility.inForeground`. |

## Hard rules

- **Two client timers only**: no-speech and stall. Any new delay needs a justification, not a guess.
- **Stall is arm-on-drain**, decided only by `stallDecision` (`StallDecision.kt`): arms when nothing plays and no
  tools run; cancels while audio flows. Audio is queued ahead of realtime, so keep the stall off transcript/
  generating events and off time-since-last-received-chunk — both cut long answers mid-sentence.
- **Wake by device**: gen1 = portal-wake hands off via `ACTION_WAKE`. gen2 = our own Vosk detector
  (`wake/ForegroundWake`), **foreground-only** (Android 10 silences a background mic). Vosk model downloads at
  runtime, not bundled.
- **No "done" signal**: portal-wake reclaims by detecting we stopped recording. Hold the mic only while live; keep
  `AudioRecord` open across turns (muted while SPEAKING).
- **Background trigger = orange bar only** (`RecordingOverlay`); the Activity opens only when the user opens it.
- **Orange bar only while LISTENING.**
- **Mic**: `VOICE_RECOGNITION`, 16 kHz mono, no effects. Audio contract: 16 kHz in / 24 kHz out PCM.
- **Gain only the conversation stream** (`PcmGain`), never wake capture (processing there could hide a *silenced* capture loss).
- **Settings**: opening settings (backend, model, speaker, tone, location) are read once per conversation; a tool
  reads its own setting when it runs.
- **Contracts**: inbound wake strings (`com.portal.wake.action.WAKE` / `…extra.ID`) are mirrored as literals in
  `WakeHandoffReceiver`; `ToolContract` is local to this app. Neither is a shared dep.
- **API key is BYOD**: `BuildConfig.GEMINI_API_KEY` stays blank.
- **Lint/detekt/ktlint**: warnings are errors, no baselines. Suppress inline only, with a reason comment. 120-col
  lines. TODO/FIXME/STOPSHIP comments fail detekt.
- **Bump together**: detekt plugin ↔ compose-rules ↔ pre-commit `DETEKT_VERSION`; ktlint CLI 1.8.0 ↔ kotlinter
  5.7.0. Keep `config/detekt/detekt.yml`, `.githooks/pre-commit`, `.shellcheckrc` identical across portal-commons,
  portal-wake, portal-assistant.
- **CI job names** `build`, `shellcheck`, `build-health` are required by branch protection: keep them.

## Known limitations (don't "fix" by misdiagnosis)

- A one-breath wake ("hey jarvis what's the weather") never starts a conversation: portal-wake gates on the
  discrete phrase. Not server VAD, not the connect buffer.
- Transcript markdown is stripped to plain text on purpose (word-sliceable for the paced reveal).
- Two persistent notifications (ours + portal-wake) are the accepted cost of staying warm.
- Tap-to-talk churns with a running portal-wake (both `VOICE_RECOGNITION`).
- Room distance (~4 m) relies on `MIC_GAIN`: handset mic only.

## Grilling (`/grill-with-docs`)

One question at a time, then stop for the answer. Implement only with the user's explicit permission.
