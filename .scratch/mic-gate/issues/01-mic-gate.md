# 01: Move the mic frame path into the Mic gate

**What to build:** Every mic frame of a conversation goes through one Mic gate. The gate decides:
- what is sent to the voice backend, and in what order;
- what the listening indicators show;
- whether the user was heard speaking;
- what to write to the debug log.

The engine's capture callback only applies that result. Users see no change: the connect buffer, the Listening tone
suppression window, the half-duplex mute, the mic gain, the level, the orange bar and the no-speech grace's
local-speech signal all behave exactly as today. What changes is that JVM tests now pin down how these rules
combine, including how the Listening tone and the connect buffer interact.

Spec: `.scratch/mic-gate/spec.md`.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

Land it as **two commits**, so there is a tested checkpoint for review and for bisecting:
1. the listening path and the connect buffer, with the tone drop still in the engine and still run before the gate;
2. the Listening tone suppression moved into the gate.

### Commit 1: listening path and connect buffer

- [x] The Mic gate is built once per conversation at mic open, before the capture thread starts. It takes whether
  this is a voice query. It has no Android dependencies, does no I/O and never reads a clock: each frame's capture
  time is passed in.
- [x] The gate owns these inputs from the orchestration thread:
  - listening started;
  - listening stopped, which also ends connect buffering and is also called at teardown.
- [x] Per frame, the gate returns plain data:
  - the ordered audio to send: on the first listening frame, the flushed connect buffer followed by the live frame;
  - the indicator: none, or a level;
  - whether speech was heard;
  - any log lines.
- [x] The engine applies that result:
  - it sends the audio;
  - it maps the indicator to the Hub level and to the orange bar, gated on whether the app is in the foreground;
  - on speech heard, it stamps the last-local-speech time with the same wall clock as today;
  - it writes the log lines.
- [x] The mic gain, the connect buffer and its cap, the half-duplex mute and the speech-heard check all live in
  the gate. Their constants keep their values: gain 2.0 and a cap of 50 frames.
- [x] Gate tests drive frame sequences through the interface and cover:
  - a voice query buffers while connecting, then flushes in capture order before the live frame;
  - a chip buffers nothing;
  - a model turn winning the race (listening stops before the first listening frame) sends nothing buffered;
  - frames while Jarvis speaks are muted;
  - the cap drops the oldest frame;
  - the gain is applied;
  - speech heard is false for silence and true for loud speech;
  - the flush log line is written once.
- [x] `GLOSSARY.md` gets a **Connect buffer** entry.
- [x] The full unit-test suite and the debug build pass.

### Commit 2: Listening tone suppression in the gate

- [x] The gate also takes whether the Listening tone will play, and starts the tone as pending if so. It takes two
  inputs from the tone player's thread: tone started at a time, and tone failed.
- [x] The suppression window, the "bar at rest" indicator for frames dropped while listening, and the one-time tone
  drop report all live in the gate.
- [x] The tone's playback states and the frame-suppression predicate move from the Listening tone module into the
  gate. The echo tail and the suppression window length stay with the tone, along with their device-measurement
  docs.
- [x] The engine has no tone fields or tone log counters left. Its mic-open sequence still decides whether the tone
  plays, builds the gate, starts the mic, and then starts the tone, whose callbacks now feed the gate.
- [x] The standalone suppression test file is replaced by gate tests covering:
  - pending drops everything;
  - the bar is at rest while listening;
  - the window edges: a frame at tone start, a frame straddling the window end, and the frame just after it;
  - a frame captured before the tone is dropped;
  - a failed tone means frames are sent again;
  - the tone and its window never count as speech heard;
  - the tone drop report is written exactly once.
- [x] A gate test pins down **the tone × connect-buffer interaction**: frames inside the window are never buffered,
  and only frames captured after the window but before listening are flushed.
- [x] `CLAUDE.md` describes the frame path as the Mic gate. The mic gain's "single seam" is now the gate.
- [x] The full unit-test suite and the debug build pass.

### Device check (gen2, after commit 2)

- [ ] Tone **off**: on a mic tap with speech during "Connecting…", the opening words are kept (the connect-buffer
  flush line is in the debug log).
- [ ] Tone **on**:
  - the model doesn't hear or react to the tone;
  - words spoken right after the tone are heard;
  - the orange bar or Listening state appears on time;
  - the debug log shows the same tone drop report as before.
- [ ] Wake ("hey jarvis") and mic-tap conversations both behave as before. A follow-up turn plays no tone, and the
  mic is muted while Jarvis speaks.
