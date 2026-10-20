# 04: Listening tone switch in Settings

**What to build:** A new **Sounds** section in Settings with one switch — title "Listening tone", subtitle "Play a
soft tone when Jarvis starts listening". On by default, persists across restarts, applies from the next
conversation. Switching on plays the tone once as a preview; switching off is silent. Update CLAUDE.md/README.md
Settings notes. Spec: docs/specs/listening-tone.md.

**Blocked by:** 02 (Play the Listening tone when a voice conversation starts)

**Status:** ready-for-agent

- [x] Sounds section + switch, styled like existing switch rows
- [x] Default on; toggling persists; next conversation honours it
- [x] Preview on enable, silence on disable
- [x] Docs updated
- [x] Verified on device

## Comments

**Implementation (agent, 2026-10-19).**

- Pure rule: `ListeningTone.previewsOnSwitch(switchedOn, conversationLive)` added to the gating section of
  `conversation/ListeningTone.kt` (synthesis and suppression untouched). It previews only when the switch was turned
  on and no conversation is live. The live check matches the Speaker preview, which pauses while a conversation is
  live. During a conversation the mic is open, and the Settings preview isn't covered by the engine's suppression
  window, so it would be recorded and sent to the voice backend. Settings is reachable mid-conversation (the gear
  stays on screen while LISTENING; on gen2 a wake word can also start one while Settings is open), so this case is
  real.
- Tests: 3 new tests in `ListeningToneGatingTest`, built test-first one at a time: switching on previews the tone;
  switching off is silent; no preview while a conversation is live. The Compose UI and AppPrefs aren't unit-tested
  (repo convention), so they were checked on device instead.
- UI (`ui/SettingsScreen.kt`): a new private `SoundsSection` with a "Sounds" header, the line "Cues Jarvis plays, at
  the same volume as its voice.", and one switch row: "Listening tone" / "Play a soft tone when Jarvis starts
  listening". It binds to `AppPrefs.listeningToneEnabled` / `setListeningToneEnabled`. The preview calls
  `ListeningTonePlayer.play` and logs `listening tone: preview playing (Settings)` on start, or
  `listening tone: preview skipped (conversation live)`. The External tools row was extracted into a shared private
  `SettingsSwitchRow` (same modifiers, text styles and switch colours), which both sections use, so the rows can't
  drift apart in style.
- Placement: the section sits after Default music app and just before External tools. It is shown on both voice
  backends because the tone is backend-neutral. That's why it isn't next to Voice, which only appears for Gemini.
  External tools stays last because it is a variable-length list of third-party apps; Sounds joins the other
  built-in preferences above it.
- Docs: CLAUDE.md's Phase 3 Settings list now includes Sounds (and the shared `SettingsSwitchRow`). README.md's
  "Talking to it" list has a new "Listening tone" bullet pointing to Settings → Sounds. The SettingsScreen KDoc is
  also updated.
- Results: `./gradlew testDebugUnitTest assembleDebug` is green (408 tests, 0 failures).

**Device evidence (gen2; I can't hear the device, so all of this comes from debug.txt, shared_prefs and
screenshots):**
- The section renders, matches the External tools rows, and is on by default with the key absent. Screenshots:
  `/private/tmp/claude-501/-Users-rudys-Documents-Software-portal-apps-portal-assistant/8f7e45b1-328f-4293-be04-189cbc16e432/scratchpad/settings-sounds.png`
  (on) and `…/scratchpad/settings-off.png` (off).
- Switched off: `sounds_listening_tone=false`, and no preview line was logged. After `am force-stop` and a relaunch,
  the switch still shows off (`…/scratchpad/settings-off-relaunch.png`). A mic tap then logged
  `listening tone: skipped (enabled=false, voiceQuery=true)`.
- Switched on: `sounds_listening_tone=true` and `listening tone: preview playing (Settings)`. The next mic tap logged
  `listening tone: playing (+155ms after mic open)`, then `dropped 7 frame(s)`.
- Switched off then on during a live mic-tap conversation (LISTENING): logged
  `listening tone: preview skipped (conversation live)` before `NoSpeechTimeout → ENDED`, so no preview played into
  the open mic. In an earlier attempt the taps landed just after the conversation ended, and the preview played
  correctly.
- Device restored afterwards: the setting is on, `svc power stayon false`, and the screensaver setting is unchanged
  (it was already 0).

**Open observation (not caused by this ticket; the engine path is untouched):** tone start relative to mic open
varies. Most taps logged +28…+44 ms, but some logged +155…+175 ms (including one in ticket 03's runs). In those
slower cases the tone starts in the same millisecond as `pcm capture started`, which suggests the audio server
serializes the track start behind the `AudioRecord` open. The suppression window still covered the tone in every run.

- 2026-10-19 (user request): Sounds moved up to sit right after the backend / API-key sections and just above
  Location (still shown for both backends).
