"""Fit the nameplate confidence model on TUNE scenes; report AUROC/ECE on TEST scenes.

Usage: uv run --project backend python eval/calibrate.py --model claude-opus-5-5 [--write]
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


def load(model: str) -> list[dict]:
    rows = []
    for line in (RESULTS / "extraction.jsonl").read_text(encoding="utf-8").splitlines():
        r = json.loads(line)
        if r.get("model") != model or r.get("purpose") != "nameplate" or "scores" not in r:
            continue
        for field, s in r["scores"].items():
            if s.get("abstained") or not s.get("features"):
                continue
            rows.append({"split": r["split"], "field": field, "y": 1.0 if s["correct"] else 0.0, "conf": s["conf"],
                         "self": s["features"]["self"], "x": [s["features"][k] for k in FEATURES]})
    return rows


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


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="claude-opus-5-5")
    ap.add_argument("--write", action="store_true", help="write backend/calibration.json")
    args = ap.parse_args()
    rows = load(args.model)
    tune = [r for r in rows if r["split"] == "tune"]
    test = [r for r in rows if r["split"] == "test"]
    if len(tune) < 10 or len({r["y"] for r in tune}) < 2:
        sys.exit(f"not enough tune rows with both outcomes ({len(tune)}); label more data first")
    Xt, yt = np.array([r["x"] for r in tune]), np.array([r["y"] for r in tune])
    w = fit(Xt, yt)
    Xs, ys = np.array([r["x"] for r in test]), np.array([r["y"] for r in test])
    result = {
        "model": args.model,
        "coefficients": {"bias": round(float(w[0]), 4), **{k: round(float(v), 4) for k, v in zip(FEATURES, w[1:])}},
        "test": {
            "model_self_confidence": summary(np.array([r["self"] for r in test]), ys),
            "prior_combined_confidence": summary(np.array([r["conf"] for r in test]), ys),
            "calibrated_confidence": summary(predict(w, Xs), ys),
        },
        "tune_rows": len(tune),
        "test_rows": len(test),
    }
    save_json(RESULTS / "calibration.json", result)
    print(json.dumps(result, indent=2))
    if args.write:
        save_json(REPO / "backend" / "calibration.json", {"nameplate": result["coefficients"], "model": args.model})
        print("wrote backend/calibration.json (the back office loads it at start-up)")


if __name__ == "__main__":
    main()
