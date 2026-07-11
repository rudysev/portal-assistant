"""Shared helpers for the 2×2 recall isolation matrix."""
from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
DEFAULT_THRESHOLD = 0.5
# Acoustic cells: Mac speaker output volume (0–100). Default 100 for max SNR into the mic.
ACOUSTIC_VOLUME = 100


@dataclass
class Clip:
    label: str
    category: str
    condition: str
    relpath: str

    @property
    def path(self) -> Path:
        return ROOT / self.relpath


def load_clip_list(path: Path) -> list[Clip]:
    clips = []
    for ln in path.read_text().splitlines():
        ln = ln.strip()
        if not ln or ln.startswith("#"):
            continue
        parts = ln.split("|")
        if len(parts) == 4:
            label, category, condition, relpath = parts
        elif len(parts) == 3:
            # legacy device_bench format: abspath|category|condition
            abspath, category, condition = parts
            p = Path(abspath)
            label = p.name
            try:
                relpath = p.resolve().relative_to(ROOT).as_posix()
            except ValueError:
                relpath = abspath
        else:
            raise ValueError(f"bad clip line: {ln}")
        clips.append(Clip(label, category, condition, relpath))
    return clips


def write_matrix_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["label", "category", "condition", "peak", "fired", "duration_ms"]
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fields})


def fired_at(peak: float, threshold: float = DEFAULT_THRESHOLD) -> bool:
    return peak >= threshold
