"""Build eval/REPORT.md from eval/results/*.json(l). Every number comes from those files.

Usage: uv run --project backend python eval/report.py [--deployed claude-opus-5-5] [--cheap claude-haiku-5-5]
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(EVAL_DIR))
from common import LABELS, RESULTS, load_json  # noqa: E402

NP_FIELDS = ("make", "model", "serial", "manufacture_date")
DM_FIELDS = ("damage_type", "material", "surface")
# A typical water-loss walk-through, used to turn per-frame costs into cost per job.
TYPICAL_JOB = {"nameplate": 3, "damage": 6, "note": 6}


def jsonl(name: str) -> list[dict]:
    p = RESULTS / name
    return [json.loads(x) for x in p.read_text(encoding="utf-8").splitlines() if x.strip()] if p.exists() else []


def pct(x) -> str:
    return "—" if x is None else f"{100 * x:.0f}%"


def acc(rows: list[dict], field: str) -> tuple[float | None, int]:
    vals = [r["scores"][field]["correct"] for r in rows if field in (r.get("scores") or {})]
    return (sum(vals) / len(vals) if vals else None), len(vals)


def section_testset(rows, qc) -> str:
    frames = {r["key"]: r for r in rows}
    by = defaultdict(int)
    for r in frames.values():
        by[(r["purpose"], r["condition"] or "?")] += 1
    lines = ["## 1. Test set", ""]
    if qc:
        c = qc["counts"]
        lines += [
            f"- **{c['frames']} labelled frames** from **{c['scenes']} physical scenes** (frames the phone's quality gate actually judged, saved in dataset mode).",
            f"- Frame labels: " + ", ".join(f"{k} {v}" for k, v in c["by_label"].items()) + ".",
            f"- Low-light frames: **{c['low_light_frames']}**.",
        ]
    lines += ["", "| Purpose | Condition | Frames scored for extraction |", "|---|---|---|"]
    lines += [f"| {p} | {cond} | {n} |" for (p, cond), n in sorted(by.items())]
    lines += ["", "Split: by scene (`common.split_of`); thresholds and calibration fitted on **tune**, all accuracy below is on **test** unless marked.", ""]
    return "\n".join(lines)


def section_qc(qc) -> str:
    if not qc:
        return "## 2. Capture quality gate\n\n_No labelled frames yet._\n"
    t, s = qc["thresholds"], qc["seed"]
    rows = "\n".join(f"| `{k}` | {s[k]} | **{t[k]}** | {json.dumps(qc['fits'].get(k, {}))} |" for k in t)
    te, ts = qc["test"]["fitted"], qc["test"]["seed"]
    return f"""## 2. Capture quality gate (on device)

Operating point: {qc['operating_point']}. Metrics are the phone's own numbers for each frame.

| Threshold | Seed (pre-data) | Measured | Fit details (tune scenes) |
|---|---|---|---|
{rows}

**Held-out test scenes:**

| Gate | Bad frames caught | False retakes on usable frames | Precision of a retake prompt | Per reason |
|---|---|---|---|---|
| Seed thresholds | {pct(ts['recall_bad'])} | {pct(ts['false_retake_rate'])} | {pct(ts['precision_retake'])} | {ts['per_reason_recall']} |
| **Measured thresholds** | **{pct(te['recall_bad'])}** | **{pct(te['false_retake_rate'])}** | **{pct(te['precision_retake'])}** | {te['per_reason_recall']} |

Median metrics by label: {json.dumps(qc['medians_by_label'])}
"""


def section_extraction(rows, models) -> str:
    test = [r for r in rows if r["split"] == "test" and "scores" in r]
    out = ["## 3. Extraction accuracy per field (test scenes)", ""]
    hdr = "| Model | Slice | " + " | ".join(NP_FIELDS) + " | " + " | ".join(DM_FIELDS) + " |"
    out += [hdr, "|" + "---|" * (2 + len(NP_FIELDS) + len(DM_FIELDS))]
    for m in models:
        for name, sl in (
            ("all frames", lambda r: True),
            ("frames the gate passed", lambda r: r["gate_ok"]),
            ("low-light", lambda r: r["condition"].startswith("low_light")),
        ):
            rs = [r for r in test if r["model"] == m and sl(r)]
            np_rs = [r for r in rs if r["purpose"] == "nameplate"]
            dm_rs = [r for r in rs if r["purpose"] == "damage"]
            cells = []
            for f in NP_FIELDS:
                a, n = acc(np_rs, f)
                cells.append(f"{pct(a)} (n={n})")
            for f in DM_FIELDS:
                a, n = acc(dm_rs, f)
                cells.append(f"{pct(a)} (n={n})")
            out.append(f"| {m} | {name} | " + " | ".join(cells) + " |")
    cers = [r["scores"][f]["cer"] for r in test if r["purpose"] == "nameplate" for f in ("serial", "model") if r["scores"].get(f, {}).get("cer") is not None]
    if cers:
        out += ["", f"Character error rate on model/serial (all models, test): median {statistics.median(cers):.2f}, mean {statistics.mean(cers):.2f}."]
    return "\n".join(out) + "\n"


def section_confidence(rows, models, calib) -> str:
    out = ["## 4. Do confidence scores go down when the answer is wrong?", ""]
    out += ["| Model | Field rows | Accuracy | Mean conf. when right | Mean conf. when wrong | Accuracy of fields auto-accepted (conf ≥ 0.6) | Share auto-accepted |", "|---|---|---|---|---|---|---|"]
    for m in models:
        vals = [(s["conf"], s["correct"]) for r in rows if r["model"] == m and r["split"] == "test" and r.get("scores")
                for f, s in r["scores"].items() if isinstance(s, dict) and "conf" in s and "correct" in s and not s.get("abstained")]
        if not vals:
            continue
        right = [c for c, ok in vals if ok]
        wrong = [c for c, ok in vals if not ok]
        auto = [ok for c, ok in vals if c >= 0.6]
        out.append(
            f"| {m} | {len(vals)} | {pct(sum(ok for _, ok in vals) / len(vals))} | {statistics.mean(right):.2f} | "
            f"{(statistics.mean(wrong) if wrong else float('nan')):.2f} | {pct(sum(auto) / len(auto) if auto else None)} | {pct(len(auto) / len(vals))} |"
        )
    if calib:
        out += ["", f"Nameplate confidence calibration for `{calib['model']}` (fitted on tune, measured on test):", "",
                "| Confidence | n | Accuracy | Mean conf. right | Mean conf. wrong | AUROC | ECE |", "|---|---|---|---|---|---|---|"]
        for name, s in calib["test"].items():
            out.append(f"| {name} | {s['n']} | {pct(s['accuracy'])} | {s['mean_conf_correct']} | {s['mean_conf_wrong']} | {s['auroc']} | {s['ece']} |")
        out += ["", f"Fitted coefficients: `{json.dumps(calib['coefficients'])}` (shipped in backend/calibration.json)."]
    return "\n".join(out) + "\n"


def section_failures(rows, deployed) -> str:
    causes = load_json(LABELS / "failure_causes.json", {})
    wrong = []
    for r in rows:
        if r["model"] != deployed or r["split"] != "test" or not r.get("scores"):
            continue
        for f, s in r["scores"].items():
            if isinstance(s, dict) and s.get("correct") is False and "conf" in s:
                wrong.append((s["conf"], r, f, s))
    wrong.sort(key=lambda x: -x[0])
    out = ["## 5. Five worst failures (most confident wrong answers, deployed model, test scenes)", "",
           "| # | Frame | Field | Truth | Predicted | Conf. | Gate | Cause |", "|---|---|---|---|---|---|---|---|"]
    for i, (c, r, f, s) in enumerate(wrong[:5], 1):
        cause = causes.get(f"{r['key']}#{f}", "_to be diagnosed_")
        out.append(f"| {i} | [{r['scene']}](raw/{r['key']}) ({r['condition']}) | {f} | `{s.get('truth')}` | `{s.get('pred')}` | {c:.2f} | {'pass' if r['gate_ok'] else 'fail'} | {cause} |")
    return "\n".join(out) + "\n"


def section_size(rows, deployed) -> str:
    sizes = [r["scores"]["size"] for r in rows if r["model"] == deployed and r.get("scores", {}).get("size")]
    if not sizes:
        return "## 6. Size without depth (stretch)\n\n_No tape-measured damage frames yet._\n"
    out = ["## 6. Size without depth vs tape measure (stretch)", "", "| Method | n | Median abs. % error (area) | Within ±25% | Within ±50% |", "|---|---|---|---|---|"]
    methods = defaultdict(list)
    for s in sizes:
        for m, v in s["methods"].items():
            methods[m].append(v["ape_area"])
    for m, apes in sorted(methods.items()):
        out.append(f"| {m} | {len(apes)} | {pct(statistics.median(apes))} | {pct(sum(a <= 0.25 for a in apes) / len(apes))} | {pct(sum(a <= 0.5 for a in apes) / len(apes))} |")
    return "\n".join(out) + "\n"


def section_notes(notes, models) -> str:
    if not notes:
        return "## 7. Notes → structured fields\n\n_No scripted notes recorded yet._\n"
    out = ["## 7. Notes → structured fields", "", f"Speech recognition word error rate on scripted notes: mean {statistics.mean(r['asr_wer'] for r in notes):.2f}.", "",
           "| Model | n | category | component | condition | location | measurement |", "|---|---|---|---|---|---|---|"]
    for m in models:
        rs = [r for r in notes if r["model"] == m and r.get("scores")]
        if not rs:
            continue
        cells = []
        for f in ("category", "component", "condition", "location", "measurement"):
            v = [r["scores"][f]["correct"] for r in rs if f in r["scores"]]
            cells.append(f"{pct(sum(v) / len(v) if v else None)} (n={len(v)})")
        out.append(f"| {m} | {len(rs)} | " + " | ".join(cells) + " |")
    return "\n".join(out) + "\n"


def section_cost(rows, notes, models, deployed, cheap) -> str:
    out = ["## 8. Cost and latency (stretch)", "", "| Model | Mean $ per image | p50 latency per image | p95 | Mean $ per note | Est. $ per job* |", "|---|---|---|---|---|---|"]
    per_model = {}
    for m in models:
        calls = [r["call"] for r in rows if r["model"] == m and r.get("call") and not r["call"].get("from_cache_replay")]
        ncalls = [r["call"] for r in notes if r["model"] == m and r.get("call")]
        if not calls:
            continue
        lat = sorted(c["latency_ms"] for c in calls)
        img = statistics.mean(c["cost_usd"] for c in calls)
        nt = statistics.mean(c["cost_usd"] for c in ncalls) if ncalls else 0.0
        job = img * (TYPICAL_JOB["nameplate"] + TYPICAL_JOB["damage"]) + nt * TYPICAL_JOB["note"]
        per_model[m] = job
        out.append(f"| {m} | ${img:.4f} | {lat[len(lat) // 2]} ms | {lat[int(0.95 * (len(lat) - 1))]} ms | ${nt:.5f} | ${job:.4f} |")
    out += ["", f"\\*Typical job = {TYPICAL_JOB['nameplate']} nameplates + {TYPICAL_JOB['damage']} damage captures + {TYPICAL_JOB['note']} notes."]
    # Cascade: cheap model first, escalate the frame to the deployed model when any core field is below tau.
    test_np = [r for r in rows if r["split"] == "test" and r["purpose"] == "nameplate" and r.get("scores")]
    cheap_rows = {r["key"]: r for r in test_np if r["model"] == cheap}
    dep_rows = {r["key"]: r for r in test_np if r["model"] == deployed}
    keys = sorted(set(cheap_rows) & set(dep_rows))
    if keys:
        out += ["", f"**Cascade simulation** on test nameplates ({len(keys)} frames): run `{cheap}`; if any core field's confidence < τ, re-run on `{deployed}`. Uses the per-model results above, so no new calls.", "",
                "| τ | Escalated | Core-field accuracy | Cost per nameplate vs deployed-only |", "|---|---|---|---|"]
        dep_acc = statistics.mean(s["correct"] for k in keys for s in dep_rows[k]["scores"].values())
        dep_cost = statistics.mean(dep_rows[k]["call"]["cost_usd"] for k in keys)
        out.append(f"| deployed only | 100% | {pct(dep_acc)} | 1.00× |")
        for tau in (0.5, 0.6, 0.7, 0.8, 0.9):
            esc = [k for k in keys if min(s["conf"] for s in cheap_rows[k]["scores"].values()) < tau]
            correct, cost = [], []
            for k in keys:
                src = dep_rows[k] if k in esc else cheap_rows[k]
                correct += [s["correct"] for s in src["scores"].values()]
                cost.append(cheap_rows[k]["call"]["cost_usd"] + (dep_rows[k]["call"]["cost_usd"] if k in esc else 0))
            out.append(f"| {tau} | {pct(len(esc) / len(keys))} | {pct(statistics.mean(correct))} | {statistics.mean(cost) / dep_cost:.2f}× |")
    return "\n".join(out) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--deployed", default="claude-opus-5-5")
    ap.add_argument("--cheap", default="claude-haiku-5-5")
    args = ap.parse_args()
    rows = jsonl("extraction.jsonl")
    notes = jsonl("notes.jsonl")
    qc = load_json(RESULTS / "qc.json", None)
    calib = load_json(RESULTS / "calibration.json", None)
    models = sorted({r["model"] for r in rows}, key=lambda m: ("haiku" not in m, "sonnet" not in m, m))
    doc = [
        "# FieldCapture evaluation report",
        "",
        "Generated by `eval/report.py` from `eval/results/`. Reproduce without an API key: "
        "`uv run --project backend python eval/run_extraction.py --cached && uv run --project backend python eval/report.py`.",
        "",
        section_testset(rows, qc),
        section_qc(qc),
        section_extraction(rows, models),
        section_confidence(rows, models, calib),
        section_failures(rows, args.deployed),
        section_size(rows, args.deployed),
        section_notes(notes, models),
        section_cost(rows, notes, models, args.deployed, args.cheap),
    ]
    (EVAL_DIR / "REPORT.md").write_text("\n".join(doc), encoding="utf-8")
    print("wrote eval/REPORT.md")


if __name__ == "__main__":
    main()
