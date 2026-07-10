# oWW operating-point tuning loop — 2026-07-10

Goal: improve openWakeWord on Portal (recall **and** false accepts) without custom training or
room-/device-specific overfit. Device: Portal gen2 `omni`. Corpus: macOS `say` TTS (relative).

## Verdict

**Keep `DEFAULT_SCORE_THRESHOLD = 0.5`. Do not add consecutive-frame patience.**

Non-training levers are exhausted: every gate that cuts soundalike FA also cuts reverb/noisy or
embedded recall, and the dominant hard FAs (`okay/yo/hi jarvis`, phonetic `jarvahs/jarvus`) score
like true positives (~0.99) so threshold cannot separate them. Background / ordinary-speech FA is
already **0**. Further precision needs hard-negative training or a lead-word gate (Vosk has one;
oWW does not, and Vosk is shadow-only for oWW-owned ids).

## Levers tried

| Lever | Iso / room recall | Near-miss / hard FA | Keep? |
|-------|-------------------|---------------------|-------|
| Raise thr (0.5→0.6/0.7) | Noisy/reverb ↓ | FA ↓ only ~2–3 pp on Mac | No |
| Lower thr (0.5→0.4) | Mac noisy/reverb ↑; acoustic embedded 25%→63% | Mac nearFA 29→40%; acoustic hard FA 21%→42% | No — FA doubles over air |
| Lower thr (0.5→0.45) | Small clean/embedded ↑ | Hard FA 21%→32% | No — not worth the FA |
| Patience=2 / hysteresis / EMA / N-of-M | Reverb/noisy ↓ hard | Modest soundalike cut; **no** effect on `okay jarvis` | No |
| Capture source / gain | Settled in `CAPTURE_STUDY.md` | — | Keep VOICE_RECOGNITION @ 1.0 |

## Why FA will not fall further without training

Clean `"hey jarvis"` peaks ~0.99. So do wrong-lead negatives (`okay/yo/hi/a jarvis`) and close
phonetics (`hey jarvahs/jarvus`). Activation duration matches the true phrase. Brief spikes
(`hey travis/charles`) *can* be cut with patience=2, but that same gate drops reverb/noisy iso
recall several points — bad for other rooms/devices.

## Acoustic A/B @ Mac speaker 40% (58 clips, same list)

| thr | positive_clean | positive_embedded | hard FA | bg / general FA |
|----:|---------------:|------------------:|--------:|----------------:|
| **0.50** (default) | 18/21 (86%) | 2/8 (25%) | 4/19 (21%) | 0 |
| 0.45 | 19/21 (90%) | 3/8 (38%) | 6/19 (32%) | 0 |
| 0.40 | 19/21 (90%) | 5/8 (63%) | 8/19 (42%) | 0 |

n is small — treat ±1 clip as noise. Direction is clear: lower thr buys embedded recall with
proportional hard-FA cost.

## Mac file-injection (11 448 clips, score re-eval)

| thr | overall recall | nearFA | bg FA clips |
|----:|---------------:|-------:|------------:|
| 0.50 | 87.3% | 29.1% | 0 |
| 0.45 | 90.5% | 35.6% | 0 |
| 0.40 | 92.8% | 40.3% | 2 |

Iso `"hey jarvis"` @0.50→0.40: clean 99.2% unchanged; noisy 88.4%→93.2%; reverb 84.1%→94.0%.

## Optional product knobs (not shipped)

- Prefer embedded recall over soundalike rejection → set wake `scoreThreshold` / `min_confidence` to **0.4** per install.
- Real FA fix → custom hey_jarvis with hard negatives (out of scope for this loop).

## Artifacts

- `acoustic_vol40.csv` (0.50), `acoustic_vol40_thr045.csv`, `acoustic_vol40_thr04.csv`
- Mac: `openwakeword.jsonl` + score re-eval above
- Branches: `tune/oww-threshold-0.45` (commons / wake / assistant) — docs + experiment; default remains 0.5
