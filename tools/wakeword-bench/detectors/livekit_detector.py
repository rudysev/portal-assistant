"""livekit-wakeword adapter (Phase 2b — requires a trained model).

livekit-wakeword trains a "hey jarvis" model locally and exports a standard ONNX file
that is openWakeWord-compatible. So once trained, inference reuses openWakeWord's runtime
exactly — this adapter is a thin subclass of OwwDetector pointed at the exported .onnx.

Train first (see train/README.md), then place/point at the .onnx:
    LiveKitDetector(model_path="train/livekit_hey_jarvis.onnx")

Raises if the model file is absent, so bench.py skips it gracefully until training is done.
"""
from __future__ import annotations

import os

from detectors.oww_detector import OwwDetector


class LiveKitDetector(OwwDetector):
    def __init__(self, model_path: str, threshold: float = 0.5, name: str | None = None):
        if not os.path.exists(model_path):
            raise RuntimeError(
                f"livekit model not found at {model_path}. Train it (train/README.md) "
                "then re-run. Skipped until then."
            )
        # openWakeWord accepts a path in wakeword_models; reuse its ONNX runtime unchanged.
        super().__init__(model_key=model_path, threshold=threshold,
                         inference_framework="onnx", name=name or "livekit-wakeword")
