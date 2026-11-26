"""Back-office load test with Locust (https://github.com/locustio/locust, MIT): many phones uploading walks at once.

Each simulated phone uploads walks the way the app does (job header, damage captures with a ~200 KB photo,
a note, the event log, finalize), as multipart POSTs with an Idempotency-Key, and replays ~10% of
uploads (a lost acknowledgement). Run it against a throwaway back office with extraction off:

    FIELDCAPTURE_PORT=8004 FIELDCAPTURE_EXTRACT=0 FIELDCAPTURE_DATA_DIR=<empty dir> uv run fieldcapture-server
    uv run --project backend --with locust locust -f tools/locustfile.py --headless -u 20 -r 5 -t 60s --host http://127.0.0.1:8004

Any non-2xx answer is a failure.
"""

from __future__ import annotations

import io
import json
import random
import uuid
from datetime import UTC, datetime

from locust import HttpUser, between, task
from PIL import Image

# One photo-sized JPEG reused by every upload: 540x960 like the mock stream, noisy so it doesn't compress
# to nothing (~200 KB).
_buf = io.BytesIO()
Image.effect_noise((540, 960), 48).convert("RGB").save(_buf, format="JPEG", quality=85)
PHOTO = _buf.getvalue()


def now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


class Phone(HttpUser):
    wait_time = between(0.5, 2.0)

    def on_start(self) -> None:
        self.new_job()

    def new_job(self) -> None:
        self.job = f"load-{uuid.uuid4()}"
        self.captures = 0
        self.post("job", f"job-{self.job}", {"jobId": self.job, "createdAt": now(), "app": {"version": "0.1.0", "dat_sdk": "1.1.0"}, "device": {"phone": "locust"}})

    def post(self, kind: str, item_id: str, meta, image: bytes | None = None) -> None:
        item = {"id": item_id, "kind": kind, "jobId": self.job, "metaFile": "m.json", "createdAt": now()}
        files = {"meta": ("m.json", json.dumps(meta), "application/json")}
        if image:
            files["image"] = (f"{item_id}.jpg", image, "image/jpeg")
            files["stream_image"] = (f"{item_id}_stream.jpg", image, "image/jpeg")
        for attempt in range(2 if random.random() < 0.1 else 1):  # ~10% replayed, like a lost ack
            with self.client.post(
                f"/v1/jobs/{self.job}/items",
                data={"item": json.dumps(item)},
                files=files,
                headers={"Idempotency-Key": item_id},
                name=f"/v1/jobs/[id]/items {kind}{' (replay)' if attempt else ''}",
                catch_response=True,
            ) as r:
                if r.status_code // 100 != 2:
                    r.failure(f"{r.status_code}: {r.text[:200]}")

    @task
    def walk_step(self) -> None:
        if self.captures < 5:
            self.captures += 1
            cid = str(uuid.uuid4())
            meta = {
                "captureId": cid, "jobId": self.job, "type": "damage", "createdAt": now(), "transcript": "capture damage kitchen north wall",
                "asrConfidence": 0.9, "room": "kitchen", "surface": "north wall",
                "evidence": {"file": f"{cid}.jpg", "width": 540, "height": 960, "sha256": "x", "source": "stream_frame"},
                "quality": {"passed": True, "issues": [], "sharpness": 900.0, "tenengrad": 1.0, "meanLuma": 120.0, "darkFraction": 0.0,
                            "clippedFraction": 0.0, "prompts": 0, "override": False, "candidatesEvaluated": 8, "thresholdsVersion": "seed"},
                "ocr": {"engine": "mlkit", "lines": [], "ms": 10},
                "timing": {"commandAt": now(), "commandToFrameMs": 300, "commandToSavedMs": 500},
            }
            self.post("capture", cid, meta, PHOTO)
        else:
            nid = str(uuid.uuid4())
            self.post("note", nid, {"noteId": nid, "jobId": self.job, "createdAt": now(), "transcript": "note subfloor soft", "text": "subfloor soft", "room": "kitchen", "asrConfidence": 0.9})
            self.post("events", f"events-{self.job}", [{"at": now(), "type": "walk", "detail": "load test"}])
            self.post("finalize", f"finalize-{self.job}", {"jobId": self.job, "endedAt": now()})
            # The back office assembles the record on read: one GET per walk, like the review page.
            self.client.get(f"/v1/jobs/{self.job}/record", name="/v1/jobs/[id]/record")
            self.new_job()
