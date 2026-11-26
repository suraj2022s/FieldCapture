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
from common import LABELS, REPO, RESULTS, load_json  # noqa: E402

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
    lines += ["", "Split: by scene (`common.split_of`). The quality thresholds (§2) are fitted on **tune** scenes and checked on held-out **test** scenes. "
              "Extraction (§3–5, §8) fits nothing on these labels, so it is reported on **all** scenes; confidence calibration uses leave-one-scene-out.", ""]
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
    test = [r for r in rows if "scores" in r]
    out = ["## 3. Extraction accuracy per field (all scenes)", "",
           "A nameplate field that is not printed counts as correct only if the model left it blank.", ""]
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
    out += ["", "**Read rate and invented values** (nameplates): of the fields printed on the plate, how many were read exactly; "
            "of the fields *not* printed, how many got a value anyway.", "",
            "| Model | Printed fields read exactly | Printed but left blank | Invented values (field not printed) |", "|---|---|---|---|"]
    for m in models:
        ss = [s for r in test if r["model"] == m and r["purpose"] == "nameplate" for s in r["scores"].values() if isinstance(s, dict)]
        printed = [s for s in ss if s.get("truth")]
        absent = [s for s in ss if not s.get("truth")]
        out.append(f"| {m} | {sum(s['correct'] for s in printed)}/{len(printed)} | {sum(s['abstained'] for s in printed)}/{len(printed)} | "
                   f"{sum(not s['abstained'] for s in absent)}/{len(absent)} |")
    cers = [r["scores"][f]["cer"] for r in test if r["purpose"] == "nameplate" for f in ("serial", "model") if r["scores"].get(f, {}).get("cer") is not None]
    if cers:
        out += ["", f"Character error rate on model/serial (all models): median {statistics.median(cers):.2f}, mean {statistics.mean(cers):.2f}."]
    return "\n".join(out) + "\n"


def section_confidence(rows, models, calib) -> str:
    out = ["## 4. Do confidence scores go down when the answer is wrong? (answered fields, all scenes)", ""]
    out += ["| Model | Field rows | Accuracy | Mean conf. when right | Mean conf. when wrong | Accuracy of fields auto-accepted (conf ≥ 0.6) | Share auto-accepted |", "|---|---|---|---|---|---|---|"]
    for m in models:
        vals = [(s["conf"], s["correct"]) for r in rows if r["model"] == m and r.get("scores")
                for f, s in r["scores"].items() if isinstance(s, dict) and "conf" in s and "correct" in s and not s.get("abstained")]
        if not vals:
            continue
        right = [c for c, ok in vals if ok]
        wrong = [c for c, ok in vals if not ok]
        auto = [ok for c, ok in vals if c >= 0.6]
        out.append(
            f"| {m} | {len(vals)} | {pct(sum(ok for _, ok in vals) / len(vals))} | {(f'{statistics.mean(right):.2f}' if right else '—')} | "
            f"{(f'{statistics.mean(wrong):.2f}' if wrong else '— (none wrong)')} | {pct(sum(auto) / len(auto) if auto else None)} | {pct(len(auto) / len(vals))} |"
        )
    for c in calib:
        out += ["", f"**Nameplate confidence calibration, `{c['model']}`** ({c['method']}; {c['answered_fields']} answered fields from {c['scenes']} scenes, "
                f"{c['wrong_answers']} wrong; abstained {c['abstained_correctly']}× where nothing was printed and {c['abstained_but_printed']}× on a printed value it couldn't read):"]
        if not c.get("fitted"):
            out += ["", f"_Not fitted: {c['note']}._"]
            continue
        out += ["", "| Confidence | n | Accuracy | Mean conf. right | Mean conf. wrong | AUROC | ECE |", "|---|---|---|---|---|---|---|"]
        for name, s in c["test"].items():
            out.append(f"| {name} | {s['n']} | {pct(s['accuracy'])} | {s['mean_conf_correct']} | {s['mean_conf_wrong']} | {s['auroc']} | {s['ece']} |")
        out += ["", f"Coefficients fitted on all rows: `{json.dumps(c['coefficients'])}`."]
    shipped = load_json(REPO / "backend" / "calibration.json", {})
    if shipped.get("compared"):
        out += ["", f"**What the back office ships** (`backend/calibration.json`, `{shipped['model']}`): **{shipped['chosen']}**, {shipped['method']}. "
                "The rows above measure the confidence computed during the run (the priors); the options on the same answered fields:", "",
                "| Confidence | AUROC | ECE | Mean conf. wrong | Auto-accepted (≥ 0.6) | Accuracy of auto-accepted | Wrong values auto-accepted |", "|---|---|---|---|---|---|---|"]
        for name, s in shipped["compared"].items():
            mark = "**" if name == shipped["chosen"] else ""
            out.append(f"| {mark}{name}{mark} | {s['auroc']} | {s['ece']} | {s['mean_conf_wrong']} | {pct(s['auto_accepted_share'])} | "
                       f"{pct(s['accuracy_of_auto_accepted'])} | {s['wrong_auto_accepted']} |")
        out += ["", "_Chosen among three mappings on the same rows, so slightly optimistic; with 11 wrong answers, one more or fewer moves the last column by 9 points._"]
    return "\n".join(out) + "\n"


def section_failures(rows, deployed) -> str:
    causes = load_json(LABELS / "failure_causes.json", {})
    wrong = []
    for r in rows:
        if r["model"] != deployed or not r.get("scores"):
            continue
        for f, s in r["scores"].items():
            if isinstance(s, dict) and s.get("correct") is False and "conf" in s:
                wrong.append((s["conf"], r, f, s))
    # Worst first: a confident wrong value goes unchecked into an estimate; a blank is at least flagged.
    # One row per capture, so five rows are five different failures, not one dark shot's fields.
    wrong.sort(key=lambda x: (x[3].get("abstained", False), -x[0]))
    seen: set[str] = set()
    wrong = [w for w in wrong if not (w[1]["capture"] in seen or seen.add(w[1]["capture"]))]
    out = ["## 5. Five worst failures (deployed model, all scenes)", "",
           "Ranked: confidently wrong values first (they reach an estimate unchecked), then printed values the model left blank (flagged for review).", ""]
    if not wrong:
        return "\n".join(out + ["_No wrong or missed fields for the deployed model._"]) + "\n"
    out += ["| # | Frame | Field | Truth | Predicted | Conf. | Gate | Cause |", "|---|---|---|---|---|---|---|---|"]
    for i, (c, r, f, s) in enumerate(wrong[:5], 1):
        cause = causes.get(f"{r['key']}#{f}", "_to be diagnosed_")
        out.append(f"| {i} | [{r['scene']}](dataset/{r['key']}) ({r['condition']}) | {f} | `{s.get('truth')}` | `{s.get('pred')}` | {c:.2f} | {'pass' if r['gate_ok'] else 'fail'} | {cause} |")
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
    # Cascade: cheap model first; escalate to the deployed model when an answered core field is below
    # tau, or make/model/serial came back blank (a blank date is normal: many plates print none).
    np_rows = [r for r in rows if r["purpose"] == "nameplate" and r.get("scores")]
    cheap_rows = {r["key"]: r for r in np_rows if r["model"] == cheap}
    dep_rows = {r["key"]: r for r in np_rows if r["model"] == deployed}
    keys = sorted(set(cheap_rows) & set(dep_rows))

    def escalate(scores: dict, tau: float) -> bool:
        return any((not s["abstained"] and s["conf"] < tau) or (s["abstained"] and f in ("make", "model", "serial")) for f, s in scores.items())

    if keys:
        out += ["", f"**Cascade simulation** on nameplates ({len(keys)} frames): run `{cheap}`; escalate to `{deployed}` when an answered field's confidence < τ "
                "or make/model/serial is blank. Uses the per-model results above, so no new calls.", "",
                "| τ | Escalated | Core-field accuracy | Cost per nameplate vs deployed-only |", "|---|---|---|---|"]
        dep_acc = statistics.mean(s["correct"] for k in keys for s in dep_rows[k]["scores"].values())
        dep_cost = statistics.mean(dep_rows[k]["call"]["cost_usd"] for k in keys)
        out.append(f"| deployed only | 100% | {pct(dep_acc)} | 1.00× |")
        for tau in (0.5, 0.6, 0.7, 0.8, 0.9):
            esc = [k for k in keys if escalate(cheap_rows[k]["scores"], tau)]
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
    calib = [load_json(p, {}) for p in sorted(RESULTS.glob("calibration_*.json"))]
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
