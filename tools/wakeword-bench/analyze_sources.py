"""Compare capture configs on device: voicerec / mic / unprocessed / voicerec+gain.

Reads results/device_<config>.csv (label,category,condition,peak,fired,peak_rms) and prints, per config:
recall@0.3, near-miss FA, background FA, median positive peak score, median positive peak RMS, plus a
small threshold sweep. Emits results/source_summary.json for a dashboard.
"""
import csv, json, os, statistics
from collections import defaultdict

R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
CONFIGS = ["voicerec", "mic", "voicerec_gain15", "voicerec_gain2", "voicerec_gain3"]
CONDS = ["clean", "reverb", "quiet", "noisy20", "noisy10", "noisy5", "noisy0"]
THR = 0.3

def load(cfg):
    p = os.path.join(R, f"device_{cfg}.csv")
    return list(csv.DictReader(open(p))) if os.path.exists(p) else None

def pct(x): return round(100 * x, 1)

summary = {"thr": THR, "configs": []}
print(f"\n=== capture-config A/B (on device, {THR} threshold) ===\n")
print(f"{'config':<16}{'recall':>8}{'nearFA':>8}{'bgFA':>7}{'medScore':>10}{'medRMS':>8}")
for cfg in CONFIGS:
    rows = load(cfg)
    if not rows:
        print(f"{cfg:<16}  (no data yet)"); continue
    pos = [r for r in rows if r["category"] == "positive"]
    nm = [r for r in rows if r["category"] == "near_miss"]
    bg = [r for r in rows if r["category"] == "background"]
    fired = lambda r: float(r["peak"]) >= THR
    recall = pct(sum(fired(r) for r in pos) / max(1, len(pos)))
    near_fa = pct(sum(fired(r) for r in nm) / max(1, len(nm)))
    bg_fa = pct(sum(fired(r) for r in bg) / max(1, len(bg)))
    med_score = round(statistics.median([float(r["peak"]) for r in pos]), 3)
    rmss = [int(r["peak_rms"]) for r in pos if r.get("peak_rms", "0").isdigit()]
    med_rms = int(statistics.median(rmss)) if rmss else 0
    by_cond = {}
    for c in CONDS:
        rs = [r for r in pos if r["condition"] == c]
        if rs: by_cond[c] = pct(sum(fired(r) for r in rs) / len(rs))
    sweep = []
    for i in range(1, 20):
        t = i / 20.0
        sweep.append({"thr": round(t, 2),
                      "recall": pct(sum(float(r["peak"]) >= t for r in pos) / len(pos)),
                      "near_fa": pct(sum(float(r["peak"]) >= t for r in nm) / len(nm)),
                      "bg_fa": pct(sum(float(r["peak"]) >= t for r in bg) / len(bg))})
    summary["configs"].append({"name": cfg, "recall": recall, "near_fa": near_fa, "bg_fa": bg_fa,
                               "med_score": med_score, "med_rms": med_rms, "by_cond": by_cond, "sweep": sweep,
                               "n_pos": len(pos)})
    print(f"{cfg:<16}{recall:>7}%{near_fa:>7}%{bg_fa:>6}%{med_score:>10}{med_rms:>8}")

print("\n=== recall by condition ===")
print(f"{'config':<16}" + "".join(f"{c:>9}" for c in CONDS))
for c in summary["configs"]:
    print(f"{c['name']:<16}" + "".join(f"{c['by_cond'].get(cond,'-'):>8}%" if cond in c['by_cond'] else f"{'-':>9}" for cond in CONDS))

json.dump(summary, open(os.path.join(R, "source_summary.json"), "w"), indent=2)
print(f"\nwrote {os.path.join(R,'source_summary.json')}")
