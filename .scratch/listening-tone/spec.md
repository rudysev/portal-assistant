# Spec: Listening tone

Status: closed (implemented by issues 01–05)

## Problem Statement

When the user says "hey jarvis" (or taps the mic), the only sign that Jarvis has started listening — and that
their voice is now being captured and sent to the voice backend — is visual: the orange bar in the background,
or the "Connecting…/Listening…" orb in the app. That is useless when the user isn't looking at the Portal:
across the room, cooking, facing away. They can't tell whether the wake word was heard, so they either repeat
it, start talking too early into nothing, or wait in silence. They also get no audible cue that the device has
started recording them.

## Solution

Play the **Listening tone** — a short, soft, rising two-note tone — at the moment a voice conversation starts,
exactly when the mic begins capturing audio that will be sent to the voice backend. The user hears it, knows
Jarvis is listening, and speaks. Because the user will hear it many times a day, it is deliberately quiet,
brief (about a quarter of a second) and gentle — no harsh beep. It is **on by default**, and the user can turn
it off with a single switch in a new **Sounds** section of Settings; flipping it on plays a preview.

The tone never pollutes the conversation: mic audio captured while the tone is playing (plus a short echo
tail) is dropped, so the voice backend never hears Jarvis's own tone.

## User Stories

1. As a user across the room, I want to hear a tone after I say "hey jarvis", so that I know Jarvis heard me without looking at the screen.
2. As a user, I want the tone to play the moment the mic starts capturing audio that will be sent, so that it truthfully tells me when I start being recorded.
3. As a privacy-conscious user, I want no audio captured before the tone to be sent to the voice backend, so that the tone is an honest "recording starts now" signal.
4. As a user, I want the tone to be soft and pleasant, so that hearing it many times a day doesn't annoy me or anyone else in the room.
5. As a user, I want the tone to be short, so that I can start speaking almost immediately after the wake word.
6. As a user, I want the tone to play for the wake word on a gen1 Portal (portal-wake hand-off, app in the background), so that I get feedback when the screen isn't showing Jarvis.
7. As a user, I want the tone to play for the wake word on a gen2 Portal (on-screen detector), so that I get feedback even though the app is technically in the foreground while I'm not looking at it.
8. As a user, I want the tone to play when I tap the mic in the app, so that the cue is consistent however I start talking.
9. As a user, I want no tone when I tap a suggestion chip, so that I don't get a "speak now" cue when my request was already sent as text.
10. As a user in a multi-turn conversation, I want no tone each time the mic reopens for a follow-up, so that a back-and-forth conversation isn't punctuated by repeated tones.
11. As a user, I want the tone to follow the same volume as Jarvis's voice, so that the volume keys control both together and the tone is never louder than the answer.
12. As a user, I want the tone to be noticeably quieter than Jarvis's speech, so that it reads as a subtle cue, not an alert.
13. As a user who has muted or turned the volume to zero, I want the tone to be silent too, so that the device respects my volume choice.
14. As a user, I want Jarvis not to react to its own tone (e.g. "I heard a sound"), so that my conversation starts cleanly with my words.
15. As a user who speaks immediately after the wake word, I want only the audio that overlaps the tone to be dropped, so that the rest of my request is still heard.
16. As a user who doesn't want the tone, I want a switch in Settings to turn it off, so that wake-ups are silent again.
17. As a new user, I want the tone on by default, so that I get audible feedback without having to discover a setting.
18. As a user turning the tone on, I want to hear a preview, so that I know what it sounds like before my next conversation.
19. As a user turning the tone off, I want no sound to play, so that switching it off is quiet.
20. As a user, I want a change to the setting to apply from my next conversation without restarting the app, so that it just works.
21. As a user, I want the setting to survive app restarts and device reboots, so that I don't have to set it again.
22. As a user on the Local voice backend, I want the tone to behave the same as on Gemini, so that the cue doesn't depend on which backend I picked.
23. As a user, I want the tone and the timer chime to sound clearly different, so that I never confuse "Jarvis is listening" with "a timer finished".
24. As a user, I want the Settings switch to be clearly labelled ("Listening tone" — "Play a soft tone when Jarvis starts listening"), so that I understand what it controls.
25. As a user whose missing API key or host stops a conversation from starting, I want no tone, so that I'm not told Jarvis is listening when it can't.
26. As a developer, I want the tone generated in code (no asset file), so that it's tunable by changing a few constants and doesn't depend on the device's unknown system sounds.
27. As a developer, I want the gating, synthesis and suppression rules to be pure and unit-tested, so that they can be changed safely.

## Implementation Decisions

- **Glossary.** The sound is the **Listening tone**; the existing timer sound is the **Timer chime**. Both terms
  are in `GLOSSARY.md`. Code and UI use "Listening tone", never "chime"/"beep"/"earcon".
- **New pure module `ListeningTone`** (the single test seam), following the pure-logic + thin-shell pattern of
  `ChimeSound` and `SpeakerPreview`/`SpeakerPreviewPlayer`. It owns three rules:
  - **Gating** — the tone plays only when *all* hold: the setting is on, the conversation is a **voice query**
    (wake word or mic tap — not a suggestion chip), and this is the conversation's start (not a follow-up
    re-listen). "Voice query" is the same condition the engine already uses to decide whether to buffer during
    connect, so no new branching is introduced.
  - **Synthesis** — a softly struck piano playing a rising perfect fourth (G5 then C6, the first note ringing
    under the second), 240 ms in total. Each note is a few slightly detuned strings of stretched partials under a
    soft-hammer roll-off, with a click-free attack, a two-stage decay, and a final damper fade so it ends at
    silence. Peak 0.22 of full scale (the Timer chime uses 0.6), matching the user-approved audition. Generated in
    memory as 16-bit mono PCM. (The first draft was a plain two-note sine at ~0.3 peak; the piano voicing replaced
    it during audition — ticket 01. A warmer pass may follow later as a synthesis-only change.)
  - **Suppression window** — given when the tone started, whether a mic frame captured at a given time must be
    dropped. The window is the tone's duration plus a tail margin covering output latency and room echo. The tail
    is a device-tuned, justified constant (like `MIC_GAIN`), not a guessed timeout; it is not a client timer and
    does not affect turn-taking.
- **Thin player shell.** A static-mode `AudioTrack` playing the synthesized PCM on the **same audio
  attributes as Jarvis's voice** (`SpeechAudio` — `USAGE_ASSISTANT` / speech), so it follows the same volume.
  The track is built once at prewarm (off the wake path) and rewound and replayed for every play, the Settings
  preview included; an unusable track is released and rebuilt for that play (the pure `PrebuiltTrack` policy).
  Failure to play is logged and never blocks or fails the conversation.
- **When it plays.** In the engine's conversation start, at the moment the mic is opened (CONNECTING) — not at
  socket-ready. Audio captured from mic open is buffered and later sent, so the tone must mark mic open to be an
  honest "recording starts now" cue. It plays only after the credential check passes (no tone when a
  conversation can't start).
- **Dropping the tone from the recording.** On the capture thread, frames inside the suppression window are
  dropped — neither added to the connect buffer nor forwarded. Frames after the window are buffered/forwarded
  exactly as today. Only the window's frames are lost, which is why the tone is kept short. The tone starts a
  little after the mic opens (handing off to the player's thread + output start), so frames captured in between — while the tone is
  *pending* — are dropped too, as is any frame that began before the window ends (user story 3); if the tone fails to play, the wait ends and frames are sent as if
  the tone were off. Dropped frames still show the orange bar (at rest) once LISTENING, so its timing is unchanged.
- **No reducer change.** The tone is tied to mic open in the engine shell, not to a phase transition; the
  `ConversationState` reducer, its actions, and its two client timers are untouched. Follow-up re-listens
  (`StartForwarding` after a turn) do not play the tone.
- **Triggers.** Wake word on gen1 (portal-wake `ACTION_WAKE` hand-off), wake word on gen2 (on-screen Vosk
  detector), and mic tap all play it, regardless of whether the app is in the foreground. A suggestion chip does
  not. The orange bar behaviour is unchanged.
- **Backend-neutral.** Lives entirely in the engine shell, behind no `VoiceBackend` dependency — identical for
  Gemini and Local.
- **Setting.** A new boolean preference, *listening tone enabled*, **default true** (absent key = on), read per
  conversation at start, so a change applies from the next conversation with no restart.
- **Settings UI.** A new **Sounds** section with one switch: title "Listening tone", subtitle "Play a soft tone
  when Jarvis starts listening". Switching it on plays the tone once as a preview; switching it off plays
  nothing. Styled like the existing switch rows (External tools).

## Testing Decisions

- Good tests assert external behaviour of the pure module — what plays, what it sounds like at the level a
  listener would notice (length, loudness, no click), and which frames are dropped — not internal sample math
  or note choices.
- **`ListeningTone` unit tests** (the one seam):
  - Gating: plays for a voice query at conversation start with the setting on; does not play when the setting
    is off, for a chip (text) query, or for a follow-up re-listen.
  - Synthesis: duration within ~200–300 ms; peak at the target level within tolerance and well below full
    scale; first and last samples near zero (no click at start or end).
  - Suppression window: a frame captured at tone start and just before the window ends is dropped; a frame just
    after the window ends is kept; with no tone played, nothing is dropped.
- **`PrebuiltTrack` unit tests** (the player's reuse policy, behind a fake track): a prepared track is replayed
  without rebuilding; an unusable one is released and a fresh one plays and is kept; when no track can play (or
  the build fails) the play fails without throwing and nothing broken is kept.
- **Not unit-tested** (consistent with the repo): the engine wiring, the `AudioTrack` shell, prefs and the
  Settings UI. These are validated on device: tone audible and soft at normal volume across the room; tone not
  transcribed or reacted to by the model; words spoken right after the tone are heard; switch default on,
  persists, previews on enable.
- **Prior art:** `NoSpeechGraceTest`, `RevealProgressTest`, `PcmGainTest` (pure engine-side helpers),
  `SpeakerPreviewTest` (pure core behind a player shell).

## Out of Scope

- A "mic closed" / end-of-listening tone.
- An error / failed-to-connect tone.
- A tone when the mic reopens for a follow-up in a multi-turn conversation.
- A tone for suggestion-chip (text) turns.
- Choosing between multiple tone sounds, or a volume slider separate from the system volume.
- Any change to the orange bar, the in-app listening orb, or the Timer chime.

## Further Notes

- **Audition before wiring.** Render the synthesized tone to a WAV first so the user can approve it on a
  laptop before it's integrated; tune notes/length/peak from that, then confirm the level on device.
- The suppression tail margin needs a device measurement (speaker → mic latency on gen1 and gen2); start
  generous and tighten, keeping the total dropped audio small.
- Update `CLAUDE.md`/`README.md` (Settings list, Phase 3 notes) when the feature lands.
