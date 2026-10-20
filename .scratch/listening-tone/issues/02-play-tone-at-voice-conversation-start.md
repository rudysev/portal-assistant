# 02: Play the Listening tone when a voice conversation starts

**What to build:** Saying "hey jarvis" (gen1 portal-wake hand-off and gen2 on-screen detector) or tapping the mic
plays the Listening tone, at the moment the mic opens (CONNECTING, not socket-ready), on the same audio
attributes / volume as Jarvis's voice. No tone for a suggestion chip, for a follow-up re-listen, or when the
conversation can't start (missing credential). Read a persisted "listening tone enabled" preference per
conversation, default on (no Settings switch yet). Backend-neutral; reducer unchanged. Spec: docs/specs/listening-tone.md.

**Blocked by:** 01 (Audition the Listening tone)

**Status:** ready-for-agent

- [x] Pure gating rule, unit-tested: plays only for setting on + voice query + conversation start
- [x] Thin one-shot player shell on the voice's audio attributes; failure logged, never blocks the conversation
- [x] Wired at mic open in the engine; no tone on missing credential, chip, or follow-up
- [x] Preference with default true, read per conversation
- [x] Verified on device: wake and mic tap play the tone; chip does not

## Comments

**Implementation (agent, 2026-10-19).**

- Gating: `ListeningTone.playsAtMicOpen(toneEnabled, voiceQuery, conversationStart)` added to the top of
  `conversation/ListeningTone.kt` (synthesis untouched). Built test-first in its own class
  `ListeningToneGatingTest`, four tests: plays for a voice query at conversation start with the setting on;
  silent with the setting off, for a suggestion chip, and on a follow-up re-listen.
- Player shell: new `conversation/ListeningTonePlayer.kt`. It plays `ListeningTone.synthesize()` once on a
  static `AudioTrack` with `SpeechAudio.attributes()`, on its own daemon thread, so `play()` returns immediately.
  Build and play failures are logged (`listening tone: failed …`) and swallowed. The track is released at
  tone length + 300 ms (resource cleanup only, not a conversation timer). `prewarm()` synthesizes the samples
  ahead of time and is called from `AssistantService.prewarm()`; a cold first tone measured +163 ms before
  this, +29 ms after.
- Engine: `AssistantEngine.start()` computes `voiceQuery = pendingInitialText == null` once. It drives both
  `connectBuffering` and the new `playListeningToneAtMicOpen(voiceQuery)`, which is called right after
  `mic.start()` and therefore after the credential check. The reducer is unchanged, and `StartForwarding`
  doesn't touch the tone. Log lines: `listening tone: playing (+Nms after mic open)` and
  `listening tone: skipped (enabled=…, voiceQuery=…)`.
- Preference: `AppPrefs.listeningToneEnabled` / `setListeningToneEnabled`, key `sounds_listening_tone`
  (absent key = on).
- Results: `./gradlew testDebugUnitTest` passes (397 tests, 0 failures); `assembleDebug` passes.

**Device evidence (gen2, debug.txt; I couldn't hear the device, so all of this comes from the logs):**
- Mic tap: `listening tone: playing (+21…36ms after mic open)` on 5 taps, including a cold process.
- Chip ("What's the weather?"): `listening tone: skipped (enabled=true, voiceQuery=false)`. Its follow-up
  re-listen (`TurnComplete → LISTENING`) logged no tone line.
- Wake word (macOS `say "Hey Jarvis"`, gen2 on-screen Vosk): `trigger=wake:jarvis` →
  `listening tone: playing (+26ms / +30ms after mic open)`.
- Voice conversation with a recognized query (tap, then `say "What is two plus two?"`): `UserSpeaking` events,
  then the answer, then `PlaybackIdle → LISTENING` with no second tone line. So exactly one tone per
  conversation, and words spoken after it are heard.
- Setting off (prefs edited via `run-as`): `skipped (enabled=false, voiceQuery=true)`. With the key removed,
  the tone plays again (default on).
- Not verified: the gen1 portal-wake hand-off (no gen1 device attached). It goes through the same
  `AssistantService.start` → `engine.start` path. Two wake runs at Mac volume 31 got no transcription of the
  spoken query, and the same happened in a control run with the tone off, so the acoustic setup was too quiet;
  the tone wasn't the cause.

**For ticket 03:** `ListeningTonePlayer.play(onStarted)` already reports the `SystemClock.elapsedRealtime()` at
which playback started (the engine only logs it today). On device the tone starts *before*
`pcm capture started`: `MicCapture.start()` opens `AudioRecord` asynchronously, ~130 ms after the tone begins.
So the suppression window should key on the tone's start time against each frame's capture time, not on the
first frame. **For ticket 04:** the Settings switch can bind to `AppPrefs.listeningToneEnabled` /
`setListeningToneEnabled`, and the on-switch preview can call `ListeningTonePlayer.play()`.
