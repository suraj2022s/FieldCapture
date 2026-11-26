"""Fit the nameplate confidence model and measure it out-of-fold (leave one scene out).

With ~10 plate scenes a single tune/test split leaves too little to fit on, so every scene is
predicted by a model fitted on the other scenes (no leakage by scene); AUROC/ECE come from those
out-of-fold predictions, and the shipped coefficients are fitted on all rows. A model that is
(almost) never confidently wrong leaves nothing to fit: that is reported, not treated as an error.

Usage: uv run --project backend python eval/calibrate.py --model claude-opus-5-5 [--write]
       (writes eval/results/calibration_<model>.json; run it for each model the report should cover)
Features per field (computed in backend/fieldcapture_backend/nameplate.py):
  self (model's own confidence), ocr_agree (device-OCR agreement), format_ok, legible, qc_pass.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

EVAL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(EVAL_DIR))
from common import REPO, RESULTS, save_json  # noqa: E402

FEATURES = ["self", "ocr_agree", "format_ok", "legible", "qc_pass"]


def load(model: str, features: list[str] = FEATURES) -> tuple[list[dict], dict]:
    """Answered nameplate fields (value given) with their confidence features, plus abstention counts."""
    rows, abstained = [], {"abstained_correctly": 0, "abstained_but_printed": 0}
    for line in (RESULTS / "extraction.jsonl").read_text(encoding="utf-8").splitlines():
        r = json.loads(line)
        if r.get("model") != model or r.get("purpose") != "nameplate" or "scores" not in r:
            continue
        for field, s in r["scores"].items():
            if s.get("abstained"):
                abstained["abstained_correctly" if s["correct"] else "abstained_but_printed"] += 1
                continue
            if not s.get("features"):
                continue
            rows.append({"scene": r["scene"], "field": field, "y": 1.0 if s["correct"] else 0.0, "conf": s["conf"],
                         "self": s["features"]["self"], "x": [s["features"][k] for k in features]})
    return rows, abstained


def fit(X: np.ndarray, y: np.ndarray, l2: float = 0.1, iters: int = 4000, lr: float = 0.5) -> np.ndarray:
    Xb = np.hstack([np.ones((len(X), 1)), X])
    w = np.zeros(Xb.shape[1])
    for _ in range(iters):
        p = 1 / (1 + np.exp(-Xb @ w))
        grad = Xb.T @ (p - y) / len(y) + l2 * np.r_[0, w[1:]] / len(y)
        w -= lr * grad
    return w


def predict(w: np.ndarray, X: np.ndarray) -> np.ndarray:
    return 1 / (1 + np.exp(-(np.hstack([np.ones((len(X), 1)), X]) @ w)))


def auroc(conf: np.ndarray, y: np.ndarray) -> float | None:
    pos, neg = conf[y == 1], conf[y == 0]
    if len(pos) == 0 or len(neg) == 0:
        return None
    wins = (pos[:, None] > neg[None, :]).sum() + 0.5 * (pos[:, None] == neg[None, :]).sum()
    return round(float(wins / (len(pos) * len(neg))), 3)


def ece(conf: np.ndarray, y: np.ndarray, bins: int = 10) -> float:
    edges = np.linspace(0, 1, bins + 1)
    total = 0.0
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (conf >= lo) & (conf < hi) if hi < 1 else (conf >= lo) & (conf <= hi)
        if m.any():
            total += m.mean() * abs(conf[m].mean() - y[m].mean())
    return round(float(total), 3)


def summary(conf: np.ndarray, y: np.ndarray) -> dict:
    return {
        "n": int(len(y)),
        "accuracy": round(float(y.mean()), 3) if len(y) else None,
        "mean_conf_correct": round(float(conf[y == 1].mean()), 3) if (y == 1).any() else None,
        "mean_conf_wrong": round(float(conf[y == 0].mean()), 3) if (y == 0).any() else None,
        "auroc": auroc(conf, y),
        "ece": ece(conf, y),
    }


MIN_WRONG = 3  # fewer wrong answers than this and there is nothing to learn a confidence model from
# --write refuses a fit that ranks right vs wrong answers worse than the model's own confidence does
# (out-of-fold AUROC). With few wrong answers a 5-feature fit can overfit: Opus on 2026-11-27, 11 wrong
# of 131, fell from 0.908 (self) to 0.709 and raised mean confidence on wrong answers from 0.48 to 0.87.
AUROC_TOLERANCE = 0.02


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="claude-opus-5-5")
    ap.add_argument("--features", default=",".join(FEATURES), help=f"subset of {FEATURES}")
    ap.add_argument("--write", action="store_true", help="write backend/calibration.json")
    args = ap.parse_args()
    features = [f.strip() for f in args.features.split(",") if f.strip()]
    if unknown := set(features) - set(FEATURES):
        sys.exit(f"unknown features: {sorted(unknown)}")
    rows, abstained = load(args.model, features)
    n_wrong = int(sum(r["y"] == 0 for r in rows))
    base = {"model": args.model, "method": "leave-one-scene-out", "features": features, "answered_fields": len(rows), "wrong_answers": n_wrong,
            "scenes": len({r["scene"] for r in rows}), **abstained}
    if len(rows) < 10 or n_wrong < MIN_WRONG or n_wrong == len(rows):
        # Not an error: a model that is never confidently wrong here leaves nothing to calibrate against.
        result = {**base, "fitted": False,
                  "note": f"{n_wrong} wrong of {len(rows)} answered fields (need >= {MIN_WRONG} of each outcome); prior coefficients kept",
                  "all_rows": {"model_self_confidence": summary(np.array([r["self"] for r in rows]), np.array([r["y"] for r in rows])),
                               "prior_combined_confidence": summary(np.array([r["conf"] for r in rows]), np.array([r["y"] for r in rows]))} if rows else {}}
        save_json(RESULTS / f"calibration_{args.model}.json", result)
        print(json.dumps(result, indent=2))
        return
    # Out-of-fold: every scene is predicted by a model fitted on the other scenes (no leakage by scene),
    # which uses all the data for evaluation; the shipped coefficients are then fitted on every row.
    X, y = np.array([r["x"] for r in rows]), np.array([r["y"] for r in rows])
    oof = np.full(len(rows), np.nan)
    for scene in sorted({r["scene"] for r in rows}):
        held = np.array([r["scene"] == scene for r in rows])
        if len(set(y[~held])) < 2:
            continue
        oof[held] = predict(fit(X[~held], y[~held]), X[held])
    ok = ~np.isnan(oof)
    w = fit(X, y)
    result = {
        **base,
        "fitted": True,
        # Features left out get 0 (nameplate.field_confidence treats a missing coefficient as 0).
        "coefficients": {"bias": round(float(w[0]), 4), **{k: 0.0 for k in FEATURES}, **{k: round(float(v), 4) for k, v in zip(features, w[1:])}},
        "test": {
            "model_self_confidence": summary(np.array([r["self"] for r in rows])[ok], y[ok]),
            "prior_combined_confidence": summary(np.array([r["conf"] for r in rows])[ok], y[ok]),
            "calibrated_confidence": summary(oof[ok], y[ok]),
        },
    }
    save_json(RESULTS / f"calibration_{args.model}.json", result)
    print(json.dumps(result, indent=2))
    if args.write:
        t = result["test"]
        own, cal = t["model_self_confidence"]["auroc"], t["calibrated_confidence"]["auroc"]
        if own is not None and cal is not None and cal < own - AUROC_TOLERANCE:
            sys.exit(f"NOT written: calibrated AUROC {cal} < the model's own confidence {own}; it would let more wrong values "
                     f"through unflagged. Try --features self.")
        save_json(REPO / "backend" / "calibration.json", {"nameplate": result["coefficients"], "model": args.model, "features": features})
        print("wrote backend/calibration.json (the back office loads it at start-up)")


if __name__ == "__main__":
    main()
