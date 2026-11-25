"""Fit the on-device quality thresholds from labelled frames, then test them on held-out scenes.

Usage (repo root):  uv run --project backend python eval/qc_thresholds.py [--max-false-retake 0.05] [--write-app]

Metrics are the numbers the phone computed (dataset mode), not recomputed here, so the shipped
thresholds are measured on exactly what the gate sees. Operating point: the loosest threshold that
still rejects at most --max-false-retake of the usable frames in the TUNE scenes. Rationale: a false
retake costs a tech ~5 s and patience, so we cap it; we then report how many truly bad frames that
threshold catches on the TEST scenes (scene-level split, no leakage).
"""

from __future__ import annotations

import argparse
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import REPO, RESULTS, RAW, labels, raw_captures, save_json, split_of  # noqa: E402

SEED = {
    "minSharpness": 40.0,
    "minMeanLuma": 45.0,
    "maxDarkFraction": 0.60,
    "maxClippedFraction": 0.10,
    "maxTextClippedFraction": 0.06,
    "minTextLineHeight": 0.018,
    "minEdgeMargin": 0.02,
}


def frame_rows() -> list[dict]:
    frames, caps, _ = labels()
    rows = []
    for c in raw_captures():
        meta = caps.get(c["captureId"], {})
        scene = meta.get("scene") or f"unassigned-{c['captureId'][:8]}"
        rel = c["_dir"].relative_to(RAW).as_posix()
        for cand in c["candidates"]:
            lab = frames.get(f"{rel}/{cand['file']}", {}).get("label")
            if not lab or lab == "wrong_target":
                continue
            boxes = cand.get("ocrBoxes") or []
            union = [min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)] if boxes else None
            heights = sorted(b[3] - b[1] for b in boxes)
            text = cand.get("text") or {}
            rows.append(
                {
                    "key": f"{rel}/{cand['file']}",
                    "capture": c["captureId"],
                    "purpose": c["purpose"],
                    "scene": scene,
                    "split": split_of(scene),
                    "condition": meta.get("condition", ""),
                    "label": lab,
                    "bad": lab != "usable",
                    "sharp": text.get("sharpness", cand["frame"]["sharpness"]) if c["purpose"] == "nameplate" else cand["frame"]["sharpness"],
                    "luma": cand["frame"]["meanLuma"],
                    "dark": cand["frame"]["darkFraction"],
                    "clip": text.get("clippedFraction", cand["frame"]["clippedFraction"]) if c["purpose"] == "nameplate" else cand["frame"]["clippedFraction"],
                    "has_ocr": bool(cand.get("ocrLines")) or cand.get("text") is not None,
                    "margin": min(union[0], union[1], 1 - union[2], 1 - union[3]) if union else None,
                    "line_h": heights[len(heights) // 2] if heights else None,
                }
            )
    return rows


def fit_lower(usable: list[float], bad: list[float], max_fr: float) -> tuple[float | None, dict]:
    """Rule 'reject if metric < T': T = the max_fr quantile of usable frames."""
    if len(usable) < 5 or len(bad) < 3:
        return None, {"n_usable": len(usable), "n_bad": len(bad), "note": "too few frames; seed kept"}
    u = sorted(usable)
    k = int(max_fr * len(u))
    t = u[k] if k < len(u) else u[-1]
    return t, {
        "n_usable": len(usable),
        "n_bad": len(bad),
        "false_retake": sum(x < t for x in usable) / len(usable),
        "recall": sum(x < t for x in bad) / len(bad),
        "auc": auc(bad, usable),  # P(bad frame scores lower than a usable one)
    }


def fit_upper(usable: list[float], bad: list[float], max_fr: float) -> tuple[float | None, dict]:
    """Rule 'reject if metric > T'."""
    t, info = fit_lower([-x for x in usable], [-x for x in bad], max_fr)
    if t is None:
        return None, info
    return -t, info


def auc(lower_is_positive: list[float], others: list[float]) -> float | None:
    if not lower_is_positive or not others:
        return None
    wins = sum((1.0 if p < o else 0.5 if p == o else 0.0) for p in lower_is_positive for o in others)
    return round(wins / (len(lower_is_positive) * len(others)), 3)


def gate(r: dict, t: dict) -> list[str]:
    """Python mirror of QualityGate.evaluate's rules (metrics themselves come from the phone)."""
    issues = []
    if r["luma"] < t["minMeanLuma"] or r["dark"] > t["maxDarkFraction"]:
        issues.append("dark")
    if r["sharp"] < t["minSharpness"]:
        issues.append("blurry")
    if r["purpose"] == "nameplate":
        if r["margin"] is not None and r["margin"] < t["minEdgeMargin"]:
            issues.append("cut_off")
        if r["line_h"] is not None and r["line_h"] < t["minTextLineHeight"]:
            issues.append("too_far")
        if r["clip"] > t["maxTextClippedFraction"]:
            issues.append("glare")
    elif r["clip"] > t["maxClippedFraction"]:
        issues.append("glare")
    return issues


def evaluate(rows: list[dict], t: dict) -> dict:
    tp = fp = fn = tn = 0
    per_reason: dict[str, list[int]] = {}
    for r in rows:
        fired = bool(gate(r, t))
        if r["bad"]:
            tp += fired
            fn += not fired
            per_reason.setdefault(r["label"], [0, 0])
            per_reason[r["label"]][0] += fired
            per_reason[r["label"]][1] += 1
        else:
            fp += fired
            tn += not fired
    return {
        "frames": len(rows),
        "bad_frames": tp + fn,
        "usable_frames": fp + tn,
        "recall_bad": round(tp / (tp + fn), 3) if tp + fn else None,
        "false_retake_rate": round(fp / (fp + tn), 3) if fp + tn else None,
        "precision_retake": round(tp / (tp + fp), 3) if tp + fp else None,
        "per_reason_recall": {k: f"{v[0]}/{v[1]}" for k, v in sorted(per_reason.items())},
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-false-retake", type=float, default=0.05)
    ap.add_argument("--write-app", action="store_true", help="write android/app/src/main/assets/thresholds.json")
    args = ap.parse_args()

    rows = frame_rows()
    tune = [r for r in rows if r["split"] == "tune"]
    test = [r for r in rows if r["split"] == "test"]
    if not rows:
        sys.exit("no labelled frames yet: pull the dataset and label it first (see eval/README.md)")

    def vals(rs, key, labels_, purpose=None):
        return [r[key] for r in rs if r["label"] in labels_ and r[key] is not None and (purpose is None or r["purpose"] == purpose)]

    t = dict(SEED)
    fits: dict[str, dict] = {}
    mf = args.max_false_retake
    t_val, fits["minSharpness"] = fit_lower(vals(tune, "sharp", {"usable"}), vals(tune, "sharp", {"blurry"}), mf)
    if t_val is not None:
        t["minSharpness"] = round(t_val, 1)
    t_val, fits["minMeanLuma"] = fit_lower(vals(tune, "luma", {"usable"}), vals(tune, "luma", {"dark"}), mf)
    if t_val is not None:
        t["minMeanLuma"] = round(t_val, 1)
    t_val, fits["maxDarkFraction"] = fit_upper(vals(tune, "dark", {"usable"}), vals(tune, "dark", {"dark"}), mf)
    if t_val is not None:
        t["maxDarkFraction"] = round(t_val, 3)
    t_val, fits["maxTextClippedFraction"] = fit_upper(vals(tune, "clip", {"usable"}, "nameplate"), vals(tune, "clip", {"glare"}, "nameplate"), mf)
    if t_val is not None:
        t["maxTextClippedFraction"] = round(t_val, 3)
    t_val, fits["maxClippedFraction"] = fit_upper(vals(tune, "clip", {"usable"}, "damage"), vals(tune, "clip", {"glare"}, "damage"), mf)
    if t_val is not None:
        t["maxClippedFraction"] = round(t_val, 3)
    t_val, fits["minEdgeMargin"] = fit_lower(vals(tune, "margin", {"usable"}, "nameplate"), vals(tune, "margin", {"cut_off"}, "nameplate"), mf)
    if t_val is not None:
        t["minEdgeMargin"] = round(t_val, 3)
    t_val, fits["minTextLineHeight"] = fit_lower(vals(tune, "line_h", {"usable"}, "nameplate"), vals(tune, "line_h", {"too_far"}, "nameplate"), mf)
    if t_val is not None:
        t["minTextLineHeight"] = round(t_val, 4)

    result = {
        "operating_point": f"<= {mf:.0%} false retakes on usable TUNE frames",
        "thresholds": t,
        "seed": SEED,
        "fits": fits,
        "tune": {"seed": evaluate(tune, SEED), "fitted": evaluate(tune, t)},
        "test": {"seed": evaluate(test, SEED), "fitted": evaluate(test, t)},
        "counts": {
            "frames": len(rows),
            "scenes": len({r["scene"] for r in rows}),
            "by_label": {lab: sum(r["label"] == lab for r in rows) for lab in sorted({r["label"] for r in rows})},
            "low_light_frames": sum(r["condition"].startswith("low_light") for r in rows),
        },
        "medians_by_label": {
            lab: {k: round(statistics.median([r[k] for r in rows if r["label"] == lab and r[k] is not None]), 3)
                  for k in ("sharp", "luma", "clip") if any(r[k] is not None for r in rows if r["label"] == lab)}
            for lab in sorted({r["label"] for r in rows})
        },
    }
    RESULTS.mkdir(parents=True, exist_ok=True)
    save_json(RESULTS / "qc.json", result)
    print(f"{len(rows)} labelled frames, {result['counts']['scenes']} scenes")
    print("thresholds:", t)
    print("TEST fitted:", result["test"]["fitted"])
    print("TEST seed:  ", result["test"]["seed"])
    if args.write_app:
        n = len(tune)
        out = {"version": f"measured-n{n}", "thresholds": t, "operating_point": result["operating_point"]}
        save_json(REPO / "android" / "app" / "src" / "main" / "assets" / "thresholds.json", out)
        print("wrote android/app/src/main/assets/thresholds.json")


if __name__ == "__main__":
    main()
