"""Build canonical matrix clip lists from the test corpus manifest.

Format (one clip per line):
  label|category|condition|relpath

  label      — basename of the WAV (stable id across cells)
  category   — positive | near_miss | background | silence
  condition  — clean | reverb | quiet | noisy20 | …
  relpath    — path relative to tools/wakeword-bench/

Usage:
  python -m matrix.build_clip_list              # smoke → matrix/clips_smoke.txt
  python -m matrix.build_clip_list --full       # full  → matrix/clips_full.txt
  python -m matrix.build_clip_list --quick      # tiny  → matrix/clips_quick.txt
"""
from __future__ import annotations

import argparse
import json
import random
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "corpus" / "test_manifest.jsonl"
OUT_DIR = ROOT / "matrix"

# Smoke: stratified caps (matches the historical device_clips scale ~97).
SMOKE_CAPS = {
    "positive": 42,
    "near_miss": 45,
    "background": 10,
    "silence": 0,
}


def load_manifest(path: Path) -> list[dict]:
    rows = []
    for ln in path.read_text().splitlines():
        if not ln.strip():
            continue
        rows.append(json.loads(ln))
    return rows


def row_to_line(r: dict) -> str:
    p = Path(r["path"])
    try:
        rel = p.resolve().relative_to(ROOT)
    except ValueError:
        rel = Path("corpus/test") / r.get("category", "positive") / p.name
    cat = r.get("category") or ("positive" if r.get("label") == 1 else "near_miss")
    cond = r.get("condition") or "clean"
    return f"{p.name}|{cat}|{cond}|{rel.as_posix()}"


def stratified_sample(rows: list[dict], caps: dict[str, int], seed: int) -> list[dict]:
    rng = random.Random(seed)
    by_cat: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        cat = r.get("category") or ("positive" if r.get("label") == 1 else "near_miss")
        by_cat[cat].append(r)
    out = []
    for cat, cap in caps.items():
        pool = by_cat.get(cat, [])
        rng.shuffle(pool)
        out.extend(pool[:cap] if cap > 0 else [])
    # Keep positives grouped by condition for readability
    out.sort(key=lambda r: (r.get("category", ""), r.get("condition", ""), Path(r["path"]).name))
    return out


def write_list(rows: list[dict], dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    lines = [row_to_line(r) for r in rows]
    dest.write_text("\n".join(lines) + ("\n" if lines else ""))
    print(f"wrote {len(lines)} clips → {dest.relative_to(ROOT)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", type=Path, default=MANIFEST)
    ap.add_argument("--full", action="store_true", help="all manifest clips")
    ap.add_argument("--quick", action="store_true", help="tiny list for wiring tests")
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    if not a.manifest.exists():
        raise SystemExit(f"manifest missing: {a.manifest} (run: python -m data.manifest --role test)")
    rows = load_manifest(a.manifest)
    if a.full:
        write_list(rows, OUT_DIR / "clips_full.txt")
    elif a.quick:
        caps = {"positive": 6, "near_miss": 6, "background": 2, "silence": 0}
        write_list(stratified_sample(rows, caps, a.seed), OUT_DIR / "clips_quick.txt")
    else:
        write_list(stratified_sample(rows, SMOKE_CAPS, a.seed), OUT_DIR / "clips_smoke.txt")


if __name__ == "__main__":
    main()
