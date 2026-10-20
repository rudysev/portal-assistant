# 05: Build the Listening tone's player ahead of time

**What to build:** Build the Listening tone's static-mode `AudioTrack` once, ahead of time (at
`ListeningTonePlayer.prewarm`, off the wake path), and reuse it for every tone instead of building a new track per
play. Today each play builds the track on the `listening-tone` worker, and the tone starts **+155…+175 ms after mic
open** (ticket 04 log). That build is the bulk of the gap between the wake word being detected and the user hearing
the cue, and of the time the tone is *pending* — during which every mic frame is dropped (user story 3). A
prebuilt track should start the tone within a few ms of mic open: a faster, more deterministic "I heard you", and
less audio dropped before the cue.

Spec: docs/specs/listening-tone.md.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Track built once at prewarm (synthesized samples written into it); each play rewinds and replays it rather
      than building a new one (e.g. `stop()` → `reloadStaticData()` / `setPlaybackHeadPosition(0)` → `play()`)
- [ ] Self-healing: if the prebuilt track is unusable (not initialized, audio server restarted, `play()` throws),
      release it and fall back to building a fresh one for that play, then keep the fresh one for next time
- [ ] Still exactly one of `onStarted` / `onFailed` per play, so the engine's pending state always ends
- [ ] Same audio attributes (`SpeechAudio`), so the tone still follows the voice's volume
- [ ] The Settings preview reuses the same track (plays are serialized on the one worker thread)
- [ ] No new delay or timer (CLAUDE.md "Don't assume timeouts")
- [ ] Verified on device (gen2, and gen1 if available): the "listening tone: playing (+N ms after mic open)" log
      drops well below today's +155…+175 ms; the tone still plays fully and is still kept out of the recording

## Comments

- 2026-10-20: Raised during the review of the pending-tone fix. With the track built per play, a hung build
  would also leave the tone pending (every frame dropped). Prebuilding moves the build off the wake path, which
  shrinks that risk without adding a timeout. The user ruled out a timeout on the pending state.
