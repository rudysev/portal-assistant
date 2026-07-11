"""sherpa-onnx keyword-spotting adapter — open-vocabulary KWS, no training.

Thin wrapper over sherpa_onnx.KeywordSpotter (its own streaming API), following the
upstream python-api-examples/keyword-spotter.py. The "hey jarvis" keyword line is
generated once with `sherpa-onnx-cli text2token` (see data/fetch_assets.py) and passed
as keywords_file. No library logic is reimplemented.
"""
from __future__ import annotations

import numpy as np

from detectors.base import ClipResult, Detector, iter_frames

FRAME_SAMPLES = 1600  # 100 ms; sherpa accepts arbitrary chunk sizes


class SherpaKwsDetector(Detector):
    def __init__(self, tokens: str, encoder: str, decoder: str, joiner: str,
                 keywords_file: str, keywords_threshold: float = 0.25,
                 keywords_score: float = 1.0, num_threads: int = 1, name: str | None = None):
        import sherpa_onnx

        self.name = name or f"sherpa-kws@{keywords_threshold}"
        self._spotter = sherpa_onnx.KeywordSpotter(
            tokens=tokens,
            encoder=encoder,
            decoder=decoder,
            joiner=joiner,
            num_threads=num_threads,
            keywords_file=keywords_file,
            keywords_threshold=keywords_threshold,
            keywords_score=keywords_score,
            provider="cpu",
        )
        self._stream = self._spotter.create_stream()

    def reset(self) -> None:
        # fresh stream per clip (mirrors the example's reset-after-detection)
        self._stream = self._spotter.create_stream()

    def process(self, pcm: np.ndarray) -> ClipResult:
        f32 = pcm.astype(np.float32) / 32768.0
        offset = None
        for start, frame in iter_frames(f32, FRAME_SAMPLES):
            self._stream.accept_waveform(16000, frame)
            while self._spotter.is_ready(self._stream):
                self._spotter.decode_stream(self._stream)
            if self._spotter.get_result(self._stream):
                if offset is None:
                    offset = start + FRAME_SAMPLES
                self._spotter.reset_stream(self._stream)
        # sherpa KWS is threshold-internal → binary point (score mirrors fired)
        return ClipResult(fired=offset is not None, score=1.0 if offset is not None else 0.0,
                          offset=offset)
