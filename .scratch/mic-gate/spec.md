# Spec: Mic gate

Status: ready-for-agent

## Problem Statement

Every mic frame of a conversation passes through a chain of rules before anything reaches the voice backend:
- the Listening tone suppression window, which drops frames captured before or while the tone plays, plus its
  echo tail;
- the connect buffer, which keeps the opening words spoken while the socket connects and flushes them at the
  first listening frame;
- the half-duplex mute while Jarvis speaks;
- the mic gain;
- the local-speech signal that feeds the no-speech grace;
- the level that drives the in-app visualizer and the orange bar.

Each rule is unit-tested on its own, but the code that runs them in order is not tested. That code sits in the
conversation engine's capture-thread callback, spread across about a hundred lines and seven fields. The state
it reads is written from three threads: the main thread at the conversation's start, the orchestration thread
when listening starts and stops, and the tone player's thread when the tone starts or fails.

This is the most-changed code in the app: the last four commits went into it, and one of them fixed an ordering
bug (pre-tone audio leaking out, and the orange bar showing late). For whoever works on it, every change means
re-reading the whole callback and checking the result on the device, because nothing pins down how the rules
combine. Example: the Listening tone's 640 ms suppression window is longer than the ~440 ms connect, so with the
tone on, the connect buffer usually holds little or nothing. The behaviour is intended ("nothing from before the
cue is sent"), but no test records it. A later change to either rule could break it silently.

## Solution

Move the whole frame path into one deep module, the **Mic gate**. For each captured frame, it decides:
- which audio is sent to the voice backend, and in what order;
- what the listening indicators show;
- whether the user was heard speaking;
- what to write to the debug log.

It owns its state, including the state written from other threads, and it performs no I/O. The engine keeps
only the I/O: it hands each frame to the gate and applies the result. Users see no change. This is a pure
refactor: every behaviour described in the Listening tone spec and the connect-buffer notes stays as it is.
JVM tests that feed whole sequences of frames through the gate's one interface now pin down that behaviour,
including how the rules interact.

## User Stories

1. As a user, I want my first words after "hey jarvis" to still reach Jarvis after this change, so that the refactor costs me nothing.
2. As a user who taps the mic and starts talking while "Connecting…" shows, I want my opening words kept exactly as they are today, so that the connect buffer still does its job.
3. As a user with the Listening tone on, I want no audio captured before the tone (or during it and its echo tail) to be sent, so that the tone stays an honest "recording starts now" cue.
4. As a user with the Listening tone on, I want the words I speak right after the tone and its echo to be sent, so that I can talk as soon as I hear it.
5. As a user with the Listening tone off, I want the conversation to behave exactly as it does without a tone today, so that the setting has no hidden side effects.
6. As a user whose tone failed to play, I want my audio sent as if the tone were off, so that a sound glitch never makes Jarvis deaf.
7. As a user who tapped a suggestion chip, I want no connect-window audio buffered or flushed, so that the text query isn't followed by stray room noise.
8. As a user, I want the orange bar to appear as soon as Jarvis starts listening, even while frames are still being dropped for the tone, so that the bar is never late.
9. As a user, I want the bar shown at rest (no level) while tone frames are dropped, so that the tone doesn't look like me speaking.
10. As a user, I want the in-app visualizer to react to my voice once listening, so that I can see Jarvis hears me.
11. As a user, I want the visualizer and bar to ignore the tone, so that the tone never reads as my voice.
12. As a user, I want the mic muted while Jarvis speaks, so that Jarvis never hears and answers itself.
13. As a user speaking from across the room, I want the outgoing audio amplified as it is today, so that Jarvis still understands me at about 4 m.
14. As a user who starts a follow-up late in the listening window, I want the no-speech grace to still notice my local speech, so that the conversation doesn't end on me mid-sentence.
15. As a user, I want the tone never counted as local speech, so that the tone can't keep the conversation open by itself.
16. As a user on a slow connection, I want the connect buffer to stay bounded and keep the most recent audio, so that a hung connect can't exhaust memory or flush stale audio.
17. As a user whose model answer begins before my first listening frame arrives, I want buffering to stop when listening stops, so that Jarvis's own speech is never buffered and sent back.
18. As a user, I want the buffered connect audio sent before the live frame, in the order it was captured, so that Jarvis hears my sentence in order.
19. As a maintainer, I want every frame rule in one module behind one interface, so that I can understand the frame path without bouncing between the engine, the tone module and the gain module.
20. As a maintainer, I want how the rules combine to be tested, so that ordering bugs like the pre-tone leak are caught on the JVM, not on the device.
21. As a maintainer, I want the tone × connect-buffer interaction pinned down by a test, so that changing either rule shows its effect on the other.
22. As a maintainer, I want the state written by other threads owned by the gate, so that there is one place to reason about races.
23. As a maintainer, I want to reproduce races, such as a model turn starting before the first listening frame, by calling the gate's inputs in a test, so that I don't need a device to check them.
24. As a maintainer, I want the gate never to read a clock itself, so that tests can place frames exactly at the edges of the suppression window.
25. As a maintainer, I want to keep the debug-log lines I rely on for device runs (the tone drop report, the connect-buffer flush), so that diagnosis on the device still works.
26. As a maintainer, I want the tone drop report written only once per conversation, so that the debug log stays readable.
27. As a maintainer, I want the mic gain to stay a single, device-tuned constant in one place, so that retuning it remains a one-line change.
28. As a maintainer, I want the engine to shrink to plain I/O on the capture thread, so that what is left in the engine is obviously correct by reading it.
29. As an agent picking up future frame-path work (for example, a "goodbye" fast-exit or a new suppression rule), I want one module and one test file to change, so that the work stays local.
30. As a maintainer, I want the old per-rule suppression tests replaced by tests through the gate, not duplicated alongside them, so that tests describe behaviour and survive refactors inside the gate.

## Implementation Decisions

**One new module: the Mic gate.** It lives with the conversation code. It is in-process: no Android
dependencies, no I/O, no clock reads. It is a class that owns its mutable state (not an immutable-state
reducer), because that state includes a bounded queue of byte frames confined to the capture thread.

**Everything below sits behind its interface:**
- the Listening tone suppression window, plus the bookkeeping for the tone drop report;
- the connect buffer: it buffers while connecting for a voice query only, is capped and drops the oldest
  frame, and flushes in capture order at the first forwarded frame;
- the half-duplex mute: frames are dropped while not listening and not connecting;
- the mic gain;
- the local-speech signal, measured on the gained frame;
- the raw level for the visualizer and the bar.

**Interface:**
- **Construction, once per conversation at mic open**, before the capture thread starts. It takes whether this
  is a voice query (which turns connect buffering on) and whether the Listening tone will play. The tone
  starting as "pending" replaces the engine setting its own tone state.
- **Inputs from other threads**, each a small method, held internally in volatile fields:
  - *listening started*: from the orchestration thread on the start-forwarding action;
  - *listening stopped*: from the orchestration thread on the stop-forwarding action and at teardown. It also
    ends connect buffering, as the engine does today;
  - *tone started at a time*: from the tone player's thread;
  - *tone failed*: from the tone player's thread.
- **Per frame, from the capture thread:** it takes the frame's PCM, its length, and the time the frame finished
  capturing (the engine passes the monotonic elapsed-realtime clock, as today), and returns a frame outcome.

**The frame outcome is plain data:**
- **Audio to send**: an ordered list, possibly empty. On the first forwarded frame it holds the flushed connect
  buffer followed by the live frame. It is empty when the frame is dropped, buffered or muted.
- **Indicator**, one of:
  - *none*: leave the indicators alone (connecting, or muted while Jarvis speaks);
  - *bar at rest*: listening, but this frame was dropped for the tone;
  - *level*: listening, carrying the raw level, which drives both the in-app visualizer and the bar's level.
- **Speech heard**: whether the gained frame cleared the no-speech grace's voice-activity level.
- **Log lines**: zero or more. These are the one-time tone drop report and the connect-buffer flush line.

**The engine applies the outcome on the capture thread:**
- it sends each audio chunk to the voice backend;
- it maps the indicator to the Hub level and to the orange bar, gated on whether the app is in the foreground
  as today. The gate never reads that flag;
- on speech heard, it stamps the last-local-speech time with the same wall clock as today, so the no-speech
  grace is unchanged;
- it writes the log lines.

**What moves out of the engine:**
- the per-frame callback's body;
- the tone-playback field and the five tone log counters;
- the connect buffer, its flag, and its buffer and flush helpers;
- the mic gain helper;
- the mic gain and connect-buffer cap constants, which move into the gate unchanged in value.

The engine keeps its mic-open sequence. It decides whether the tone plays, builds the gate, starts the mic,
then starts the tone, whose callbacks now feed the gate.

**The Listening tone module loses its frame rule.**
- Moving into the gate: the playback state (none / pending / started-at) and the frame-suppression predicate.
  The gate is now their only user.
- Staying with the tone: the gating rules, the echo tail constant and the suppression window length (properties
  of the tone, with the device measurement in their docs), and the synthesis.

**Behaviour is preserved exactly.** Every current rule and edge stays as it is, including:
- whole-frame overlap at the window edges;
- "pending drops everything";
- a failed tone sending frames again;
- the bar at rest while frames are dropped;
- buffering stopping when listening stops;
- the cap of 50 that drops the oldest frame.

Changing how the tone and the connect buffer interact is not part of this spec.

**Docs.**
- Update the project onboarding notes. The mic gain's "single seam" is now the gate, and the frame path is
  described as the Mic gate.
- Add a **Connect buffer** entry to the glossary, since the term is used throughout but not defined.
- "Mic gate" names an implementation module, not domain language, so it stays out of the glossary.

## Testing Decisions

- **One seam: the Mic gate's interface.** No new ports or adapters, and no engine-level test. After this
  change the engine is plain I/O, and the risk lives in the gate.
- **A good test** builds a gate the way a conversation would. It drives a realistic sequence through the public
  interface: frames at chosen times, interleaved with listening started/stopped and tone started/failed. It
  asserts only on the outcomes: which audio is sent and in what order, the indicator, speech heard, and the log
  lines. It never inspects internal fields, so it survives refactors inside the gate.
- **Frames are synthetic PCM** with known amplitudes: silence, quiet, and loud enough to clear the
  voice-activity level after gain. Tests can then assert the gain was applied and speech was heard without
  depending on real audio.
- **Behaviours to cover**:
  - voice query with no tone: connect frames buffered, then flushed in order before the live frame on the first
    listening frame;
  - chip (not a voice query): nothing buffered, and live frames sent once listening;
  - tone pending: everything dropped, bar at rest once listening;
  - tone started: the window edges, including a frame at tone start, a frame straddling the end and the frame
    just after it;
  - tone failed: frames sent again;
  - **the tone × connect-buffer interaction**: frames inside the window are never buffered, and only frames
    after the window but before listening are flushed;
  - a model turn wins the race: listening stops before the first listening frame, buffering ends, and nothing
    buffered is ever sent;
  - muted while Jarvis speaks: nothing sent, indicator none;
  - the connect-buffer cap drops the oldest frame;
  - the tone drop report is written exactly once and the flush line once;
  - speech heard is false for the tone and for silence, and true for loud speech once listening.
- **Replace, don't layer.** The suppression-window edge tests move into the Mic gate tests, expressed as frame
  sequences, and the standalone suppression test file is deleted. The tone's gating and synthesis tests stay
  as they are.
- **Prior art:** the conversation reducer's tests (sequences of events through one pure interface, asserting
  outcomes), the suppression-window tests being replaced, and the no-speech grace tests.
- **The full unit-test suite and the debug build must pass.**
- **Device check:** with the tone on and off, wake and mic-tap conversations behave as before. The debug log
  shows the same tone drop report and connect-buffer flush lines.

## Out of Scope

- Changing what any rule does. That includes the tone × connect-buffer interaction, the echo tail length, the
  mic gain value and the buffer cap.
- The no-speech timer and its grace counter, which stay in the engine. The gate only reports speech heard.
- Moving the no-speech grace from the wall clock to the monotonic clock.
- The tool-batch executor, transcript and reveal bookkeeping, and the other untested parts of the engine.
- An engine-level test harness, or injecting the mic, voice backend or overlay into the engine.
- The other candidates from the architecture review: the tool catalog, backend events, session setup, wake
  detection and the preview arbiter.
- The mic capture itself (format, source, the shared mic contract with portal-wake) and gen2 wake detection.

## Further Notes

- This comes from the 2026-10-20 architecture review (candidate 01, "Strong", the top recommendation). Its
  open design questions were settled on the review's recommended answers:
  - all seven frame rules go inside;
  - a class that owns its state, not a reducer;
  - the gate owns the inputs from other threads;
  - time is a parameter;
  - the name is Mic gate;
  - behaviour is preserved.
- No ADRs exist yet, so none constrain this work.
- Thread model to keep:
  - only the capture thread calls the per-frame method and touches the buffer and the log counters;
  - the inputs from other threads are the only volatile state;
  - construction happens before the capture thread starts, so its initial values are visible to that thread
    without further synchronisation, as today.
