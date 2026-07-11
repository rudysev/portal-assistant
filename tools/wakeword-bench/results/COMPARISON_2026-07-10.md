# oWW vs Vosk wake-word benchmark — 2026-07-10

Portal gen2 (`omni`, API 29). Branches: `portal-assistant` `bench/oww-vs-vosk` (rebased onto
`e0a9c32`), `portal-wake` `bench/oww-vs-vosk`, commons shadow-log fix. Corpus: macOS `say` TTS
(not Piper — avoids handing oWW an in-distribution advantage). Treat as **relative**.

## Headline

| Path | Metric | openWakeWord | Vosk |
|------|--------|-------------:|-----:|
| **Mac file-injection** (11 448 clips) | overall recall | **87.3%** | 69.8% |
| | clean / iso `"hey jarvis"` | **98.3% / 99.2%** | 66.1% / 94.4% |
| | near-miss FA | **29.1%** | 39.6% |
| | background FA/hr | **0.00** | 0.00 |
| | latency P50 | **23 ms** | 700 ms |
| **On-device WAV injection** (837 clean clips) | positive recall | **99.2%** | 95.0% |
| | hard-negative FA | **22.8%** | 70.0% |
| | background FA/hr | **0.00** | 0.00 |
| **Speaker→mic @40%** (58 clips) | positive_clean | 86% (18/21) | **95%** (20/21) |
| | hard-negative FA | **21%** (4/19) | 47% (9/19) |

**Takeaway:** On bit-identical audio, **oWW wins recall and precision** and is ~30× lower latency.
Over the air into the Portal handset mic, **Vosk often recalls better** (especially embedded wakes),
but still false-accepts far more soundalikes. Neither false-accepts on ordinary background speech
in these runs.

## 1. Mac harness — 11 448 clips

Generated with `--large`: 18 voices × rates × conditions; 4 403 positives, 6 804 near-misses,
20.1 min background + silence.

```
detector        recall   nearFA    FA/hr   latP50   latP90     RTF
openwakeword     87.3%    29.1%     0.00     23ms    180ms   0.018
vosk             69.8%    39.6%     0.00    700ms    700ms   0.030
```

Clean-condition recall: oWW **98.3%**, Vosk 66.1% (Vosk is dragged down by embedded phrases in the
positive set — iso `"hey jarvis"` only: oWW 99.2%, Vosk 94.4%).

### Near-miss FA by phrase (Mac)

| Phrase | oWW | Vosk | Notes |
|--------|----:|-----:|-------|
| bare `jarvis` / `yo jarvis` / `jarvis jarvis` | high | **0%** | Vosk lead-word gate works |
| `okay/hi/a jarvis` | high | medium–high | contains keyword |
| `hey travis/charles/charmless/jervis/…` | medium | **very high** | Vosk grammar bias |
| `hey jeremy` / `hey james` | ~0–1% | 12–23% | |
| `hey alexa` / `hey there` / rivals | **0%** | **0%** | |

## 2. On-device WAV injection (Portal CPU, no mic)

Smoke (58 clips) and large (837 clean clips from the Mac corpus).

| | oWW smoke | Vosk smoke | oWW large | Vosk large |
|--|----------:|-----------:|----------:|-----------:|
| positive_clean | 21/21 | 21/21 | **374/377 (99.2%)** | 358/377 (95.0%) |
| hard FA | 6/19 | 10/19 | **91/400 (22.8%)** | 280/400 (70.0%) |
| general / background | 0 | 0 | 0 | 0 |

Large-set hard FA is dominated by soundalikes for Vosk (`hey travis/harris/service/…` ~75–92%)
vs oWW (`hey charles/travis/charmless` ~47–58%; `hey jeremy/james` 0%).

## 3. Speaker→mic acoustic (Mac speaker → Portal handset)

`wakebench` marker: detection-only, cooldown=0 so both detectors can fire. Volumes 20 / 40 / 50%.

| Vol | positive_clean oWW | Vosk | hard FA oWW | Vosk | embedded oWW | Vosk |
|----:|-------------------:|-----:|------------:|-----:|-------------:|-----:|
| 20% | **19/21 (90%)** | 17/21 (81%) | 6/19 (32%) | **5/19 (26%)** | 3/8 | 7/8 |
| 40% | 18/21 (86%) | **20/21 (95%)** | **4/19 (21%)** | 9/19 (47%) | 2/8 | 8/8 |
| 50% | 18/21 (86%) | **21/21 (100%)** | **5/19 (26%)** | 9/19 (47%) | 3/8 | 8/8 |

Quieter playback favors oWW recall; louder favors Vosk. Vosk still pays for soundalike FAs at
40–50%. Background/general: 0/0 at all three volumes. Embedded wakes: Vosk ≫ oWW over the air.

## 4. Tuning loop (post-benchmark)

Exhausted non-training levers on this device — full write-up in [`TUNING_LOOP.md`](TUNING_LOOP.md).

**Keep threshold 0.5; no consecutive-frame patience.** Lowering to 0.4 improves acoustic embedded
recall (25%→63% @40% speaker) but roughly doubles hard-negative FA (21%→42%). Patience/hysteresis
cut brief soundalike spikes but regress reverb/noisy iso recall. Dominant FAs (`okay/yo jarvis`,
phonetic near-misses) score like true positives — only hard-negative training or a lead-word gate
fixes them. Background FA stays 0 throughout.

## Artifacts

- Mac: `portal-assistant/tools/wakeword-bench/results/{openwakeword,vosk}.jsonl`, `summary_large.md`
- On-device: `portal-wake/benchmark/{results,report}_large.csv/txt`, smoke `results.csv`
- Tuning: `TUNING_LOOP.md`, `acoustic_vol40{,_thr045,_thr04}.csv`
- Acoustic: `portal-assistant/tools/wakeword-bench/results/acoustic_vol{20,40,50}.csv`
- Harness: restored `tools/wakeword-bench`, wake `benchmark/` dual `WakeBenchmark`, `device_bench_dual.sh`
- Fix: `WakeMicEventHandler` always logs fires (shadow visibility); assistant `files/wakebench` mode

## Recommendation (from this data)

Keep **oWW as primary** for precision + latency; keep **Vosk as shadow** for coverage diagnostics.
If acoustic recall on the handset mic is the bottleneck, consider a slightly lower oWW threshold
and/or gain — not switching primary back to Vosk (70% hard-negative FA on-device is worse).
