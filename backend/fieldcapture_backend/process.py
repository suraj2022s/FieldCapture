"""Background extraction: each capture/note is processed as soon as it arrives, so the job record
is nearly complete by the time the tech walks out (capture -> record latency is measured)."""

from __future__ import annotations

import json
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import damage, nameplate, note
from .config import BACKEND_DIR, Settings
from .llm import Llm
from .record import assemble
from .storage import Store

log = logging.getLogger("fieldcapture.process")


def load_calibration(path: Path = BACKEND_DIR / "calibration.json") -> dict:
    """Confidence coefficients fitted by eval/calibrate.py (falls back to priors in nameplate.py)."""
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


class Processor:
    def __init__(self, store: Store, settings: Settings, llm: Llm):
        self.store = store
        self.s = settings
        self.llm = llm
        self.pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="extract")
        self.calibration = load_calibration()

    def submit(self, item_id: str) -> None:
        item = self.store.item(item_id)
        if item is None or item.kind not in ("capture", "note") or item.meta.get("deleted"):
            return
        if not self.s.extract:
            return
        self.store.set_extraction(item.id, item.job_id, item.kind, "pending")
        self.pool.submit(self._run_safe, item_id)

    def _run_safe(self, item_id: str) -> None:
        item = self.store.item(item_id)
        if item is None:
            return
        try:
            result, calls = self.run(item)
            self.store.set_extraction(item.id, item.job_id, item.kind, "done", result, calls)
        except Exception as e:  # recorded on the item and flagged in the job record
            log.exception("extraction failed for %s", item_id)
            self.store.set_extraction(item.id, item.job_id, item.kind, "error", error=f"{type(e).__name__}: {e}")

    def run(self, item) -> tuple[dict, list[dict]]:
        m = item.meta
        if item.kind == "note":
            read, call = note.extract(self.llm, self.s.model_note, self.s.effort_note, m.get("text", ""), m.get("room"))
            return {"fields": note.to_fields(read, m.get("room")), "raw": read.model_dump()}, [call]
        if not item.image_path:
            raise ValueError("capture has no image")
        jpeg = self.store.abs_path(item.image_path).read_bytes()
        if m["type"] == "nameplate":
            read, call = nameplate.extract(self.llm, self.s.model_nameplate, self.s.effort_nameplate, jpeg, self.s.max_image_edge)
            ocr_lines = [ln["text"] for ln in (m.get("ocr") or {}).get("lines", [])]
            passed = bool((m.get("quality") or {}).get("passed"))
            fields = nameplate.to_fields(read, ocr_lines, passed, self.calibration.get("nameplate"))
            return {"fields": fields, "raw": read.model_dump()}, [call]
        read, call, size = damage.extract(
            self.llm, self.s.model_damage, self.s.effort_damage, jpeg, self.s.max_image_edge, m.get("room"), m.get("surface")
        )
        fields = damage.to_fields(read, m.get("room"), m.get("surface"), float(m.get("asrConfidence") or 0.5), size)
        return {"fields": fields, "raw": read.model_dump(), "image_size": list(size)}, [call]

    def finalize_later(self, job_id: str, timeout_s: float = 180.0) -> None:
        """After 'end job': wait for in-flight extractions, then write job_record.json next to the evidence."""

        def work():
            deadline = time.monotonic() + timeout_s
            while time.monotonic() < deadline:
                if not any(e["status"] == "pending" for e in self.store.extractions(job_id).values()):
                    break
                time.sleep(1.0)
            record = assemble(self.store, job_id, self.s.extract)
            (self.store.job_dir(job_id) / "job_record.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
            log.info("job %s record written (%d captures, %d notes)", job_id, len(record["captures"]), len(record["notes"]))

        threading.Thread(target=work, name=f"finalize-{job_id[:8]}", daemon=True).start()
