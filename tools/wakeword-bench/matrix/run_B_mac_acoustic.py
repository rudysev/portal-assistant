"""Cell B — Mac acoustic: afplay clip → Mac default mic → openWakeWord (control cell).

Requires: sounddevice, openwakeword. Sets Mac output volume to ACOUSTIC_VOLUME (65).

Usage:
  python -m matrix.run_B_mac_acoustic [--clips matrix/clips_smoke.txt]
"""
from __future__ import annotations

import argparse
import subprocess
import time
from pathlib import Path

import numpy as np

from detectors.oww_detector import OwwDetector, FRAME_SAMPLES
from matrix import (
    ACOUSTIC_VOLUME,
    DEFAULT_THRESHOLD,
    RESULTS,
    fired_at,
    load_clip_list,
    write_matrix_csv,
)

SAMPLE_RATE = 16_000
# Pad after playback so the detector sees trailing silence / late scores.
POST_PLAY_S = 1.2
# Pre-roll silence so afplay latency doesn't clip the phrase onset.
PRE_ROLL_S = 0.3


def set_volume(pct: int) -> None:
    subprocess.run(
        ["osascript", "-e", f"set volume output volume {int(pct)}"],
        check=False,
        capture_output=True,
    )


def wav_duration_s(path: Path) -> float:
    import soundfile as sf
    info = sf.info(str(path))
    return float(info.duration)


def capture_while_playing(path: Path) -> np.ndarray:
    import sounddevice as sd

    dur = wav_duration_s(path) + PRE_ROLL_S + POST_PLAY_S
    frames = int(dur * SAMPLE_RATE)
    # Start recording, then play.
    rec = sd.rec(frames, samplerate=SAMPLE_RATE, channels=1, dtype="int16")
    time.sleep(PRE_ROLL_S)
    subprocess.run(["afplay", str(path)], check=False)
    time.sleep(POST_PLAY_S)
    sd.wait()
    return rec.reshape(-1)


def run(clips_path: Path, threshold: float, out: Path, volume: int) -> Path:
    clips = load_clip_list(clips_path)
    missing = [c for c in clips if not c.path.is_file()]
    if missing:
        raise SystemExit(f"{len(missing)} clips missing (first: {missing[0].path})")
    set_volume(volume)
    print(f"B Mac acoustic — volume={volume}%  clips={len(clips)}  thr={threshold}")
    det = OwwDetector(threshold=threshold, name="openwakeword")
    rows = []
    for i, c in enumerate(clips, 1):
        pcm = capture_while_playing(c.path)
        # Trim to whole frames for the detector
        n = (len(pcm) // FRAME_SAMPLES) * FRAME_SAMPLES
        pcm = pcm[:n] if n else pcm
        det.reset()
        result = det.process(pcm.astype(np.int16))
        peak = float(result.score)
        rows.append({
            "label": c.label,
            "category": c.category,
            "condition": c.condition,
            "peak": f"{peak:.3f}",
            "fired": int(fired_at(peak, threshold) or result.fired),
            "duration_ms": int(len(pcm) * 1000 / SAMPLE_RATE),
        })
        print(f"  B [{i}/{len(clips)}] {c.label} peak={peak:.3f} fired={rows[-1]['fired']}")
    det.close()
    write_matrix_csv(out, rows)
    print(f"B done → {out}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", type=Path, default=Path("matrix/clips_smoke.txt"))
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    ap.add_argument("--out", type=Path, default=RESULTS / "matrix_B_mac_acoustic.csv")
    ap.add_argument("--volume", type=int, default=ACOUSTIC_VOLUME)
    a = ap.parse_args()
    run(a.clips, a.threshold, a.out, a.volume)


if __name__ == "__main__":
    main()
