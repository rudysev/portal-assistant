# 01: Audition the Listening tone

**What to build:** A pure `ListeningTone` synthesis that produces the Listening tone — a soft, rising two-note
sine (e.g. G5 then C6), about 250 ms, short attack ramp (no click), bell-like exponential decay ending at
silence, peak ~0.3 of full scale (the Timer chime uses 0.6), 16-bit mono PCM generated in memory (no asset).
Render it to a WAV the user can listen to on a laptop; tune until the user approves the sound.
Spec: docs/specs/listening-tone.md.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [x] Pure synthesis function exists, generated in code, constants easy to tune
- [x] Unit tests: duration ~200–300 ms; peak at target within tolerance, well below full scale; first and last samples near zero
- [x] WAV rendered for audition (outside the repo)
- [x] User approves the sound

## Comments

- 2026-10-19: Reworked toward a soft piano (3 detuned strings × 8 stretched partials, soft-hammer roll-off,
  two-stage decay, G5 rings under C6), level matched to the earlier "alt-softer" audition (peak 0.22), 240 ms.
  User approved this version for now ("a little digital but we will use this for now"). A warmer / more
  piano-like pass may follow later as a synthesis-only change.
