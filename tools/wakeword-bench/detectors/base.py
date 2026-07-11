"""The single seam every wake-word detector implements.

Each concrete detector is a THIN adapter over its library's own API/example code —
we do not reimplement any library's detection logic (that would introduce bugs and
defeat the comparison). The adapter only: (1) feeds our canonical 16 kHz mono int16
audio in, and (2) reports a uniform per-clip result out.

Audio contract (matches the device's PcmCaptureFormat): 16 kHz, mono, 16-bit PCM.
Detectors receive a whole clip as an int16 numpy array and stream it internally in
whatever frame size the library wants.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional

import numpy as np

SAMPLE_RATE = 16_000
FRAME_BYTES = 3_200  # 100 ms @ 16 kHz mono 16-bit — matches PcmCaptureFormat.FRAME_BYTES


@dataclass
class ClipResult:
    """Outcome of running one detector over one clip.

    fired:  did the detector trigger at its configured operating point?
    score:  peak wake-likelihood in [0,1] over the clip (for detectors that expose a
            continuous score → lets analyze.py sweep a DET curve). Binary-only
            detectors report 1.0/0.0 and are plotted as a single operating point.
    offset: sample index at which it fired (for latency), or None.
    """
    fired: bool
    score: float
    offset: Optional[int]


class Detector(ABC):
    #: unique display name; variants add a suffix (e.g. "porcupine@0.7")
    name: str = "detector"

    def reset(self) -> None:
        """Clear any streaming state. Called before every clip."""

    @abstractmethod
    def process(self, pcm: np.ndarray) -> ClipResult:
        """Run the detector over one int16 mono 16 kHz clip; return a ClipResult."""

    def close(self) -> None:  # optional cleanup
        pass


def iter_frames(pcm: np.ndarray, frame_samples: int):
    """Yield (start_sample, frame) chunks of exactly frame_samples (last partial dropped)."""
    n = len(pcm) // frame_samples
    for k in range(n):
        s = k * frame_samples
        yield s, pcm[s:s + frame_samples]
