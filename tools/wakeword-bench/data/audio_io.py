"""Shared audio helpers: canonical 16 kHz / mono / int16, macOS `say` synthesis, resampling.

Canonical format matches the device (PcmCaptureFormat): 16 kHz, mono, 16-bit PCM.
Everything in the corpus is stored as int16 mono WAV at 16 kHz.
"""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

SAMPLE_RATE = 16_000

# Natural-sounding macOS voices (novelty/robotic ones like Bells/Boing/Zarvox excluded —
# they aren't representative human speech and would unfairly penalise detectors).
SAY_VOICES = [
    "Samantha", "Daniel", "Karen", "Fred", "Kathy", "Ralph", "Albert", "Flo",
    "Grandma", "Grandpa", "Junior", "Reed", "Rocko", "Sandy", "Shelley", "Alex",
    "Victoria", "Tom", "Vicki", "Allison", "Ava", "Susan", "Zoe", "Tessa", "Moira",
    "Fiona", "Rishi", "Serena",
]


def to_int16(x: np.ndarray) -> np.ndarray:
    if x.dtype == np.int16:
        return x
    x = np.clip(x, -1.0, 1.0)
    return (x * 32767.0).astype(np.int16)


def to_float(x: np.ndarray) -> np.ndarray:
    if np.issubdtype(x.dtype, np.floating):
        return x.astype(np.float32)
    return x.astype(np.float32) / 32768.0


def load_wav(path: str | Path) -> np.ndarray:
    """Load any wav → int16 mono 16 kHz."""
    data, sr = sf.read(str(path), always_2d=False)
    if data.ndim > 1:
        data = data.mean(axis=1)
    data = to_float(data)
    if sr != SAMPLE_RATE:
        data = resample_poly(data, SAMPLE_RATE, sr)
    return to_int16(data)


def save_wav(path: str | Path, pcm: np.ndarray) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(path), to_int16(pcm), SAMPLE_RATE, subtype="PCM_16")


def available_say_voices() -> list[str]:
    """Intersection of our curated list with the voices actually installed on this Mac."""
    try:
        out = subprocess.run(["say", "-v", "?"], capture_output=True, text=True, timeout=20).stdout
    except Exception:
        return ["Samantha", "Daniel", "Karen"]
    installed = {ln.split()[0] for ln in out.splitlines() if ln.strip()}
    return [v for v in SAY_VOICES if v in installed] or ["Samantha"]


def say_to_int16(text: str, voice: str, rate: int | None = None) -> np.ndarray | None:
    """Synthesize `text` with macOS `say` → int16 mono 16 kHz. None on failure."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tf:
        out = tf.name
    cmd = ["say", "-v", voice, "--data-format=LEI16@16000", "--file-format=WAVE", "-o", out]
    if rate:
        cmd += ["-r", str(rate)]
    cmd.append(text)
    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=30)
        return load_wav(out)
    except Exception:
        return None
    finally:
        Path(out).unlink(missing_ok=True)


def rms(x: np.ndarray) -> float:
    xf = to_float(x)
    return float(np.sqrt(np.mean(xf * xf)) + 1e-12)


def trim_silence(pcm: np.ndarray, thresh_db: float = -45.0, pad_ms: int = 60) -> np.ndarray:
    """Trim leading/trailing near-silence so the speech-end offset is meaningful for latency."""
    xf = to_float(pcm)
    win = int(0.01 * SAMPLE_RATE)  # 10 ms
    if len(xf) < win:
        return pcm
    energy = np.array([
        np.sqrt(np.mean(xf[i:i + win] ** 2) + 1e-12) for i in range(0, len(xf) - win, win)
    ])
    thresh = 10 ** (thresh_db / 20.0)
    voiced = np.where(energy > thresh)[0]
    if len(voiced) == 0:
        return pcm
    pad = int(pad_ms / 1000 * SAMPLE_RATE)
    start = max(0, voiced[0] * win - pad)
    end = min(len(pcm), (voiced[-1] + 1) * win + pad)
    return pcm[start:end]
