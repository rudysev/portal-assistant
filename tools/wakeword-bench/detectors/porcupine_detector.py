"""Picovoice Porcupine adapter — uses the FREE built-in `jarvis` keyword.

Thin wrapper over pvporcupine.create(...).process() (its own API). No library logic is
reimplemented.

Caveats reported in results:
  - This is the built-in keyword `jarvis`, NOT the exact phrase "hey jarvis" (a custom
    `hey jarvis` .ppn needs a paid Picovoice tier). So Porcupine is graded on the keyword,
    not the lead — not strictly apples-to-apples vs the phrase detectors. Flagged, not hidden.
  - Requires a free access key in env PICOVOICE_ACCESS_KEY (single-device free tier).
  - Sensitivity is the operating point; instantiate variants at several sensitivities.
"""
from __future__ import annotations

import os

import numpy as np

from detectors.base import ClipResult, Detector, iter_frames


class PorcupineDetector(Detector):
    def __init__(self, keyword: str = "jarvis", sensitivity: float = 0.5,
                 access_key: str | None = None, name: str | None = None):
        import pvporcupine

        key = access_key or os.environ.get("PICOVOICE_ACCESS_KEY")
        if not key:
            raise RuntimeError(
                "Porcupine needs a free access key. Set PICOVOICE_ACCESS_KEY "
                "(get one at https://console.picovoice.ai/). This detector is skipped without it."
            )
        self.name = name or f"porcupine[{keyword}]@{sensitivity}"
        self.keyword = keyword
        self._p = pvporcupine.create(
            access_key=key, keywords=[keyword], sensitivities=[sensitivity]
        )
        self._frame_length = self._p.frame_length  # 512

    def reset(self) -> None:
        pass  # porcupine is stateless across process() calls at clip granularity

    def process(self, pcm: np.ndarray) -> ClipResult:
        offset = None
        for start, frame in iter_frames(pcm, self._frame_length):
            if self._p.process(frame.astype(np.int16)) >= 0:
                offset = start + self._frame_length
                break
        return ClipResult(fired=offset is not None, score=1.0 if offset is not None else 0.0,
                          offset=offset)

    def close(self) -> None:
        try:
            self._p.delete()
        except Exception:
            pass
