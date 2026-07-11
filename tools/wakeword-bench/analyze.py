"""Aggregate results/*.jsonl into a comparison report.

Metrics per detector (at its configured operating point):
  - Detection rate (recall) overall and per condition (clean/reverb/quiet/noisy@SNR)
  - Near-miss false-accept rate (% of confusable-phrase clips that fired)
  - Background false-accepts per hour (fires on non-wake speech ÷ hours of it)
  - Latency P50/P90 (fire offset − phrase-end), on positives that fired
  - Real-time factor (processing time ÷ audio duration; lower = cheaper)

For detectors that expose a continuous score (e.g. openWakeWord), also sweeps the score
threshold to report recall at common FA/hour targets (the only fair cross-library point).

Prints a table and writes results/summary.md. TTS-bias caveat printed up top.
"""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / "results"

CAVEAT = (
    "NOTE: positives are TTS (macOS `say`). openWakeWord's `hey_jarvis` was trained on "
    "Piper TTS; `say` is a different distribution, so no detector is strictly in-distribution "
    "here, but neural-TTS test audio still differs from real room speech. Treat as relative, "
    "not absolute. Porcupine detects the built-in keyword `jarvis` (not the phrase 'hey jarvis')."
)


def pct(x):
    return f"{100 * x:.1f}%"


def load():
    data = defaultdict(list)
    for f in RESULTS.glob("*.jsonl"):
        for ln in f.read_text().splitlines():
            if ln.strip():
                r = json.loads(ln)
                data[r["detector"]].append(r)
    return data


def summarize(rows):
    pos = [r for r in rows if r["label"] == 1]
    near = [r for r in rows if r["category"] == "near_miss"]
    bg = [r for r in rows if r["category"] in ("background", "silence")]

    recall = sum(r["fired"] for r in pos) / max(1, len(pos))

    by_cond = defaultdict(lambda: [0, 0])
    for r in pos:
        tag = r["condition"] if r["snr"] is None else f'{r["condition"]}@{r["snr"]}dB'
        by_cond[tag][0] += int(r["fired"])
        by_cond[tag][1] += 1

    near_fa = sum(r["fired"] for r in near) / max(1, len(near))
    bg_hours = sum(r["dur_s"] for r in bg) / 3600.0
    bg_fires = sum(r["fired"] for r in bg)
    fa_per_hour = bg_fires / bg_hours if bg_hours > 0 else 0.0

    lats = sorted(r["latency_ms"] for r in pos if r["fired"] and r["latency_ms"] is not None)
    p50 = lats[len(lats) // 2] if lats else None
    p90 = lats[int(len(lats) * 0.9)] if lats else None

    rtf = [r["proc_ms"] / (r["dur_s"] * 1000) for r in rows if r["dur_s"] > 0]
    rtf_med = sorted(rtf)[len(rtf) // 2] if rtf else None

    # A detector exposes a usable continuous score only if its FIRED clips carry varied
    # scores (true for openWakeWord's peak score). Binary detectors (vosk/sherpa/porcupine)
    # set fired→score==1.0 exactly, so their "score" is not a threshold-sweepable signal —
    # their real operating point is fixed and they're plotted as a single point.
    fired_scores = {r["score"] for r in pos if r["fired"]}
    continuous = len(fired_scores) > 1

    return {
        "n_pos": len(pos), "recall": recall, "by_cond": dict(by_cond),
        "near_fa": near_fa, "n_near": len(near),
        "fa_per_hour": fa_per_hour, "bg_hours": bg_hours, "bg_fires": bg_fires,
        "p50": p50, "p90": p90, "rtf_med": rtf_med,
        "continuous": continuous,
    }


def sweep_curve(rows, targets=(2.0, 1.0, 0.5, 0.1)):
    """For continuous-score detectors: recall at given FA/hour targets via threshold sweep."""
    pos = [r for r in rows if r["label"] == 1]
    bg = [r for r in rows if r["category"] in ("background", "silence")]
    bg_hours = sum(r["dur_s"] for r in bg) / 3600.0
    if bg_hours == 0 or not pos:
        return {}
    out = {}
    for target in targets:
        best_recall = 0.0
        best_thr = None
        for thr in [i / 100 for i in range(1, 100)]:
            fa = sum(1 for r in bg if r["score"] >= thr) / bg_hours
            if fa <= target:
                rec = sum(1 for r in pos if r["score"] >= thr) / len(pos)
                if rec > best_recall:
                    best_recall, best_thr = rec, thr
        out[target] = (best_recall, best_thr)
    return out


def main():
    data = load()
    if not data:
        print("No results found. Run bench.py first.")
        return

    lines = []
    def out(s=""):
        print(s)
        lines.append(s)

    out("# Wake-word detector benchmark — summary\n")
    out(CAVEAT + "\n")
    out(f"{'detector':<20} {'recall':>8} {'nearFA':>8} {'FA/hr':>8} "
        f"{'latP50':>8} {'latP90':>8} {'RTF':>7}")
    out("-" * 72)
    summaries = {}
    for name, rows in sorted(data.items()):
        s = summarize(rows)
        summaries[name] = s
        out(f"{name:<20} {pct(s['recall']):>8} {pct(s['near_fa']):>8} "
            f"{s['fa_per_hour']:>8.2f} "
            f"{('%dms'%s['p50']) if s['p50'] is not None else '-':>8} "
            f"{('%dms'%s['p90']) if s['p90'] is not None else '-':>8} "
            f"{('%.3f'%s['rtf_med']) if s['rtf_med'] is not None else '-':>7}")

    out("\n## Detection rate by condition\n")
    conds = sorted({c for s in summaries.values() for c in s["by_cond"]})
    header = f"{'detector':<20}" + "".join(f"{c:>12}" for c in conds)
    out(header)
    out("-" * len(header))
    for name, s in summaries.items():
        row = f"{name:<20}"
        for c in conds:
            hit, tot = s["by_cond"].get(c, (0, 0))
            row += f"{(pct(hit/tot) if tot else '-'):>12}"
        out(row)

    # operating curves for continuous-score detectors
    curves = {n: sweep_curve(r) for n, r in data.items() if summaries[n]["continuous"]}
    if curves:
        out("\n## Recall at common FA/hour targets (continuous-score detectors)\n")
        targets = (2.0, 1.0, 0.5, 0.1)
        out(f"{'detector':<20}" + "".join(f"{'≤%.1f/hr'%t:>12}" for t in targets))
        for name, cv in curves.items():
            row = f"{name:<20}"
            for t in targets:
                rec, thr = cv.get(t, (0.0, None))
                row += f"{(pct(rec) if thr else '-'):>12}"
            out(row)

    out("\n## Notes")
    for name, s in summaries.items():
        out(f"- {name}: {s['n_pos']} positives, {s['n_near']} near-miss clips, "
            f"{s['bg_hours']*60:.1f} min background ({s['bg_fires']} FA).")

    (RESULTS / "summary.md").write_text("\n".join(lines) + "\n")
    print(f"\nWrote {RESULTS/'summary.md'}")


if __name__ == "__main__":
    main()
