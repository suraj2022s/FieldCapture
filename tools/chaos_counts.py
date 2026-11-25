"""What the back office stored for one job, read straight from its SQLite file and log.

Used by tools/chaos_run.ps1 to compare the phone's acknowledgements with the server's rows.
Standard library only:  python chaos_counts.py <data_dir> <job_id> <server_log>
"""

from __future__ import annotations

import json
import re
import sqlite3
import sys
from collections import Counter
from pathlib import Path


def main(data_dir: str, job_id: str, server_log: str) -> None:
    db = sqlite3.connect(Path(data_dir) / "fieldcapture.db")
    rows = db.execute("SELECT id, kind, meta_json, versions FROM items WHERE job_id = ?", (job_id,)).fetchall()
    live = [(i, k, json.loads(m), v) for i, k, m, v in rows]
    captures = sorted(i[:8] for i, k, m, _ in live if k == "capture" and not m.get("deleted"))
    notes = sorted(i[:8] for i, k, m, _ in live if k == "note" and not m.get("deleted"))
    # "ingest <job8> <kind> <item8> -> created|duplicate|updated" (one line per upload the server answered)
    pattern = re.compile(rf"ingest {re.escape(job_id[:8])} (\w+) (\S+) -> (\w+)")
    answers: Counter[str] = Counter()
    per_item: Counter[tuple[str, str]] = Counter()
    for line in Path(server_log).read_text(encoding="utf-8", errors="replace").splitlines():
        if m := pattern.search(line):
            answers[m.group(3)] += 1
            per_item[(m.group(1), m.group(2))] += 1
    print(
        json.dumps(
            {
                "rows_by_kind": dict(Counter(k for _, k, _, _ in live)),
                "captures": captures,
                "notes": notes,
                "finalized": any(k == "finalize" for _, k, _, _ in live),
                "ingest_answers": dict(answers),
                "items_uploaded_more_than_once": sorted(f"{k} {i}" for (k, i), n in per_item.items() if n > 1 and k in ("capture", "note")),
            }
        )
    )


if __name__ == "__main__":
    main(*sys.argv[1:4])
