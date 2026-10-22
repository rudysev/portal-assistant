# 03: Keep the Listening tone out of the recording

**What to build:** Mic audio captured while the Listening tone plays, plus a short echo tail, is dropped — neither
connect-buffered nor forwarded — so the voice backend never hears or reacts to the tone, while words spoken right
after it are still heard. The tail is a device-measured, justified constant (like MIC_GAIN), not a client timer.
Spec: .scratch/listening-tone/spec.md.

**Blocked by:** 02 (Play the Listening tone when a voice conversation starts)

**Status:** closed

- [x] Pure suppression-window rule, unit-tested: frames at tone start and just before window end dropped; just after kept; no tone → nothing dropped
- [x] Capture thread drops suppressed frames; everything after behaves as before
- [x] Tail margin measured on device and documented
- [x] Verified on device: model doesn't transcribe/react to the tone; immediate speech after the tone is heard

## Comments

**Implementation (agent, 2026-10-19).**

- Pure rule in `conversation/ListeningTone.kt` (new "Suppression window" section, between gating and synthesis;
  synthesis untouched): `suppressesFrame(toneStartedAtMs: Long?, frameCapturedFromMs, frameCapturedUntilMs)`.
  It drops a frame whose capture span **overlaps** the window `[toneStart, toneStart + suppressionWindowMs)`,
  where `suppressionWindowMs = DURATION_MS + ECHO_TAIL_MS`. It takes a span, not a point, because frames are
  100 ms: a point check would let tone samples through at either edge. A null tone start (no tone: chip,
  follow-up, setting off, or play failure) drops nothing. `suppressionWindowMs` is a computed property, not a
  `const`, because `DURATION_MS` is declared later, in the synthesis section, and Kotlin rejects the forward
  reference. It follows any retune of `DURATION_MS` automatically.
- Tests, built test-first in a new class `ListeningToneSuppressionTest` (8 tests): a frame at tone start is
  dropped; a frame that began before the tone but holds its onset is dropped; a frame ending just before the
  window end is dropped; a frame straddling the window end is dropped; a frame starting just after the window
  end is kept; a frame that ended as the tone began is kept; with no tone nothing is dropped; the window
  exceeds the tone's length.
- Engine (`AssistantEngine`):
  - `@Volatile toneStartedAtMs: Long?` is written in `ListeningTonePlayer.play`'s `onStarted` callback (the tone
    worker thread) and read by the capture thread.
  - `onFrame` first calls `droppedForListeningTone()`, which stamps the frame as
    `[elapsedRealtime() − FRAME_MS, elapsedRealtime())` at delivery. Delivery lags the real capture by the input
    latency plus at most one 20 ms poll. That offset is steady, and the tail was measured on the same stamping,
    so it's absorbed.
  - A dropped frame returns early. It isn't connect-buffered or forwarded, and it doesn't drive the level, the
    orange bar or the no-speech grace's local-speech signal (the tone would read as the user speaking).
    Everything after the window behaves exactly as before: the connect-buffer flush simply happens on the first
    kept frame, in order.
  - Capture-thread-only counters feed one DebugLog line per conversation, at the first kept frame after the
    window: `listening tone: dropped N frame(s) (Nms) captured +a..+bms after the tone started (window 640ms)`,
    or `dropped nothing`.
- `ECHO_TAIL_MS = 400` (window 640 ms), with the measurement recorded in its KDoc.

**Tail measurement (gen2, temporary instrumentation, since removed).** I logged per-20 ms raw RMS of each frame,
plus Goertzel energy at the tone's frequencies (784/1046/1568/2093 Hz), with offsets from the tone start on the
engine's frame stamping. The tonal measure was needed because the room has intermittent broadband ambient
bursts that broadband RMS couldn't tell apart from the tone.
- At voice volume 4/18 (the device's setting) the tone barely registers in the mic: RMS 0–2, the same as the
  floor.
- At **max volume 18/18** (worst case), across 8 mic taps:
  - The tone reaches the mic at +147…+299 ms. `AudioTrack` output latency varies run to run.
  - It peaks at 1,000–5,600 RMS.
  - It falls back under the room's ambient maximum (about 100 tonal RMS, measured from frames after +1 s) by
    +426…+549 ms. A faint residue at the ambient p95 (10) lasts to about +650 ms, and the whole-frame overlap
    rule also catches it.
- Tail = 549 − 240 (tone length) ≈ 310 ms + ~90 ms margin → 400 ms. In practice 5 frames (500 ms) are dropped
  per conversation (occasionally 6–7 when the mic opens before the tone starts), covering about +150…+645 ms
  after tone start.

**Device verification (gen2; I can't hear the device, so all of this comes from debug.txt and screenshots):**
- Silent mic taps at max volume (4/4) and at 4/18: `listening tone: dropped 5–7 frame(s)…`, no `UserSpeaking`,
  no model turn, ended by `NoSpeechTimeout`.
- **Control:** a temporary build with the drop disabled (since removed). At max volume, 4/4 silent taps flushed
  1–4 tone-bearing frames from the connect buffer, and Gemini still didn't transcribe or react to them. So on
  this device and room the model doesn't react to the tone even unsuppressed. The suppression is defence in
  depth (and keeps the tone out of the no-speech grace's local VAD) rather than the fix for an observed
  reaction.
- Mic tap, then `say "What is two plus two?"`, started about 0.65 s after the tone began (right at the window
  end): the query was heard (`UserSpeaking`) and answered ("2 plus 2 is 4."), 2/2 at max volume and 1/1 at
  4/18. The transcript reads "One is two plus two". A control run with the query spoken 1.5 s after the tone
  showed the same mishearing, so it's ASR on the `say` voice, not clipping by the window.
- Wake word (`say "Hey Jarvis"` → gen2 Vosk `trigger=wake:jarvis`), then the query about 0.4 s after the tone
  was logged: heard and answered 2/2. One was transcribed exactly ("What is the capital of France?" → "Paris is
  the capital of France.").
- Acoustic note: Mac `say` reaches the Portal only through the Mac Studio Speakers. Earlier runs had gone to
  headphones, which explains ticket 02's failed transcriptions. Speakers at 60 worked.
- Results: `./gradlew testDebugUnitTest assembleDebug` is green (405 tests, 0 failures).
- Not verified: gen1 (portal-wake hand-off). It goes through the same `engine.start` → `onFrame` path.

**Notes for ticket 04:**
- The Settings preview (`ListeningTonePlayer.play()` from Settings) has no engine, so nothing is suppressed.
  That's correct: no conversation is recording.
- If the tone's `DURATION_MS` is retuned, the window follows automatically. If the tone is made much louder or
  longer-ringing, re-measure `ECHO_TAIL_MS`.


**2026-10-21 — closed.** All boxes ticked; the unit tests and the debug build pass on `main`.
