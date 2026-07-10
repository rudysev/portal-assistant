# Kitchen / same-device playback — oWW vs Vosk (2026-07-10)

Tests the openWakeWord warning: *playback of music/speech from the same device that is
capturing the mic* (no AEC) can crush wake recall — the Portal-in-a-kitchen-with-music case.

## Method

- Portal gen2 (`omni`) foreground assistant in `wakebench` (detection-only, cooldown=0).
- **Same-device interferer:** `WakeInterferer` loops `files/wakeinterferer.wav` on
  `STREAM_MUSIC` while the handset mic runs oWW + Vosk shadow.
- **Wake stimulus:** Mac `afplay` of 21 `positive_clean` “hey jarvis” clips @ speaker 40%.
- Interferers: synthesized multi-tone+pink-noise “music” bed; concatenated background-speech WAVs.
- Device music level via `files/wakeinterferer_vol` (percent of max). Harness:
  `device_bench_kitchen.sh`.

This is **not** room ambient music from another speaker — it is echo into the capturing mic.

## Results (positive_clean recall)

| Condition | oWW | Vosk | Winner |
|-----------|----:|-----:|--------|
| **Quiet** (no Portal playback) | **20/21 (95%)** | 19/21 (90%) | oWW |
| Music @ **25%** STREAM_MUSIC | **17/21 (81%)** | 14/21 (67%) | oWW |
| Music @ **40%** STREAM_MUSIC | 8/21 (38%) | **11/21 (52%)** | **Vosk** |
| Music @ **70%** STREAM_MUSIC | 0/21 (0%) | 0/21 (0%) | neither |
| Speech @ **40%** STREAM_MUSIC | 5/21 (24%) | **8/21 (38%)** | **Vosk** |

## Takeaway

1. The oWW AEC warning is real on Portal handset mic: same-device playback is a different regime
   from quiet-room / file-injection benches.
2. At **low** device music (~25%), oWW still leads. At **kitchen-moderate** levels (~40% music or
   speech), **Vosk recall is higher** (~1.4–1.6×), matching the product observation.
3. At very high device volume both fail — not a useful operating point.
4. If music-while-listening matters, keep Vosk in the path (shadow → promote on oWW miss, or
   fusion) rather than relying on oWW alone.

## Artifacts

- `results/kitchen_{quiet,music_vol25,music_vol40,music_vol70,speech_vol40}.csv`
- `device_bench_kitchen.sh`, `app/.../audio/WakeInterferer.kt`
