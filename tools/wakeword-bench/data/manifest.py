"""Build the labeled test corpus + manifest.jsonl (the single input to bench.py).

Orchestrates gen_positives + gen_negatives, writes every clip under corpus/<role>/, and
emits corpus/<role>_manifest.jsonl (one JSON row per clip).

  role=test  (default) — the held-out benchmark set.
  role=train           — a DISJOINT set (different seed/voices) for microWakeWord/livekit
                         training, so no test clip is ever seen in training.

Usage:
  python -m data.manifest --role test  [--quick] [--voices Samantha Daniel ...] [--seed 7]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from . import augment, gen_negatives, gen_positives
from .audio_io import available_say_voices

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "corpus"


def build(role="test", voices=None, seed=7, quick=False, large=False, verbose=True):
    augment.set_seed(seed)
    out_dir = CORPUS / role
    all_voices = voices or available_say_voices()
    if quick:
        all_voices = all_voices[:3]

    if verbose:
        print(f"Building '{role}' corpus with {len(all_voices)} voices (seed={seed}"
              f"{', large' if large else ''}{', quick' if quick else ''})…")
    # Near-miss precision is dominated by the confusable phrase, not voice/rate coverage —
    # cap voices and use one rate to keep the corpus (and bench time) bounded.
    # --large: denser positives (multi-phrase × rates) + more near-miss voice/rate coverage.
    nm_voices = all_voices if quick else (all_voices[:12] if large else all_voices[:8])
    nm_rates = (None,) if not large else (None, 180, 220)
    pos_phrases = None if quick else (gen_positives.POSITIVE_PHRASES if large else ["hey jarvis"])
    rows = []
    rows += gen_positives.generate(
        out_dir, voices=all_voices, phrases=pos_phrases,
        max_base=(6 if quick else None), verbose=verbose,
    )
    rows += gen_negatives.gen_near_misses(
        out_dir, voices=nm_voices, rates=nm_rates,
        max_base=(6 if quick else None), verbose=verbose,
    )
    rows += gen_negatives.gen_background(
        out_dir, voices=all_voices,
        max_voices=(3 if quick else (None if large else 8)),
        verbose=verbose,
    )
    rows += gen_negatives.gen_silence(out_dir, n=(4 if quick else (40 if large else 20)), verbose=verbose)

    manifest = CORPUS / f"{role}_manifest.jsonl"
    with open(manifest, "w") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")

    pos = sum(1 for r in rows if r["label"] == 1)
    neg = len(rows) - pos
    if verbose:
        print(f"\n{len(rows)} clips  ({pos} positive / {neg} negative)  ->  {manifest}")
    return manifest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--role", default="test", choices=["test", "train"])
    ap.add_argument("--voices", nargs="*", default=None)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--quick", action="store_true", help="tiny corpus for a smoke test")
    ap.add_argument("--large", action="store_true",
                    help="thousands of clips: multi-phrase positives + denser near-miss/background")
    a = ap.parse_args()
    # train role uses a different default seed so it's disjoint from test
    seed = a.seed if a.role == "test" else a.seed + 100
    build(role=a.role, voices=a.voices, seed=seed, quick=a.quick, large=a.large)


if __name__ == "__main__":
    main()
