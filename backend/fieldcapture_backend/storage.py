"""SQLite + file storage for uploaded items and their extractions.

Idempotency: the phone sends every item with an Idempotency-Key (its id). The same id with the
same bytes is acknowledged as a duplicate; the same id with new bytes is an upsert (the job
header, events and "undo" re-uploads use this on purpose). Nothing is ever stored twice.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS items (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    meta_json TEXT NOT NULL,
    image_path TEXT,
    stream_image_path TEXT,
    content_sha256 TEXT NOT NULL,
    received_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    versions INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS items_job ON items(job_id);
CREATE TABLE IF NOT EXISTS extractions (
    item_id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    result_json TEXT,
    calls_json TEXT,
    error TEXT,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS extractions_job ON extractions(job_id);
"""


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


@dataclass
class Item:
    id: str
    job_id: str
    kind: str
    meta: dict[str, Any]
    image_path: str | None
    stream_image_path: str | None
    received_at: str
    updated_at: str
    versions: int


class Store:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(self.data_dir / "fieldcapture.db", check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(SCHEMA)

    # ------------------------------------------------------------------ files

    def job_dir(self, job_id: str) -> Path:
        safe = "".join(c for c in job_id if c.isalnum() or c in "-_")
        d = self.data_dir / "jobs" / safe
        d.mkdir(parents=True, exist_ok=True)
        return d

    def save_file(self, job_id: str, name: str, data: bytes) -> str:
        safe = "".join(c for c in name if c.isalnum() or c in "-_.")
        path = self.job_dir(job_id) / safe
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_bytes(data)
        tmp.replace(path)
        return str(path.relative_to(self.data_dir)).replace("\\", "/")

    def abs_path(self, rel: str) -> Path:
        return self.data_dir / rel

    # ------------------------------------------------------------------ items

    def upsert_item(
        self,
        item_id: str,
        job_id: str,
        kind: str,
        meta: dict[str, Any],
        image: tuple[str, bytes] | None,
        stream_image: tuple[str, bytes] | None,
    ) -> str:
        """Returns 'created', 'duplicate' or 'updated'."""
        h = hashlib.sha256()
        h.update(json.dumps(meta, sort_keys=True).encode())
        for part in (image, stream_image):
            if part:
                h.update(part[1])
        digest = h.hexdigest()
        with self._lock:
            row = self._db.execute("SELECT content_sha256 FROM items WHERE id=?", (item_id,)).fetchone()
            if row is not None and row["content_sha256"] == digest:
                return "duplicate"
            image_path = self.save_file(job_id, image[0], image[1]) if image else None
            stream_path = self.save_file(job_id, stream_image[0], stream_image[1]) if stream_image else None
            ts = now_iso()
            if row is None:
                self._db.execute(
                    "INSERT INTO items(id, job_id, kind, meta_json, image_path, stream_image_path, content_sha256,"
                    " received_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    (item_id, job_id, kind, json.dumps(meta), image_path, stream_path, digest, ts, ts),
                )
                self._db.commit()
                return "created"
            self._db.execute(
                "UPDATE items SET meta_json=?, image_path=COALESCE(?, image_path),"
                " stream_image_path=COALESCE(?, stream_image_path), content_sha256=?, updated_at=?,"
                " versions=versions+1 WHERE id=?",
                (json.dumps(meta), image_path, stream_path, digest, ts, item_id),
            )
            self._db.commit()
            return "updated"

    def items(self, job_id: str) -> list[Item]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM items WHERE job_id=? ORDER BY received_at", (job_id,)).fetchall()
        return [self._item(r) for r in rows]

    def item(self, item_id: str) -> Item | None:
        with self._lock:
            r = self._db.execute("SELECT * FROM items WHERE id=?", (item_id,)).fetchone()
        return self._item(r) if r else None

    def job_ids(self) -> list[tuple[str, str]]:
        with self._lock:
            rows = self._db.execute(
                "SELECT job_id, MIN(received_at) AS first FROM items GROUP BY job_id ORDER BY first DESC"
            ).fetchall()
        return [(r["job_id"], r["first"]) for r in rows]

    @staticmethod
    def _item(r: sqlite3.Row) -> Item:
        return Item(
            id=r["id"],
            job_id=r["job_id"],
            kind=r["kind"],
            meta=json.loads(r["meta_json"]),
            image_path=r["image_path"],
            stream_image_path=r["stream_image_path"],
            received_at=r["received_at"],
            updated_at=r["updated_at"],
            versions=r["versions"],
        )

    # ------------------------------------------------------------------ extractions

    def set_extraction(
        self,
        item_id: str,
        job_id: str,
        kind: str,
        status: str,
        result: dict[str, Any] | None = None,
        calls: list[dict[str, Any]] | None = None,
        error: str | None = None,
    ) -> None:
        with self._lock:
            self._db.execute(
                "INSERT INTO extractions(item_id, job_id, kind, status, result_json, calls_json, error, updated_at)"
                " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(item_id) DO UPDATE SET status=excluded.status,"
                " result_json=excluded.result_json, calls_json=excluded.calls_json, error=excluded.error,"
                " updated_at=excluded.updated_at",
                (
                    item_id,
                    job_id,
                    kind,
                    status,
                    json.dumps(result) if result is not None else None,
                    json.dumps(calls or []),
                    error,
                    now_iso(),
                ),
            )
            self._db.commit()

    def extractions(self, job_id: str) -> dict[str, dict[str, Any]]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM extractions WHERE job_id=?", (job_id,)).fetchall()
        return {
            r["item_id"]: {
                "status": r["status"],
                "result": json.loads(r["result_json"]) if r["result_json"] else None,
                "calls": json.loads(r["calls_json"] or "[]"),
                "error": r["error"],
                "updated_at": r["updated_at"],
            }
            for r in rows
        }
