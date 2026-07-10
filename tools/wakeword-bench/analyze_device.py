"""Compare on-device (Portal mic, acoustic) results: openWakeWord vs Vosk, and device vs Mac.

Inputs (same 97-clip subset):
  results/device_oww.csv   — openWakeWord peak score per clip (bench mode)
  results/device_vosk.csv  — Vosk fired/not per clip (shipped config)
  results/openwakeword.jsonl, results/vosk.jsonl — the Mac run (per-clip, joined by filename)

Emits results/device_summary.json (for the dashboard) and prints tables.
"""
import csv, json, os, statistics
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
R = os.path.join(HERE, "results")
CONDS = ["clean", "reverb", "quiet", "noisy20", "noisy10", "noisy5", "noisy0"]
OWW_THRESH = 0.3  # shipped default

def load_csv(name):
    return list(csv.DictReader(open(os.path.join(R, name))))

def load_mac(name):
    out = {}
    for ln in open(os.path.join(R, name)):
        r = json.loads(ln)
        out[os.path.basename(r["path"])] = r
    return out

oww = load_csv("device_oww.csv")
vosk = load_csv("device_vosk.csv")
mac_oww = load_mac("openwakeword.jsonl")
mac_vosk = load_mac("vosk.jsonl")

def cat(rows, c): return [r for r in rows if r["category"] == c]
def pct(x): return round(100 * x, 1)

# ---- oww device: fire = peak >= threshold ----
def oww_fired(r, thr=OWW_THRESH): return float(r["peak"]) >= thr

summary = {"n_clips": len(oww), "oww_thresh": OWW_THRESH, "conditions": CONDS}

# per-condition recall (device)
def recall_by_cond(rows, fired_fn):
    out = {}
    for c in CONDS:
        rs = [r for r in rows if r["category"] == "positive" and r["condition"] == c]
        if rs:
            out[c] = pct(sum(fired_fn(r) for r in rs) / len(rs))
    return out

summary["oww_recall_by_cond"] = recall_by_cond(oww, oww_fired)
summary["vosk_recall_by_cond"] = recall_by_cond(vosk, lambda r: r["fired"] == "1")

# Mac recall on the SAME 97 clips (join by filename)
def mac_recall_by_cond(mac):
    out = {}
    for c in CONDS:
        rs = [r for r in oww if r["category"] == "positive" and r["condition"] == c]
        hit = tot = 0
        for r in rs:
            m = mac.get(r["label"])
            if m:
                tot += 1; hit += 1 if m["fired"] else 0
        if tot:
            out[c] = pct(hit / tot)
    return out

summary["oww_recall_by_cond_mac"] = mac_recall_by_cond(mac_oww)
summary["vosk_recall_by_cond_mac"] = mac_recall_by_cond(mac_vosk)

# overall recall / near-miss FA / background FA
def stats(rows, fired_fn):
    pos = [r for r in rows if r["category"] == "positive"]
    nm = [r for r in rows if r["category"] == "near_miss"]
    bg = [r for r in rows if r["category"] == "background"]
    return {
        "recall": pct(sum(fired_fn(r) for r in pos) / max(1, len(pos))),
        "near_fa": pct(sum(fired_fn(r) for r in nm) / max(1, len(nm))),
        "bg_fa": pct(sum(fired_fn(r) for r in bg) / max(1, len(bg))),
        "n_pos": len(pos), "n_nm": len(nm), "n_bg": len(bg),
    }

summary["oww_device"] = stats(oww, oww_fired)
summary["vosk_device"] = stats(vosk, lambda r: r["fired"] == "1")
# Mac same-97 overall
summary["oww_mac"] = stats(
    [{**r, "_m": mac_oww.get(r["label"])} for r in oww],
    lambda r: bool(r["_m"] and r["_m"]["fired"]),
)
summary["vosk_mac"] = stats(
    [{**r, "_m": mac_vosk.get(r["label"])} for r in vosk],
    lambda r: bool(r["_m"] and r["_m"]["fired"]),
)

# ---- oww device threshold sweep (recall vs near-miss FA vs bg FA) ----
sweep = []
pos = [r for r in oww if r["category"] == "positive"]
nm = [r for r in oww if r["category"] == "near_miss"]
bg = [r for r in oww if r["category"] == "background"]
for i in range(1, 20):
    thr = i / 20.0
    sweep.append({
        "thr": round(thr, 2),
        "recall": pct(sum(float(r["peak"]) >= thr for r in pos) / len(pos)),
        "near_fa": pct(sum(float(r["peak"]) >= thr for r in nm) / len(nm)),
        "bg_fa": pct(sum(float(r["peak"]) >= thr for r in bg) / len(bg)),
    })
summary["oww_sweep"] = sweep

# ---- device vs Mac score deviation (oww, per clip, same file) ----
dev = []
for r in oww:
    m = mac_oww.get(r["label"])
    if m:
        dev.append({"label": r["label"], "category": r["category"], "condition": r["condition"],
                    "device": round(float(r["peak"]), 3), "mac": round(float(m["score"]), 3)})
summary["oww_dev_vs_mac"] = dev
# median device/mac score for positives
pos_dev = [d for d in dev if d["category"] == "positive"]
summary["oww_pos_median_device"] = round(statistics.median([d["device"] for d in pos_dev]), 3)
summary["oww_pos_median_mac"] = round(statistics.median([d["mac"] for d in pos_dev]), 3)

# top near-miss offenders on device (oww peak)
nm_sorted = sorted(nm, key=lambda r: -float(r["peak"]))
summary["oww_top_near_miss"] = [
    {"label": r["label"], "condition": r["condition"], "peak": round(float(r["peak"]), 3)}
    for r in nm_sorted[:8]
]

json.dump(summary, open(os.path.join(R, "device_summary.json"), "w"), indent=2)

# ---- print ----
print(f"\n=== ON-DEVICE (Portal mic, acoustic) — {summary['n_clips']} clips ===\n")
print(f"{'detector':<16}{'recall':>9}{'nearFA':>9}{'bgFA':>8}")
for k, name in [("oww_device", f"oww@{OWW_THRESH} dev"), ("vosk_device", "vosk dev"),
                ("oww_mac", "oww Mac(same)"), ("vosk_mac", "vosk Mac(same)")]:
    s = summary[k]
    print(f"{name:<16}{s['recall']:>8}%{s['near_fa']:>8}%{s['bg_fa']:>7}%")

print("\n=== recall by condition (device) ===")
print(f"{'':<10}" + "".join(f"{c:>9}" for c in CONDS))
for k, name in [("oww_recall_by_cond", "oww dev"), ("vosk_recall_by_cond", "vosk dev"),
                ("oww_recall_by_cond_mac", "oww Mac")]:
    d = summary[k]
    print(f"{name:<10}" + "".join(f"{d.get(c,'-'):>8}%" if c in d else f"{'-':>9}" for c in CONDS))

print(f"\noww positive median score: device={summary['oww_pos_median_device']} vs Mac={summary['oww_pos_median_mac']}")
print("\noww device threshold sweep (recall / nearFA / bgFA):")
for s in summary["oww_sweep"]:
    if s["thr"] in (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7):
        print(f"  thr={s['thr']:.2f}  recall={s['recall']:>5}%  nearFA={s['near_fa']:>5}%  bgFA={s['bg_fa']:>4}%")
print("\ntop near-miss offenders on device:")
for r in summary["oww_top_near_miss"]:
    print(f"  {r['peak']:.3f}  {r['label']}")
print(f"\nwrote {os.path.join(R,'device_summary.json')}")
