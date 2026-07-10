"""microWakeWord adapter (Phase 2b — requires a trained model).

microWakeWord is a TF/TFLite framework for microcontroller-class wake models. It has its
own MFCC-style feature frontend and streaming TFLite inference, so — per "don't reimplement
library logic" — this adapter drives microWakeWord's OWN inference (MicroWakeWord /
tflite streaming), not a hand-rolled feature pipeline.

Training a "hey jarvis" model is a prerequisite (see train/README.md): microWakeWord
consumes the same augmented spectrogram features it trains on. Once you have the trained
.tflite + its training config, wire them here following microWakeWord's inference example.

Until a model is trained, this raises so bench.py skips it gracefully.
"""
from __future__ import annotations

import os

import numpy as np

from detectors.base import ClipResult, Detector


class MicroWakeWordDetector(Detector):
    def __init__(self, model_path: str, threshold: float = 0.5, name: str | None = None):
        self.name = name or "microwakeword"
        self.threshold = threshold
        if not os.path.exists(model_path):
            raise RuntimeError(
                f"microWakeWord model not found at {model_path}. Train it "
                "(train/README.md) then wire microWakeWord's streaming inference here. "
                "Skipped until then."
            )
        # Drive microWakeWord's own inference (its feature frontend + streaming TFLite):
        #   from microwakeword.inference import Model as MwwModel   # example API
        #   self._m = MwwModel(model_path)
        # Left unwired on purpose until a trained model + config exist, so we don't ship a
        # guessed feature pipeline (that would produce misleading numbers).
        raise NotImplementedError(
            "Wire microWakeWord's inference example once a trained model exists "
            "(train/README.md)."
        )

    def process(self, pcm: np.ndarray) -> ClipResult:  # pragma: no cover - unwired stub
        raise NotImplementedError
