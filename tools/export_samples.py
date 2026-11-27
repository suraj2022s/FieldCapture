"""Export real job records from a running back office into samples/ (record JSON + evidence photos).

Usage (repo root, back office running):
  uv run --project backend python tools/export_samples.py --url http://127.0.0.1:8002 \
      --job a0c92776 --job 69bb6343 --job 77b09db1 \
      --withhold "77b09db1:kitchen window:view outside through the window"

The access code is read from FIELDCAPTURE_TOKEN or private/backoffice_access_code.txt and is never
written. --withhold JOB:TEXT:REASON keeps a photo out of the repo (privacy) for the capture of that
job whose transcript contains TEXT; its fields stay in the record and the reason is written in place
of the photo link.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "samples"


def get(url: str, code: str) -> bytes:
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {code}"} if code else {})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8002")
    ap.add_argument("--job", action="append", required=True, help="job id or its first 8 characters")
    ap.add_argument("--withhold", action="append", default=[], help="JOB:TEXT:REASON")
    args = ap.parse_args()
    code = os.environ.get("FIELDCAPTURE_TOKEN") or (REPO / "private" / "backoffice_access_code.txt").read_text(encoding="utf-8").strip()
    withhold = [w.split(":", 2) for w in args.withhold]

    jobs = json.loads(get(f"{args.url}/v1/jobs", code))
    index = []
    for n, prefix in enumerate(args.job, 1):
        job_id = next(j["job_id"] for j in jobs if j["job_id"].startswith(prefix))
        record = json.loads(get(f"{args.url}/v1/jobs/{job_id}/record", code))
        folder = OUT / f"job{n}-{job_id[:8]}"
        folder.mkdir(parents=True, exist_ok=True)
        for c in record["captures"]:
            url = c["evidence"].get("url")
            held = next((w for w in withhold if job_id.startswith(w[0]) and w[1].lower() in c["command"]["transcript"].lower()), None)
            if held:
                c["evidence"]["url"] = None
                c["evidence"]["withheld"] = f"photo not published: {held[2]}"
                continue
            if url:
                name = f"{c['capture_id'][:8]}-{c['type']}.jpg"
                (folder / name).write_bytes(get(args.url + url, code))
                c["evidence"]["url"] = name
        text = json.dumps(record, indent=2, ensure_ascii=False)
        if code and code in text:
            raise SystemExit(f"{job_id}: the access code appears in the record; not writing it")
        text = re.sub(r"http://(?:10|172\.(?:1[6-9]|2\d|3[01])|192\.168)\.\d+\.\d+(?::\d+)?", "http://<back office>", text)
        (folder / "record.json").write_text(text + "\n", encoding="utf-8")
        p = record["processing"]
        index.append(
            f"| [job{n}-{job_id[:8]}](job{n}-{job_id[:8]}/record.json) | {len(record['captures'])} | {len(record['notes'])} | "
            f"{len(record['line_items'])} | {len(record['review']['flags'])} | ${p['cost_usd']:.3f} | {p.get('schema_valid')} |"
        )
        print(f"{folder.relative_to(REPO)}: {len(record['captures'])} captures, {len(record['notes'])} notes")
    (OUT / "README.md").write_text(
        "# Sample job records\n\n"
        "Three real walk-throughs, filmed 2026-11-27 with the submitted app on the test phone (Mock Device Kit fed by the "
        "phone's camera), uploaded **over Wi-Fi with no USB cable** to the back office with extraction on, then exported "
        "with `tools/export_samples.py`. Nothing is edited by hand. Each folder holds the job record exactly as the back "
        "office assembled it, and the evidence photos it links to.\n\n"
        "| Job | Captures | Notes | Draft line items | Review flags | Model cost | Schema valid |\n|---|---|---|---|---|---|---|\n"
        + "\n".join(index)
        + "\n\nPhotos withheld for privacy (fields kept): "
        + ("; ".join(f"{w[0]} '{w[1]}': {w[2]}" for w in withhold) or "none")
        + ".\n\nWhat these show, wrong values included, is discussed in `docs/JOURNAL.md` (2026-11-27 entries).\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
