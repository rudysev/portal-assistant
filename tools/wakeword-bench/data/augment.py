"""Acoustic augmentation with numpy/scipy only (no fragile deps).

Two effects, matching the plan's conditions:
  - reverb: convolve with a room impulse response (synthetic exponential-decay RIRs at a
    few RT60s — a robust, download-free proxy for the MIT RIR survey; point at real RIR
    wavs via a directory if you have them).
  - noisy: mix background noise at a target SNR (synthetic pink/white noise always
    available; real noise wavs — e.g. a MUSAN subset — used if present).

Kept deliberately simple/robust per "avoid fragile libraries". `audiomentations` was
dropped because it pulls numba/llvmlite which fail to build here.
"""
from __future__ import annotations

import numpy as np
from scipy.signal import fftconvolve

from .audio_io import SAMPLE_RATE, rms, to_float, to_int16

_rng = np.random.default_rng(1234)


def set_seed(seed: int) -> None:
    global _rng
    _rng = np.random.default_rng(seed)


# ---- noise --------------------------------------------------------------------

def white_noise(n: int) -> np.ndarray:
    return _rng.standard_normal(n).astype(np.float32)


def pink_noise(n: int) -> np.ndarray:
    """Voss-McCartney-ish pink noise via FFT 1/f shaping."""
    x = _rng.standard_normal(n)
    X = np.fft.rfft(x)
    f = np.arange(1, len(X) + 1)
    X = X / np.sqrt(f)
    y = np.fft.irfft(X, n=n).astype(np.float32)
    return y / (np.std(y) + 1e-12)


def mix_noise(clean_i16: np.ndarray, noise_f: np.ndarray, snr_db: float) -> np.ndarray:
    """Mix noise into clean at target SNR (dB). Returns int16."""
    clean = to_float(clean_i16)
    if len(noise_f) < len(clean):
        reps = int(np.ceil(len(clean) / len(noise_f)))
        noise_f = np.tile(noise_f, reps)
    start = int(_rng.integers(0, max(1, len(noise_f) - len(clean))))
    noise = noise_f[start:start + len(clean)]
    sig_rms = rms(clean_i16)
    noise_rms = np.sqrt(np.mean(noise ** 2)) + 1e-12
    target_noise_rms = sig_rms / (10 ** (snr_db / 20.0))
    noise = noise * (target_noise_rms / noise_rms)
    mixed = clean + noise
    peak = np.max(np.abs(mixed)) + 1e-9
    if peak > 1.0:
        mixed = mixed / peak
    return to_int16(mixed)


# ---- reverb -------------------------------------------------------------------

def synth_rir(rt60_s: float = 0.4) -> np.ndarray:
    """Synthetic room impulse response: exponentially-decaying white noise (RT60 = rt60_s)."""
    n = int(rt60_s * 1.2 * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    decay = np.exp(-6.908 * t / rt60_s)  # -60 dB at rt60
    rir = _rng.standard_normal(n) * decay
    rir[0] = 1.0  # direct path
    return (rir / (np.max(np.abs(rir)) + 1e-9)).astype(np.float32)


def apply_reverb(clean_i16: np.ndarray, rir: np.ndarray) -> np.ndarray:
    clean = to_float(clean_i16)
    wet = fftconvolve(clean, rir)[: len(clean)]
    peak = np.max(np.abs(wet)) + 1e-9
    if peak > 1.0:
        wet = wet / peak
    return to_int16(wet)


# ---- gain (room distance proxy) ----------------------------------------------

def apply_gain(pcm_i16: np.ndarray, gain: float) -> np.ndarray:
    """Scale amplitude (e.g. 0.5 ~ the ~4 m / half-amplitude condition from PcmGain KDoc)."""
    return to_int16(to_float(pcm_i16) * gain)
