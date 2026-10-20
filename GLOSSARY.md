# Portal-Assistant

Jarvis, a voice assistant for the Meta Portal. A conversation is powered by a pluggable backend (cloud
Gemini or a local LAN host).

## Language

### Backend & speech

**Voice backend**:
The engine that powers a conversation — Gemini (cloud) or a local voice host on the LAN.
_Avoid_: Voice (on its own), provider

**Speaker**:
A named prebuilt voice the Gemini voice backend speaks with (e.g. Charon, Kore), labelled Male or Female.
Only exists for the Gemini voice backend.
_Avoid_: Voice, Gemini voice, voice name

**Chosen speaker**:
The Speaker saved in Settings; every Gemini conversation speaks with it, however the conversation started.
_Avoid_: Default speaker, default voice, selected voice

**Server default**:
No Speaker chosen — Gemini picks its own voice. Every install starts here, and the user can return to it.
_Avoid_: Default speaker, Puck (not known to be Gemini's default), none

### Sounds

**Listening tone**:
A short, soft sound played when a voice conversation starts, at the moment the mic begins capturing audio
that will be sent to the voice backend. Played for the wake word and a mic tap, never for a suggestion chip,
and not when the mic reopens for a follow-up. On by default; the user can turn it off in Settings.
_Avoid_: Chime, beep, earcon, wake sound

**Timer chime**:
The bell sound played when a timer finishes.
_Avoid_: Chime (on its own), alarm, tone
