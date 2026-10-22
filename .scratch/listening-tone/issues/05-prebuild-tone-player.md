# 05: Build the Listening tone's player ahead of time

**What to build:** Build the Listening tone's static-mode `AudioTrack` once, ahead of time (at
`ListeningTonePlayer.prewarm`, off the wake path), and reuse it for every tone instead of building a new track per
play. Today each play builds the track on the `listening-tone` worker, and the tone starts **+155…+175 ms after mic
open** (ticket 04 log). That build is the bulk of the gap between the wake word being detected and the user hearing
the cue, and of the time the tone is *pending* — during which every mic frame is dropped (user story 3). A
prebuilt track should start the tone within a few ms of mic open: a faster, more deterministic "I heard you", and
less audio dropped before the cue.

Spec: .scratch/listening-tone/spec.md.

**Blocked by:** None (can start immediately)

**Status:** closed

- [x] Track built once at prewarm (synthesized samples written into it); each play rewinds and replays it rather
      than building a new one (e.g. `stop()` → `reloadStaticData()` / `setPlaybackHeadPosition(0)` → `play()`)
- [x] Self-healing: if the prebuilt track is unusable (not initialized, audio server restarted, `play()` throws),
      release it and fall back to building a fresh one for that play, then keep the fresh one for next time
- [x] Still exactly one of `onStarted` / `onFailed` per play, so the engine's pending state always ends
- [x] Same audio attributes (`SpeechAudio`), so the tone still follows the voice's volume
- [x] The Settings preview reuses the same track (plays are serialized on the one worker thread)
- [x] No new delay or timer (CLAUDE.md "Don't assume timeouts")
- [x] Verified on device (gen2; gen1 not available): the "listening tone: playing (+N ms after mic open)" log
      drops well below today's +155…+175 ms; the tone still plays fully and is still kept out of the recording

## Comments

- 2026-10-20: Raised during the review of the pending-tone fix. With the track built per play, a hung build
  would also leave the tone pending (every frame dropped). Prebuilding moves the build off the wake path, which
  shrinks that risk without adding a timeout. The user ruled out a timeout on the pending state.
- 2026-10-20 (implemented): pure `audio/PrebuiltTrack` (build once, replay, self-healing rebuild, exactly one
  outcome per play; `PrebuiltTrackTest`) behind the `ListeningTonePlayer` shell (`StaticToneTrack`: `stop()` →
  `reloadStaticData()` → `play()`). The 300 ms release delay is gone; no new delay or timer.
  **gen2 results:** tone start at **+3…+13 ms after mic open** in 9 conversations (7 taps + 1 wake word, plus 2 on
  the post-review build), down from +28…+44 ms typical / +155…+175 ms slow (ticket 04). No slow outlier was seen,
  but the old ones were intermittent, so 9 runs don't prove they're gone. **0 frames dropped while pending** in every run.
  An external mic heard every replay (and the Settings preview) with both notes at a steady level. Silent taps ended
  on no-speech, so the model never reacted to the tone, and speech after the tone was answered. No rebuild or failure
  was logged across ~13 plays. After plays, every audio output thread was in Standby, so the kept track holds no
  output open.
  **Known limits:** `play()` returns no status, so a native start that fails silently isn't detected (the framework
  restores a track after an audio-server restart itself). A finished track stays "started" client-side until the
  next play. A play during a still-sounding tone restarts it. gen1 is unverified.

**2026-10-21 — closed.** All boxes ticked; the unit tests and the debug build pass on `main`.
