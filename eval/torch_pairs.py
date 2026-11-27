"""Does a torch help? Paired by plate: only scenes shot both in the dark and with a torch.

Usage: uv run --project backend python eval/torch_pairs.py [--model claude-opus-5-5]
Per scene and overall: frames labelled usable, frames the gate passed, printed fields read exactly.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import RESULTS  # noqa: E402

CONDS = ("low_light", "low_light_with_torch")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="claude-opus-5-5")
    args = ap.parse_args()
    rows = [json.loads(l) for l in (RESULTS / "extraction.jsonl").read_text(encoding="utf-8").splitlines()]
    rows = [r for r in rows if r["model"] == args.model and r["purpose"] == "nameplate" and r.get("scores")]
    scenes = sorted({r["scene"] for r in rows if r["condition"] == CONDS[0]} & {r["scene"] for r in rows if r["condition"] == CONDS[1]})
    tot = {c: [0, 0, 0, 0, 0] for c in CONDS}  # frames, usable, gate ok, read, printed
    print(f"{len(scenes)} plates shot both ways ({args.model}):")
    for s in scenes:
        cells = []
        for c in CONDS:
            cr = [r for r in rows if r["scene"] == s and r["condition"] == c]
            printed = [x for r in cr for x in r["scores"].values() if isinstance(x, dict) and x.get("truth")]
            v = [len(cr), sum(r["label"] == "usable" for r in cr), sum(r["gate_ok"] for r in cr),
                 sum(x["correct"] for x in printed), len(printed)]
            tot[c] = [a + b for a, b in zip(tot[c], v)]
            cells.append(f"{c}: usable {v[1]}/{v[0]}, gate ok {v[2]}/{v[0]}, read {v[3]}/{v[4]}")
        print(f"  {s:26} " + " | ".join(cells))
    for c in CONDS:
        f, u, g, rd, p = tot[c]
        print(f"TOTAL {c:22} frames {f}: usable {u} ({u / f:.0%}), gate passed {g} ({g / f:.0%}), printed fields read exactly {rd}/{p} ({rd / p:.0%})")


if __name__ == "__main__":
    main()
