"""Experiment (not shipped): would trusting the phone's OCR cut the gate's false retakes?

Idea: on a nameplate frame where the on-device OCR already found a code-like token (6+ letters/digits,
2+ digits), drop the 'blurry' (variant A) or 'blurry' and 'dark' (variant B) verdicts. Measured with
the shipped thresholds on tune and held-out test scenes.

Result 2026-11-27: A changes nothing; B saves 1 false retake on test (9 -> 8 of 46) but lets 1 more
bad frame through (21 -> 20 of 28) and only loses on tune. 6 of the 9 falsely rejected test frames
have no OCR text at all (4 nameplates the phone could not read either, 2 damage shots, which get no
OCR), so there is nothing for OCR to vouch for. Not shipped.

Usage: uv run --project backend python eval/ocr_gate_experiment.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import qc_thresholds as q  # noqa: E402
from common import REPO, raw_captures, source_root  # noqa: E402

TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9\-./]{3,}[A-Za-z0-9]")


def code_like(lines: list[str], min_len: int = 6) -> bool:
    for line in lines:
        for tok in TOKEN.findall(line):
            s = re.sub(r"[^A-Za-z0-9]", "", tok)
            digits = sum(ch.isdigit() for ch in s)
            if len(s) >= min_len and digits >= 2:
                return True
    return False


def main() -> None:
    t = json.loads((REPO / "android/app/src/main/assets/thresholds.json").read_text(encoding="utf-8"))["thresholds"]
    root = source_root()
    ocr = {}
    for c in raw_captures(root):
        rel = c["_dir"].relative_to(root).as_posix()
        for k in c["candidates"]:
            ocr[f"{rel}/{k['file']}"] = k.get("ocrLines") or []
    rows = q.frame_rows()
    variants = {"shipped gate": set(), "A: OCR code overrides blurry": {"blurry"}, "B: OCR code overrides blurry + dark": {"blurry", "dark"}}
    for split in ("tune", "test"):
        for name, dropped in variants.items():
            tp = fp = fn = tn = 0
            for r in (r for r in rows if r["split"] == split):
                issues = q.gate(r, t)
                if dropped and r["purpose"] == "nameplate" and code_like(ocr.get(r["key"], [])):
                    issues = [i for i in issues if i not in dropped]
                fired = bool(issues)
                if r["bad"]:
                    tp, fn = tp + fired, fn + (not fired)
                else:
                    fp, tn = fp + fired, tn + (not fired)
            print(f"{split:4} {name:38} catches {tp}/{tp + fn} bad, false retakes {fp}/{fp + tn}")
        no_text = [r for r in rows if r["split"] == split and not r["bad"] and q.gate(r, t) and not ocr.get(r["key"])]
        print(f"{split:4} falsely rejected usable frames with no OCR text at all: {len(no_text)}")


if __name__ == "__main__":
    main()
