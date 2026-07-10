# Training the two trained detectors (Phase 2b)

microWakeWord and livekit-wakeword don't ship a pretrained "hey jarvis" — they must be
trained. Both are gated behind the no-training detectors (Vosk, openWakeWord, sherpa,
Porcupine) so the core comparison lands first; add these as extra data points.

## Train/test hygiene (important)

Train on a **disjoint** corpus so no test clip is ever seen in training:

```bash
python -m data.manifest --role train      # different seed (+100) → disjoint from test
```

This writes `corpus/train/` + `corpus/train_manifest.jsonl`. Use those clips (positives +
near-miss + background) as the labeled training set. Never train on `corpus/test/`.

## livekit-wakeword

Exports a standard **openWakeWord-compatible ONNX**, so once trained, inference reuses the
openWakeWord runtime unchanged (see `detectors/livekit_detector.py`).

1. Install: `pip install livekit-wakeword` (or clone the repo).
2. Train with its single-command trainer on the `train/` positives + negatives.
3. Export the ONNX to `train/livekit_hey_jarvis.onnx`.
4. `python bench.py --detectors livekit` — it drops into the DET-curve comparison.

## microWakeWord

TF/TFLite, targets microcontrollers. Has its own MFCC-style frontend + streaming TFLite
inference — drive **its** inference (don't hand-roll features).

1. Install microWakeWord and its training deps (TensorFlow).
2. Generate its spectrogram features from the `train/` corpus (its data pipeline), train,
   export `train/mww_hey_jarvis.tflite` + the training config.
3. Wire microWakeWord's streaming inference into `detectors/mww_detector.py` (the marked
   TODO), following its inference example.
4. `python bench.py --detectors mww`.

Training runs on the M2 CPU (small models; slower than GPU but feasible — expect minutes to
low hours depending on corpus size and epochs).
