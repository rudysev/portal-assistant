# Portal-Assistant

Jarvis, a voice assistant for the Meta Portal. A conversation is powered by a pluggable backend (cloud
Gemini or a local LAN host).

## Language

### Backend & speech

**Voice backend**:
The engine that powers a conversation — Gemini (cloud) or a local voice host on the LAN.
_Avoid_: Voice (on its own), provider

**Credential**:
What the selected voice backend needs before a conversation can open — a Gemini API key, or a local server
address. With none set, no conversation opens and Jarvis says it isn't set up yet.
_Avoid_: Key (on its own), config

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

### Conversation

**Conversation**:
One exchange with Jarvis, from its trigger until the user goes silent, asks to end it, or the connection drops.
Multi-turn: after each answer Jarvis listens again for a follow-up. At most one conversation runs at a time.
_Avoid_: Session, turn (a turn is one side's part of a conversation)

**Turn**:
One side's part of a conversation — the user speaking, or Jarvis answering. A conversation is a run of
alternating turns: Jarvis listens for the user's turn, answers in its own, then listens again.
_Avoid_: Message, exchange, utterance

**Turn end**:
The moment Jarvis's turn is over: the voice backend has finished the answer, all of it has been heard, and
every action it asked for (a timer, a volume change) has been carried out — whichever of those happens last.
Only then does Jarvis listen again (or the conversation end).
_Avoid_: Turn complete (that's only the voice backend's part), done

**Final turn**:
A Jarvis turn after which the conversation ends at turn end instead of Jarvis listening again — because the user
asked to end it, or because their request expects no reply (a music command). Jarvis still finishes speaking it.
_Avoid_: Goodbye (that's only one way to ask), fast-exit, hang-up, last turn

**Music command**:
A request to start or steer playback: play something, resume, pause, skip, go back, set repeat. Asking what's
playing is not one; it expects an answer.
_Avoid_: Media command (too broad), music request

**Transcript**:
The conversation's turns as shown on screen, in words. While Jarvis is speaking, its turn appears word by word
in step with the audio actually heard — not every word streamed, only what has been heard so far.
_Avoid_: Chat, history, log

**Orb**:
The glowing circle that grows and brightens with the sound of the conversation: the user's voice while Jarvis
listens, Jarvis's voice while Jarvis answers. It's large and centred until something has been said, then small and
beside the End button once there's a transcript.
_Avoid_: Visualizer, waveform, bubble

**Standby**:
Jarvis is ready between conversations but holds no mic and isn't listening — the next trigger starts a
conversation without delay.
_Avoid_: Idle (that's the home screen), sleep

### Starting a conversation

**Trigger**:
What starts a conversation — exactly one of the wake word, a mic tap, or a suggestion chip. Everything else
about how the conversation opens (resume or fresh, voice query or not, Listening tone or not) follows from
which one it was.
_Avoid_: Source, entry point

**Opening**:
How one conversation begins, decided from its trigger: resume or fresh, whether the first turn is spoken or a
suggestion chip's text, whether the Listening tone plays, and what Jarvis is told up front. A conversation
without a credential gets no opening — it's refused.
_Avoid_: Plan, setup, start

**Wake word**:
The trigger of saying "hey jarvis". Always starts a fresh conversation.
_Avoid_: Hotword, wake (on its own)

**Foreground wake**:
Jarvis listening for the wake word itself, only on a Portal gen2 and only while the app is on screen — there, a
background mic is silenced, so portal-wake can't hand off. It needs a one-time download of the wake model first,
and stays off for the rest of the session if that model can't be set up.
_Avoid_: Wake detector, hotword, hands-free mode

**Mic tap**:
The trigger of tapping the mic in the app. Resumes the on-screen conversation if there is one.
_Avoid_: Tap-to-talk, tap (on its own)

**Suggestion chip**:
The trigger of tapping a suggested question on the idle home; its text is sent as the first turn. Always starts
a fresh conversation.
_Avoid_: Chip (on its own), suggestion

**Voice query**:
A conversation whose first turn is spoken — started by the wake word or a mic tap, never a suggestion chip.

**Resume**:
Continuing the conversation still on screen, so Jarvis keeps its context. Only a mic tap resumes.
_Avoid_: Continue, restore

### Mic

**Connect buffer**:
The mic audio captured while a voice conversation's connection to the voice backend is still opening, kept so
the user's opening words aren't lost, and sent ahead of the live audio once Jarvis starts listening. Only for a
voice query (wake word or mic tap), never for a suggestion chip; holds at most the most recent ~5 s.
_Avoid_: Pre-roll, connect window (the time, not the audio), buffer-while-connecting

### Sounds

**Listening tone**:
A short, soft sound played when a voice conversation starts, at the moment the mic begins capturing audio
that will be sent to the voice backend. Played for the wake word and a mic tap, never for a suggestion chip,
and not when the mic reopens for a follow-up. On by default; the user can turn it off in Settings.
_Avoid_: Chime, beep, earcon, wake sound

**Timer chime**:
The bell sound played when a timer finishes.
_Avoid_: Chime (on its own), alarm, tone

### Motion

**Remove animations**:
Android's system-wide setting asking apps to stop non-essential motion. Jarvis has no setting of its own. When
it's on, looping decoration holds still, while anything that shows live information (the **Orb**, a loading
spinner) keeps moving.
_Avoid_: Reduce motion, animations off
