# Capture-source study — VOICE_RECOGNITION vs MIC vs input-gain

**Question:** would `MediaRecorder.AudioSource.MIC` (or an input gain) improve openWakeWord recall on the
Portal handset mic vs the current `VOICE_RECOGNITION`?

**Answer: no. Keep VOICE_RECOGNITION at gain 1.0.** MIC is worse on both recall and precision; gain is a
non-lever at normal distance; and the capture was never the bottleneck.

## Method

- 97-clip subset (42 positives × 7 conditions, 45 near-misses, 10 background), played out loud into the
  Portal mic at ~1 m. openWakeWord peak score per clip captured via on-device **bench mode**
  (`files/wakebench`), source selected via `files/wakesrc`, gain via `files/wakegain`. Harness:
  `run_sources.sh` → `device_bench.sh` → `analyze_sources.py`. "Fire" = peak score ≥ 0.30.
- The instrumentation (configurable `AudioRecordPcmDevice` source, `OwwRecognizer` wake-gain, `WavRecorder`
  dump, per-frame RMS logging) is committed as a diagnostic, **off by default**.

## Key methodology fix (this changed the result)

**Android 10 silences the mic whenever the display dozes.** An unattended batch run captured nothing on
~half the clips (RMS=0, inverted recall) and had understated the *first* device benchmark to 71%. Forcing
the screen awake (`svc power stayon true` + periodic `KEYCODE_WAKEUP`) fixed it. Also learned: **do not
disable portal-wake** during tests — it suppresses the Portal's native "Hey Alexa" (millennium); disabling
it lets millennium steal the mic.

## Results (screen-awake, 97 clips)

| config | recall | nearFA | bgFA | median score |
|---|--:|--:|--:|--:|
| **VOICE_RECOGNITION** | **83.3%** | 35.6% | 0.0% | 0.785 |
| MIC | 78.6% | 44.4% | 0.0% | 0.619 |
| VOICE_RECOGNITION + gain 1.5 | 88.1% | 40.0% | 0.0% | 0.796 |
| VOICE_RECOGNITION + gain 2.0 | 83.3% | 48.9% | 0.0% | 0.652 |
| VOICE_RECOGNITION + gain 3.0 | 85.7% | 44.4% | 0.0% | 0.841 |

## Findings

1. **MIC is worse (hypothesis refuted).** Lower recall, higher near-miss FA, lower median score. Its OEM
   AGC/noise-suppression degrades the clean signal and makes soundalikes score higher. VOICE_RECOGNITION
   already runs AGC/NS-free.
2. **With stable capture, device ≈ Mac.** VOICE_RECOGNITION recall 83% vs Mac 81% on the same clips; peak
   speech RMS ≈2600 vs the corpus ≈2100. The earlier large "Mac→device gap" was mostly the screen-doze
   artifact, not acoustic loss. **The capture level is fine — there's nothing to fix there.**
3. **Gain is within noise.** At 1 m the capture is already in openWakeWord's trained level range, so gain
   mostly shifts the whole distribution (recall and nearFA rise together). No clean win.
4. **Real-speech precision is rock-solid** — 0% background false-accepts across all five configs.
5. **UNPROCESSED** is unsupported on the Portal (`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` false → falls
   back to VOICE_RECOGNITION).

## Recommendation

- **No capture change.** Keep `VOICE_RECOGNITION`, gain 1.0. Leave the diagnostic instrumentation in (off).
- **If revisiting recall later**, the productive levers are: (a) a **custom hey_jarvis model** trained with
  hard negatives — fixes the heavy-noise floor (~50% @ 0 dB) and the soundalike nearFA together;
  (b) a **live-human** re-measure (this study is TTS-through-a-speaker, a worst case); (c) the threshold,
  still 0.30. Capture source/gain are settled.

## Caveats

- TTS played through a speaker into the mic — a worst case; live human speech will score higher.
- n=6 per condition cell → cell-level differences are noisy (~±5% run-to-run). The headline per-config
  numbers aggregate 42 positives and are more stable.
