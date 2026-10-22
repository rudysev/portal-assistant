# Spec: Conversation opening

Status: ready-for-agent

## Problem Statement

How a conversation opens is decided from its trigger: resume or fresh, voice query or not, whether the Listening
tone plays, what text goes first, and whether a conversation can open at all. Today those decisions are spread
across four files and encoded in strings and nulls:
- the trigger travels as a string `source` (`"tap"`, `"chip"`, `"wake:<id>"`) built in four places;
- the service decides resume (`source == SOURCE_TAP`); the engine decides voice query (`initialText == null`);
- the credential check runs in the service, again in the engine, and again in the idle home (with its own copy
  of the rule);
- the engine's `start` reads settings, the clock, location and the on-screen turns as it goes, and assembles the
  session prompt and backend config through three shallow modules (`BackendSessionConfig`,
  `BackendWireDefaults`, `Backends.missingCredentialMessage`).

None of the combined decision is tested. The start guard's test re-implements the service logic locally and tests
two one-line helpers extracted only for it. The location rule (override beats IP-geo beats unknown) has no test.
The engine and service are the two most-changed files in the repo, and this is where they overlap.

## Solution

One pure module, **`ConversationOpening`**, in `conversation/`. It takes a **Trigger** and a plain-value snapshot
(**`OpeningInputs`**) and returns either an **`Opening`** (everything the conversation opens with) or a
**`Refusal`** (no credential, with its notice). The engine gathers the inputs, calls it, and runs the I/O; the
service routes triggers and reacts to a refusal. Users see no change.

Domain terms (Trigger, Wake word, Mic tap, Suggestion chip, Voice query, Resume, Opening, Credential,
Conversation, Standby) are in `GLOSSARY.md`. The service/engine split is a design rule in `CLAUDE.md`.

## User Stories

1. As a user, I want "hey jarvis" to always start a fresh conversation, as today, even with a transcript on screen.
2. As a user, I want a mic tap to resume the conversation still on screen, so that Jarvis keeps its context.
3. As a user, I want a mic tap with nothing on screen to start fresh.
4. As a user, I want a tapped suggestion chip to start fresh and send its text as the first turn.
5. As a user with the Listening tone on, I want it to play for the wake word and a mic tap, never for a chip.
6. As a user with no credential set, I want a plain "isn't set up yet" notice for my backend and no connecting flash.
7. As a gen2 user with no credential set, I want foreground "hey jarvis" detection to keep working after the notice.
8. As a user, I want the idle home's setup nudge to appear exactly when a conversation would be refused.
9. As a user who resumes a long chat, I want only the recent turns (within the budget) replayed to the model.
10. As a user with a location override, I want Jarvis told that location instead of the IP-geo guess.
11. As a user, I want Settings to describe accurately which changes apply on the next conversation.
12. As a maintainer, I want every opening decision in one module with one test file, so that a new trigger or setting is a local change.
13. As a maintainer, I want triggers typed end to end except for the one Intent hop, so that no string comparison decides behaviour.
14. As a maintainer, I want an Intent that doesn't decode to a trigger to start nothing (and be logged), so that a bug can't silently open the mic.
15. As a maintainer, I want the engine to do only I/O after the opening is decided, and never reach back into settings.
16. As a maintainer, I want the service never to decide how a conversation opens, so that the service/engine rule holds without exceptions.

## Implementation Decisions

**Trigger** (`conversation/`): a closed type — `WakeWord(wakeId)`, `MicTap`, `SuggestionChip(text)`. The wake id is
kept for the debug log (`trigger=wake:jarvis`).

**`ConversationOpening`** — pure, in-process, no Android, no I/O, no clock reads.
- `decide(trigger, inputs): Opening | Refusal`. Calls `refusalFor` first.
- `refusalFor(choice): Refusal?` — the only credential rule. `Refusal` carries the backend-specific notice
  (today's `CredentialMessages` text). Also called by `IdleHome`, which keeps its own nudge wording.
- `RESUME_MAX_CHARS` (4,000) moves here as a constant.

**`OpeningInputs`** — plain values gathered by the engine in `start`:
- `backendChoice: Backends.Choice` (only `kind` and `credential` are read; `factory` is never touched);
- `modelId`, `speakerId?` (null = Server default), `listeningToneEnabled` — from `AppPrefs`;
- `now: ZonedDateTime`;
- `locationOverride: String?`, `deviceLocation: DeviceLocation?` — from `LocationProvider`;
- `onScreenTurns: List<Turn>` — from `ConversationHub.session`;
- `toolDeclarations`, `externalPromptLines` — from the engine's `ToolRegistry`, same snapshot as today.

**`Opening`** — plain values:
- transcript seed: resume or fresh, turns shown, turns replayed (the `ResumeContext` cut). Resume only for a mic
  tap with on-screen turns;
- the finished session prompt (`SessionPrompt` + the time and location lines; override > IP-geo > unknown);
- the `BackendConfig` (credential, model, Chosen speaker, prompt, declarations);
- voice query (false only for a suggestion chip);
- Listening tone plays (setting on ∧ voice query);
- first text (the chip's text, or none).

**Engine** — `start(trigger)` gathers `OpeningInputs`, calls `decide`, and returns started or the `Refusal`. It keeps
the `VoiceBackendFactory` (and its stall timeout), builds the `MicGate` from the two flags, applies
`ConversationHub.startFresh`/`startResume`, and runs the I/O as today. Its own credential check, its
`deviceContextLines`, `listeningTonePlaysAtMicOpen`, and its `LocationProvider.refreshIfStale` call go.

**Service**
- `start(context, trigger)` replaces `start(context, source, initialText)`. Encoding and decoding the Intent
  extras sit side by side in the companion; nothing else knows the extras. An undecodable Intent starts nothing
  and is logged.
- `startConversation(trigger)` resolves the backend, calls `LocationProvider.refreshIfStale` (moved from the
  engine), builds the engine, and matches its result: on a `Refusal`, post its notice and re-arm detection if in
  the foreground (as today); on started, keep the engine.
- The gen2 detector calls `startConversation(Trigger.WakeWord(id))` directly instead of sending an Intent to itself.
- Callers: `MainActivity` (three call sites) and `WakeHandoffReceiver` pass a `Trigger`.

**Deleted**
- `BackendSessionConfig`;
- `BackendWireDefaults` and `Choice.defaultWire` — first confirm `BackendConfig` already defaults its
  `gemini`/`local` wire options to the same values `forKind` returns;
- `Backends.missingCredentialMessage` (the `CredentialMessages` text moves behind `refusalFor` or stays as its
  source — implementer's choice, one place either way);
- `AssistantService.canOpenConversation`, `retainIfStarted`, `SOURCE_TAP`, `SOURCE_CHIP`;
- the `conversationStart` parameter of `ListeningTone.playsAtMicOpen` (always true; a follow-up never reaches the
  opening). If `playsAtMicOpen` is left with no caller outside `ConversationOpening`, fold it in.

**Settings rule.** Settings that shape the opening (backend, model, Chosen speaker, Listening tone, location) are
read once per conversation; a tool reads its own setting when it runs (the default music app at `play_music`
time stays live). Reword Settings' "Changes take effect on your next conversation" (`SettingsScreen.kt:170`) so
it doesn't claim the music app.

## Testing Decisions

- **One seam: `ConversationOpening`'s interface.** No engine- or service-level test harness.
- **New `ConversationOpeningTest`**, through `decide` / `refusalFor` only, asserting on the returned values:
  - every Trigger × turns on screen or not → resume/fresh, turns shown, turns replayed (budget crossed with long
    turns), voice query, first text;
  - Listening tone on/off × Trigger → plays only for a voice query with the setting on;
  - missing credential × each backend (including a blank Local host) → `Refusal` with that backend's notice;
    present credential → no refusal;
  - location: override beats IP-geo beats unknown, as seen in the prompt;
  - `BackendConfig` carries credential, model, Chosen speaker (or none for Server default), prompt, declarations.
- **Deleted:** `BackendSessionConfigTest`; `AssistantServiceStartGuardTest`; in `BackendsTest` the
  `missingCredentialMessage` and `BackendWireDefaults applyTo` tests (`resolveChoice` and DNS tests stay); in
  `ListeningToneGatingTest` the four `playsAtMicOpen` tests (the three `previewsOnSwitch` tests stay).
- **Kept as internal tests:** `SessionPromptTest`, `ResumeContextTest`, `SystemContextTest` — exact wording and
  truncation edges. The new test asserts what was decided; these assert how text is formatted.
- **Prior art:** `MicGateTest`, `ConversationStateTest`.
- **The full unit-test suite and the debug build must pass.**

## Out of Scope

- Freezing tool-time settings (the default music app) into the snapshot.
- Changing any behaviour: resume rules, the resume budget, prompt wording, notice wording, tone rules.
- The other architecture-review candidates: Turn log, Tool runner, tool definitions, the shared socket session,
  foreground wake setup, the no-speech timer.
- The adapters' own credential checks in `LiveClient.connect` / `LocalBackend.connect` — they stay (the Speaker
  preview builds a `LiveClient` without this module).
- `AssistantService.running` (written, never read) — note it, but removing it is unrelated cleanup.
