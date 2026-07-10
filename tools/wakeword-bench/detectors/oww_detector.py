"""openWakeWord adapter — uses the pretrained `hey_jarvis` model, no training.

Thin wrapper over openwakeword.model.Model.predict() (its own streaming API). We only
feed 80 ms frames and read back the score dict; no library logic is reimplemented.

NOTE (report in results): openWakeWord's `hey_jarvis` was trained on Piper TTS, so it has
an in-distribution advantage on a Piper-generated test corpus. This is the headline caveat.
"""
from __future__ import annotations

import numpy as np

from detectors.base import ClipResult, Detector, iter_frames

FRAME_SAMPLES = 1280  # 80 ms @ 16 kHz — openWakeWord's expected chunk


class OwwDetector(Detector):
    def __init__(self, model_key: str = "hey_jarvis", threshold: float = 0.5,
                 inference_framework: str = "onnx", name: str | None = None):
        from openwakeword.model import Model
        from openwakeword import utils

        # pretrained models are downloaded once into the openwakeword package dir
        try:
            utils.download_models([model_key])
        except Exception:
            utils.download_models()

        self.threshold = threshold
        self.model_key = model_key
        self.name = name or "openwakeword"
        self._model = Model(wakeword_models=[model_key], inference_framework=inference_framework)
        # resolve the actual score key openWakeWord uses for this model
        self._score_key = next(iter(self._model.models.keys()))

    def reset(self) -> None:
        self._model.reset()

    def process(self, pcm: np.ndarray) -> ClipResult:
        peak = 0.0
        offset = None
        for start, frame in iter_frames(pcm, FRAME_SAMPLES):
            scores = self._model.predict(frame.astype(np.int16))
            s = float(scores.get(self._score_key, 0.0))
            if s > peak:
                peak = s
            if s >= self.threshold and offset is None:
                offset = start + FRAME_SAMPLES
        return ClipResult(fired=offset is not None, score=peak, offset=offset)
