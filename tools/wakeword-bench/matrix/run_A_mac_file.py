"""Cell A — Mac file inject: PCM → openWakeWord (Mac ONNX Runtime), no microphone.

Usage:
  python -m matrix.run_A_mac_file [--clips matrix/clips_smoke.txt] [--threshold 0.5]
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

from data.audio_io import load_wav
from detectors.oww_detector import OwwDetector
from matrix import RESULTS, load_clip_list, write_matrix_csv, fired_at, DEFAULT_THRESHOLD


def run(clips_path: Path, threshold: float, out: Path) -> Path:
    clips = load_clip_list(clips_path)
    missing = [c for c in clips if not c.path.is_file()]
    if missing:
        raise SystemExit(f"{len(missing)} clips missing (first: {missing[0].path})")
    det = OwwDetector(threshold=threshold, name="openwakeword")
    rows = []
    t0 = time.perf_counter()
    for i, c in enumerate(clips, 1):
        pcm = load_wav(c.path)
        det.reset()
        result = det.process(pcm)
        peak = float(result.score)
        rows.append({
            "label": c.label,
            "category": c.category,
            "condition": c.condition,
            "peak": f"{peak:.3f}",
            "fired": int(fired_at(peak, threshold) or result.fired),
            "duration_ms": int(len(pcm) * 1000 / 16000),
        })
        if i % 20 == 0 or i == len(clips):
            print(f"  A [{i}/{len(clips)}] {c.label} peak={peak:.3f} fired={rows[-1]['fired']}")
    det.close()
    write_matrix_csv(out, rows)
    print(f"A done in {time.perf_counter()-t0:.1f}s → {out}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", type=Path, default=Path("matrix/clips_smoke.txt"))
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    ap.add_argument("--out", type=Path, default=RESULTS / "matrix_A_mac_file.csv")
    a = ap.parse_args()
    run(a.clips, a.threshold, a.out)


if __name__ == "__main__":
    main()
