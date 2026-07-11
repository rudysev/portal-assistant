"""Run every detector over the corpus manifest → results/<detector>.jsonl.

Each detector is reset per clip, then run once; we record fire/no-fire, peak score (for
DET curves), fire offset (for latency), and per-clip processing time (for real-time factor).
Detectors whose assets or keys are missing are skipped with a note (never a crash).

Usage:
  python bench.py [--manifest corpus/test_manifest.jsonl] [--detectors vosk oww sherpa porcupine]
                  [--limit N]
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from data.audio_io import SAMPLE_RATE, load_wav

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "corpus" / "assets"
RESULTS = ROOT / "results"


def build_detectors(which):
    """Construct requested detectors, skipping any whose assets/keys are unavailable.

    Returns a list of (name, detector) — thin adapters over each library's own API.
    """
    dets = []

    if "vosk" in which:
        try:
            from detectors.vosk_detector import VoskDetector
            model_dir = str(ASSETS / "vosk-model")
            if not (ASSETS / "vosk-model" / "am").is_dir():
                print("  skip vosk: model missing (run: python -m data.fetch_assets --vosk)")
            else:
                d = VoskDetector(model_dir, endpoint_silence=0.5, name="vosk")
                dets.append((d.name, d))
        except Exception as e:
            print(f"  skip vosk: {e}")

    if "oww" in which or "openwakeword" in which:
        try:
            from detectors.oww_detector import OwwDetector
            d = OwwDetector(threshold=0.5, name="openwakeword")
            dets.append((d.name, d))
        except Exception as e:
            print(f"  skip openwakeword: {e}")

    if "sherpa" in which:
        try:
            from detectors.sherpa_detector import SherpaKwsDetector
            sd = ASSETS / "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
            kw = ASSETS / "hey_jarvis_keywords.txt"
            if not (sd / "tokens.txt").exists() or not kw.exists():
                print("  skip sherpa: model/keywords missing (run: python -m data.fetch_assets --sherpa)")
            else:
                enc = next(sd.glob("encoder*.onnx"))
                dec = next(sd.glob("decoder*.onnx"))
                joi = next(sd.glob("joiner*.onnx"))
                d = SherpaKwsDetector(
                    tokens=str(sd / "tokens.txt"), encoder=str(enc), decoder=str(dec),
                    joiner=str(joi), keywords_file=str(kw), keywords_threshold=0.25,
                    name="sherpa-kws",
                )
                dets.append((d.name, d))
        except Exception as e:
            print(f"  skip sherpa: {e}")

    if "porcupine" in which:
        try:
            from detectors.porcupine_detector import PorcupineDetector
            d = PorcupineDetector(keyword="jarvis", sensitivity=0.5, name="porcupine[jarvis]")
            dets.append((d.name, d))
        except Exception as e:
            print(f"  skip porcupine: {e}")

    if "livekit" in which:
        try:
            from detectors.livekit_detector import LiveKitDetector
            d = LiveKitDetector(model_path=str(ROOT / "train" / "livekit_hey_jarvis.onnx"))
            dets.append((d.name, d))
        except Exception as e:
            print(f"  skip livekit: {e}")

    if "mww" in which or "microwakeword" in which:
        try:
            from detectors.mww_detector import MicroWakeWordDetector
            d = MicroWakeWordDetector(model_path=str(ROOT / "train" / "mww_hey_jarvis.tflite"))
            dets.append((d.name, d))
        except Exception as e:
            print(f"  skip microwakeword: {e}")

    return dets


def run(manifest_path, which, limit=None):
    rows = [json.loads(ln) for ln in Path(manifest_path).read_text().splitlines() if ln.strip()]
    if limit:
        rows = rows[:limit]
    print(f"Corpus: {len(rows)} clips from {manifest_path}")

    dets = build_detectors(which)
    if not dets:
        print("No detectors available.")
        return
    print(f"Detectors: {', '.join(n for n, _ in dets)}\n")

    RESULTS.mkdir(exist_ok=True)
    # cache decoded audio once (reused across detectors)
    for name, det in dets:
        out = RESULTS / f"{name.replace('/', '_')}.jsonl"
        t0 = time.perf_counter()
        n_fire = 0
        with open(out, "w") as f:
            for i, r in enumerate(rows):
                pcm = load_wav(r["path"])
                det.reset()
                s = time.perf_counter()
                res = det.process(pcm)
                proc_ms = (time.perf_counter() - s) * 1000
                dur_s = len(pcm) / SAMPLE_RATE
                lat_ms = None
                if res.fired and res.offset is not None and r.get("phrase_end") is not None:
                    lat_ms = (res.offset - r["phrase_end"]) / SAMPLE_RATE * 1000
                n_fire += int(res.fired)
                f.write(json.dumps({
                    "detector": name, "path": r["path"], "label": r["label"],
                    "category": r["category"], "condition": r["condition"], "snr": r["snr"],
                    "fired": res.fired, "score": round(res.score, 4), "offset": res.offset,
                    "latency_ms": None if lat_ms is None else round(lat_ms, 1),
                    "dur_s": round(dur_s, 3), "proc_ms": round(proc_ms, 2),
                }) + "\n")
                if (i + 1) % 200 == 0:
                    print(f"  {name}: {i+1}/{len(rows)}")
        try:
            det.close()
        except Exception:
            pass
        el = time.perf_counter() - t0
        print(f"  {name}: {n_fire} fires, {el:.1f}s -> {out.name}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default=str(ROOT / "corpus" / "test_manifest.jsonl"))
    ap.add_argument("--detectors", nargs="*",
                    default=["vosk", "oww", "sherpa", "porcupine"])
    ap.add_argument("--limit", type=int, default=None)
    a = ap.parse_args()
    run(a.manifest, set(a.detectors), a.limit)


if __name__ == "__main__":
    main()
