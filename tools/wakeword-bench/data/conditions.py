"""Expand one clean utterance into the benchmark's acoustic conditions.

Shared by gen_positives and gen_negatives so positives and near-misses get the exact
same condition treatment (a fair comparison). Conditions:
  clean            — as recorded
  reverb           — convolved with a synthetic room RIR (RT60 ~0.4 s)
  noisy@<snr>      — pink noise mixed at the given SNR (dB)
  quiet            — amplitude 0.5 (the ~4 m / half-RMS room-distance proxy, PcmGain KDoc)
"""
from __future__ import annotations

import numpy as np

from . import augment

DEFAULT_SNRS = [20, 10, 5, 0]


def expand(pcm: np.ndarray, snrs=DEFAULT_SNRS, rt60: float = 0.4):
    """Yield (condition_name, snr_or_None, pcm) for every condition of one clip."""
    yield ("clean", None, pcm)
    yield ("reverb", None, augment.apply_reverb(pcm, augment.synth_rir(rt60)))
    yield ("quiet", None, augment.apply_gain(pcm, 0.5))
    for snr in snrs:
        noise = augment.pink_noise(len(pcm))
        yield (f"noisy", snr, augment.mix_noise(pcm, noise, snr))
