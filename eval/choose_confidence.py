"""Choose the confidence the back office ships for nameplate fields, when fitting one doesn't work.

calibrate.py fits a logistic model and refuses to ship it if it ranks right vs wrong answers worse
than the model's own confidence (out-of-fold AUROC). On 2026-11-27 (Opus, 131 answered fields, 11
wrong) both fits were refused (5 features 0.709, self only 0.824, vs 0.908 for the model's own
confidence). This script compares the back office's priors with fixed monotone mappings of the
model's own confidence, sigmoid(bias + k*self), which keep its ranking, against the auto-accept line
(confidence >= 0.6 goes into the estimate unreviewed).

Chosen, not fitted: three mappings are compared on the same rows they are reported on, so the
numbers are optimistic by that small choice. With --write the chosen mapping goes to
backend/calibration.json, together with the numbers behind it.

Usage: uv run --project backend python eval/choose_confidence.py [--model claude-opus-5-5] [--write]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import calibrate as cal  # noqa: E402
from common import REPO, save_json  # noqa: E402

AUTO_ACCEPT = 0.6
CANDIDATES = [(-3.0, 6.0), (-4.0, 8.0), (-5.0, 9.0)]


def measure(conf: np.ndarray, y: np.ndarray) -> dict:
    acc = conf >= AUTO_ACCEPT
    return {
        **cal.summary(conf, y),
        "auto_accepted_share": round(float(acc.mean()), 3),
        "accuracy_of_auto_accepted": round(float(y[acc].mean()), 3) if acc.any() else None,
        "wrong_auto_accepted": f"{int(((y == 0) & acc).sum())}/{int((y == 0).sum())}",
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="claude-opus-5-5")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()
    rows, _ = cal.load(args.model)
    y = np.array([r["y"] for r in rows])
    own = np.array([r["self"] for r in rows])
    options = {"priors (no calibration.json)": measure(np.array([r["conf"] for r in rows]), y)}
    for b, k in CANDIDATES:
        options[f"sigmoid({b:g} + {k:g}*self)"] = measure(1 / (1 + np.exp(-(b + k * own))), y)
    print(f"{args.model}: {len(rows)} answered nameplate fields, {int((y == 0).sum())} wrong")
    for name, m in options.items():
        print(f"  {name:30} {m}")
    # Fewest wrong values auto-accepted, then the most fields auto-accepted, then the lowest ECE.
    best_name, best = None, None
    for (b, k) in CANDIDATES:
        name = f"sigmoid({b:g} + {k:g}*self)"
        m = options[name]
        key = (int(m["wrong_auto_accepted"].split("/")[0]), -m["auto_accepted_share"], m["ece"])
        if best is None or key < best[0]:
            best, best_name = (key, b, k), name
    _, b, k = best
    print(f"chosen: {best_name}")
    if args.write:
        save_json(
            REPO / "backend" / "calibration.json",
            {
                "model": args.model,
                "nameplate": {"bias": b, "self": k, "ocr_agree": 0.0, "format_ok": 0.0, "legible": 0.0, "qc_pass": 0.0},
                "method": "chosen, not fitted (eval/choose_confidence.py): fitted combinations ranked worse out-of-fold",
                "chosen": best_name,
                "compared": options,
            },
        )
        print("wrote backend/calibration.json (the back office loads it at start-up)")


if __name__ == "__main__":
    main()
