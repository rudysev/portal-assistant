"""Analyze the 2×2 recall isolation matrix → results/MATRIX.md + matrix_summary.json.

Reads:
  results/matrix_A_mac_file.csv
  results/matrix_B_mac_acoustic.csv
  results/matrix_C_portal_file.csv
  results/matrix_D_portal_acoustic.csv

Usage:
  python -m matrix.analyze_matrix [--threshold 0.5]
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

from matrix import DEFAULT_THRESHOLD, RESULTS

CELLS = [
    ("A", "mac_file", "matrix_A_mac_file.csv", "Mac file inject (model + Mac ORT)"),
    ("B", "mac_acoustic", "matrix_B_mac_acoustic.csv", "Mac acoustic (control)"),
    ("C", "portal_file", "matrix_C_portal_file.csv", "Portal file inject (model + Android ORT)"),
    ("D", "portal_acoustic", "matrix_D_portal_acoustic.csv", "Portal acoustic (mic + FX + model)"),
]


def load(path: Path, threshold: float) -> list[dict]:
    if not path.exists():
        return []
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            peak = float(r.get("peak") or 0)
            fired = r.get("fired", "")
            if fired == "":
                fired_b = peak >= threshold
            else:
                fired_b = str(fired).strip() in ("1", "true", "True")
            rows.append({
                "label": r.get("label", ""),
                "category": r.get("category", ""),
                "condition": r.get("condition", ""),
                "peak": peak,
                "fired": fired_b,
                "duration_ms": int(float(r["duration_ms"])) if r.get("duration_ms") else 0,
            })
    return rows


def metrics(rows: list[dict]) -> dict:
    if not rows:
        return {"n": 0, "recall": None, "near_fa": None, "bg_fa_per_hr": None, "n_pos": 0, "n_near": 0}
    pos = [r for r in rows if r["category"] == "positive"]
    near = [r for r in rows if r["category"] == "near_miss"]
    bg = [r for r in rows if r["category"] == "background"]
    recall = (sum(1 for r in pos if r["fired"]) / len(pos)) if pos else None
    near_fa = (sum(1 for r in near if r["fired"]) / len(near)) if near else None
    bg_ms = sum(r["duration_ms"] for r in bg)
    bg_fires = sum(1 for r in bg if r["fired"])
    hours = bg_ms / 3_600_000.0 if bg_ms else 0.0
    bg_fa_hr = (bg_fires / hours) if hours > 0 else (0.0 if bg else None)
    by_cond = {}
    for c in sorted({r["condition"] for r in pos}):
        rs = [r for r in pos if r["condition"] == c]
        by_cond[c] = round(100 * sum(1 for r in rs if r["fired"]) / len(rs), 1) if rs else None
    return {
        "n": len(rows),
        "n_pos": len(pos),
        "n_near": len(near),
        "recall": None if recall is None else round(100 * recall, 1),
        "near_fa": None if near_fa is None else round(100 * near_fa, 1),
        "bg_fa_per_hr": None if bg_fa_hr is None else round(bg_fa_hr, 2),
        "by_cond": by_cond,
    }


def pct(x):
    return "—" if x is None else f"{x:.1f}%"


def delta(a, b):
    if a is None or b is None:
        return None
    return round(a - b, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    ap.add_argument("--results", type=Path, default=RESULTS)
    a = ap.parse_args()
    a.results.mkdir(parents=True, exist_ok=True)

    cell_metrics = {}
    lines = []
    lines.append("# Wake recall isolation matrix")
    lines.append("")
    lines.append(f"Threshold **{a.threshold}**. Same clip list across cells that have data.")
    lines.append("")
    lines.append("| Cell | Path | Recall | Near-miss FA | BG FA/hr | n |")
    lines.append("|------|------|-------:|-------------:|---------:|--:|")
    for key, slug, fname, desc in CELLS:
        m = metrics(load(a.results / fname, a.threshold))
        cell_metrics[key] = {"slug": slug, "desc": desc, "file": fname, **m}
        if m["n"] == 0:
            lines.append(f"| **{key}** | {desc} | — | — | — | 0 |")
        else:
            bg = "—" if m["bg_fa_per_hr"] is None else f"{m['bg_fa_per_hr']:.2f}"
            lines.append(
                f"| **{key}** | {desc} | {pct(m['recall'])} | {pct(m['near_fa'])} | {bg} | {m['n']} |"
            )

    A, C, D = cell_metrics["A"], cell_metrics["C"], cell_metrics["D"]
    lines.append("")
    lines.append("## Deltas (how to read)")
    lines.append("")
    lines.append("| Delta | Meaning | Value (recall pp) |")
    lines.append("|-------|---------|------------------:|")
    d_ac = delta(A.get("recall"), C.get("recall"))
    d_cd = delta(C.get("recall"), D.get("recall"))
    d_ad = delta(A.get("recall"), D.get("recall"))
    lines.append(f"| **A − C** | Runtime / port fidelity (Mac ORT vs Android ORT) | {d_ac if d_ac is not None else '—'} |")
    lines.append(f"| **C − D** | Portal mic + audio FX + room/speaker | {d_cd if d_cd is not None else '—'} |")
    lines.append(f"| **A − D** | End-to-end (model ceiling vs product path) | {d_ad if d_ad is not None else '—'} |")
    lines.append("")
    lines.append("- **A ≈ C** → model ceiling is trustworthy on-device; don't blame Android ORT.")
    lines.append("- **C ≫ D** → fix capture / ducking / placement before training a new head.")
    lines.append("- **A ≫ C** → asset mismatch, frame format, or Android path bug — fix before training.")
    lines.append("- **A ≈ D** → model is the bottleneck even with a good mic path — train / phrase / architecture.")
    lines.append("")
    lines.append("Cell **B** (Mac acoustic) is a control only — not a Portal product proxy.")
    lines.append("")

    # Per-condition for A/C/D when present
    lines.append("## Positive recall by condition")
    lines.append("")
    conds = sorted({c for m in cell_metrics.values() for c in (m.get("by_cond") or {})})
    if conds:
        header = "| Cell | " + " | ".join(conds) + " |"
        sep = "|------|" + "|".join(["------:"] * len(conds)) + "|"
        lines.append(header)
        lines.append(sep)
        for key, _, _, _ in CELLS:
            m = cell_metrics[key]
            if m["n"] == 0:
                continue
            cells = [pct(m["by_cond"].get(c)) for c in conds]
            lines.append(f"| {key} | " + " | ".join(cells) + " |")
        lines.append("")

    md_path = a.results / "MATRIX.md"
    md_path.write_text("\n".join(lines) + "\n")
    summary = {
        "threshold": a.threshold,
        "cells": cell_metrics,
        "deltas": {"A_minus_C": d_ac, "C_minus_D": d_cd, "A_minus_D": d_ad},
    }
    json_path = a.results / "matrix_summary.json"
    json_path.write_text(json.dumps(summary, indent=2) + "\n")
    print("\n".join(lines))
    print(f"\nwrote {md_path}")
    print(f"wrote {json_path}")


if __name__ == "__main__":
    main()
