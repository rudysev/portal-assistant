# 01: Decide the conversation opening in one module

**What to build:** A typed **Trigger** (wake word, mic tap, suggestion chip) and one pure `ConversationOpening` module
that turns a Trigger plus a plain-value snapshot into either the conversation's **Opening** or a **Refusal** (no
credential). The engine gathers the snapshot and runs the I/O; the service routes triggers and reacts to a
refusal. Users see no change.

Spec: `.scratch/conversation-opening/spec.md`.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

Land it as **two commits**, so there is a tested checkpoint for review and for bisecting:
1. the typed Trigger, end to end;
2. `ConversationOpening`, and the engine and service rewired onto it.

### Commit 1: typed Trigger

- [x] `Trigger` in `conversation/`: `WakeWord(wakeId)`, `MicTap`, `SuggestionChip(text)`.
- [x] `AssistantService.start(context, trigger)` replaces `start(context, source, initialText)`. Intent encode and
  decode sit side by side in the service's companion; nothing else knows the extras.
- [x] An Intent that doesn't decode to a Trigger starts nothing and logs one line.
- [x] `MainActivity` (three call sites) and `WakeHandoffReceiver` pass a `Trigger`.
- [x] The gen2 foreground detector calls `startConversation(Trigger.WakeWord(id))` directly instead of sending an
  Intent to its own service.
- [x] `SOURCE_TAP` and `SOURCE_CHIP` are deleted. In this commit the service may still derive resume and first text
  from the Trigger itself; commit 2 moves that into the opening.
- [x] The debug log still shows the trigger (e.g. `trigger=wake:jarvis`).
- [x] The full unit-test suite and the debug build pass.

### Commit 2: ConversationOpening

- [x] `ConversationOpening.decide(trigger, inputs)` returns an `Opening` or a `Refusal`; `refusalFor(choice)` is the
  only credential rule and `decide` calls it first. Pure: no Android, no I/O, no clock reads.
- [x] `OpeningInputs` and `Opening` carry exactly the fields listed in the spec. `RESUME_MAX_CHARS` moves into the
  module.
- [x] Engine `start(trigger)` gathers `OpeningInputs`, calls `decide`, returns started or the `Refusal`; builds the
  `MicGate` from the two flags; applies `startFresh`/`startResume`; never reads `AppPrefs` after the snapshot.
  Its credential check, `deviceContextLines`, `listeningTonePlaysAtMicOpen` and `refreshIfStale` call are gone.
- [x] Service `startConversation` calls `LocationProvider.refreshIfStale`, builds the engine, and matches the result:
  a `Refusal` posts its notice and re-arms detection when in the foreground; started keeps the engine.
- [x] `IdleHome` calls `refusalFor` for the setup nudge and keeps its own wording.
- [x] Deleted: `BackendSessionConfig`; `BackendWireDefaults` + `Choice.defaultWire` (after confirming
  `BackendConfig`'s defaults match); `Backends.missingCredentialMessage`; `canOpenConversation`;
  `retainIfStarted`; the `conversationStart` parameter of `ListeningTone.playsAtMicOpen`.
- [x] Settings' "Changes take effect on your next conversation" is reworded so it doesn't claim the default music app.
- [x] New `ConversationOpeningTest` covers every case in the spec's Testing Decisions.
- [x] Deleted tests: `BackendSessionConfigTest`, `AssistantServiceStartGuardTest`, the `missingCredentialMessage` and
  `BackendWireDefaults applyTo` tests in `BackendsTest`, the four `playsAtMicOpen` tests in
  `ListeningToneGatingTest`.
- [x] `CLAUDE.md`: drop the "(Today the service still derives resume…)" note from the service/engine rule, and
  describe `ConversationOpening` in the architecture section (session prompt assembly now lives there).
- [x] The full unit-test suite and the debug build pass.

### Device check (gen2, after commit 2)

- [ ] "hey jarvis" with a transcript on screen starts fresh; the Listening tone plays (setting on).
- [ ] Mic tap with a transcript on screen resumes (debug log shows the resume line with replayed turns); with
  nothing on screen, starts fresh.
- [ ] Suggestion chip starts fresh, its text appears as the first turn, no Listening tone.
- [ ] With no credential (Gemini key cleared, then Local host cleared): the backend's notice shows, no connecting
  flash, the idle home shows its nudge, and "hey jarvis" detection still works afterwards.
- [ ] A location override set in Settings is what Jarvis answers with for "where am I?".

## Comments
