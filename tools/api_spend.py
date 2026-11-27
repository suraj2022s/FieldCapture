"""Total the Anthropic API spend from every response we cached (one cache file = one paid call).

Usage (repo root): uv run --project backend python tools/api_spend.py
Reads eval/llm-cache (the eval runs, committed) and backend/data/llm-cache (the back office, local).
Prices: backend/fieldcapture_backend/config.py (list prices). The Anthropic console invoice is the receipt;
this is the cross-check.
"""

from __future__ import annotations

import collections
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "backend"))
from fieldcapture_backend.config import CACHE_READ_FACTOR, CACHE_WRITE_FACTOR, PRICES_PER_MTOK  # noqa: E402


def cost(st: dict) -> float:
    if "cost_usd" in st:
        return float(st["cost_usd"])
    pin, pout = PRICES_PER_MTOK.get(st.get("model", ""), (0.0, 0.0))
    return (
        st.get("input_tokens", 0) * pin
        + st.get("output_tokens", 0) * pout
        + st.get("cache_read_input_tokens", 0) * pin * CACHE_READ_FACTOR
        + st.get("cache_creation_input_tokens", 0) * pin * CACHE_WRITE_FACTOR
    ) / 1e6


def main() -> None:
    grand = 0.0
    for name, folder in (("eval runs", REPO / "eval" / "llm-cache"), ("back office (phone walks)", REPO / "backend" / "data" / "llm-cache")):
        by = collections.defaultdict(lambda: [0, 0.0])
        days = collections.Counter()
        for f in folder.glob("*.json") if folder.exists() else []:
            st = json.loads(f.read_text(encoding="utf-8")).get("stats") or {}
            c = cost(st)
            by[st.get("model", "?")][0] += 1
            by[st.get("model", "?")][1] += c
        total = sum(v[1] for v in by.values())
        grand += total
        print(f"{name}: {sum(v[0] for v in by.values())} calls, ${total:.2f}")
        for m, (n, c) in sorted(by.items()):
            print(f"   {m:22} {n:5} calls  ${c:.2f}")
    print(f"TOTAL measured API spend: ${grand:.2f} (budget $50)")


if __name__ == "__main__":
    main()
