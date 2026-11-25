import io
import json

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from fieldcapture_backend import lineitems
from fieldcapture_backend.app import create_app
from fieldcapture_backend.damage import Box, DamageRead, ReferenceObject, size_from_references
from fieldcapture_backend.textmatch import agreement, normalize_date

JOB = "job-test-1"


def jpeg(color=(120, 120, 120)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (64, 64), color).save(buf, format="JPEG")
    return buf.getvalue()


def post(client, kind, item_id, meta, image=True):
    item = {"id": item_id, "kind": kind, "jobId": JOB, "metaFile": "x", "createdAt": "2026-11-25T10:00:00Z"}
    files = {"meta": ("meta.json", json.dumps(meta), "application/json")}
    if image:
        files["image"] = (f"{item_id}.jpg", jpeg(), "image/jpeg")
    return client.post(f"/v1/jobs/{JOB}/items", data={"item": json.dumps(item)}, files=files, headers={"Idempotency-Key": item_id})


def capture_meta(cid, ctype, room=None, surface=None, ocr=None, deleted=False):
    return {
        "captureId": cid, "jobId": JOB, "type": ctype, "createdAt": "2026-11-25T10:00:01Z", "transcript": f"capture {ctype}",
        "asrConfidence": 0.9, "room": room, "surface": surface,
        "evidence": {"file": f"{cid}.jpg", "width": 64, "height": 64, "sha256": "x", "source": "stream_frame"},
        "quality": {"passed": True, "issues": [], "sharpness": 100.0, "tenengrad": 1.0, "meanLuma": 120.0, "darkFraction": 0.0,
                    "clippedFraction": 0.0, "prompts": 0, "override": False, "candidatesEvaluated": 3, "thresholdsVersion": "seed"},
        "ocr": {"engine": "mlkit", "lines": [{"text": t, "box": [0, 0, 1, 1]} for t in (ocr or [])], "ms": 10},
        "timing": {"commandAt": "2026-11-25T10:00:01Z", "commandToFrameMs": 300, "commandToSavedMs": 500},
        "deleted": deleted,
    }


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(data_dir=tmp_path, extract=False))


def test_ingest_is_idempotent_and_upserts(client):
    meta = capture_meta("c1", "nameplate", ocr=["RHEEM", "MODEL XE50M06ST45U1", "SERIAL Q011504893"])
    assert post(client, "capture", "c1", meta).json()["status"] == "created"
    assert post(client, "capture", "c1", meta).json()["status"] == "duplicate"  # replay after a dropped ack
    meta["deleted"] = True
    assert post(client, "capture", "c1", meta).json()["status"] == "updated"  # "undo last"
    record = client.get(f"/v1/jobs/{JOB}/record").json()
    assert record["captures"] == []


def test_idempotency_key_must_match(client):
    meta = capture_meta("c2", "damage")
    item = {"id": "c2", "kind": "capture", "jobId": JOB}
    r = client.post(
        f"/v1/jobs/{JOB}/items",
        data={"item": json.dumps(item)},
        files={"meta": ("m.json", json.dumps(meta), "application/json")},
        headers={"Idempotency-Key": "other"},
    )
    assert r.status_code == 400


def test_offline_record_is_schema_valid_with_line_items(client):
    post(client, "job", f"job-{JOB}", {"jobId": JOB, "createdAt": "2026-11-25T10:00:00Z", "app": {"version": "0.1.0", "dat_sdk": "1.1.0"}, "device": {"phone": "OPPO CPH2823"}}, image=False)
    post(client, "capture", "np1", capture_meta("np1", "nameplate", ocr=["A.O. SMITH", "MODEL NO. GCR-50 300", "SERIAL NO 1932A012345"]))
    post(client, "capture", "dm1", capture_meta("dm1", "damage", room="kitchen", surface="north wall"))
    note = {"noteId": "n1", "jobId": JOB, "createdAt": "2026-11-25T10:01:00Z", "transcript": "note subfloor soft by the cabinet",
            "text": "subfloor soft by the cabinet", "room": "kitchen", "asrConfidence": 0.9}
    post(client, "note", "n1", note, image=False)
    post(client, "events", f"events-{JOB}", [{"at": "2026-11-25T10:00:05Z", "type": "reconnect", "detail": "x"}], image=False)
    post(client, "finalize", f"finalize-{JOB}", {"jobId": JOB, "endedAt": "2026-11-25T10:10:00Z"}, image=False)

    r = client.get(f"/v1/jobs/{JOB}/record").json()
    assert r["processing"]["schema_valid"], r["processing"].get("schema_errors")
    assert r["status"] == "complete"
    assert r["session"]["disconnects"] == 1
    np = next(c for c in r["captures"] if c["capture_id"] == "np1")
    assert np["fields"]["serial"]["value"] == "1932A012345"  # device-OCR fallback when no model ran
    assert np["extraction_status"] == "disabled"
    codes = {li["code"] for li in r["line_items"]}
    assert {"SBF-RR", "FLC-RR", "CAB-DR"} <= codes  # from "subfloor soft by the cabinet"
    assert all(li["needs_measurement"] for li in r["line_items"] if li["code"] == "SBF-RR")
    assert client.get(f"/jobs/{JOB}").status_code == 200  # back-office page renders


def test_wall_stain_quantities():
    fields = {
        "damage_type": {"value": "water_stain", "confidence": 0.9},
        "material": {"value": "drywall", "confidence": 0.8},
        "surface": {"value": "north wall", "confidence": 0.9},
        "room": {"value": "kitchen", "confidence": 0.9},
        "size": {"value": {"width_in": 24, "height_in": 36, "area_sqft": 6.0}, "confidence": 0.7},
    }
    items = {li["code"]: li for li in lineitems.derive([{"id": "d1", "type": "damage", "fields": fields}], [])}
    assert items["DRY-RR"]["quantity"] == 8.0  # 6 SF x 1.25 waste = 7.5 -> 8
    assert items["PNT-SPR"]["quantity"] == 12.0  # 2x blend
    assert items["MST-MAP"]["quantity"] == 1.0
    assert "WTR-DRY" not in items  # a dry stain doesn't need drying equipment


def test_unknown_size_is_never_invented():
    fields = {"damage_type": {"value": "water_stain", "confidence": 0.9}, "surface": {"value": "ceiling", "confidence": 0.9},
              "room": {"value": "hall", "confidence": 0.9}, "size": {"value": None, "confidence": 0.0}}
    items = {li["code"]: li for li in lineitems.derive([{"id": "d1", "type": "damage", "fields": fields}], [])}
    assert items["CLG-RR"]["quantity"] is None and items["CLG-RR"]["needs_measurement"]


def test_ocr_agreement_and_dates():
    assert agreement("XE50M06ST45U1", ["MODEL XE50M06ST45U1"]) == 1.0
    assert agreement("XE5OM06ST45U1", ["MODEL XE50M06ST45U1"]) == 0.9  # O/0 confusion
    assert agreement("ABC123", ["nothing like it"]) < 0.6
    assert normalize_date("MFG DATE 05/2019") == "2019-05"
    assert normalize_date("2019.11") == "2019-11"
    assert normalize_date("MAY 2018") == "2018-05"
    assert normalize_date("no date") is None


def test_size_from_reference_object():
    # Damage box 400x300 px; a switch plate 2.75x4.5 in spans 55x90 px => 20 px/in => 20x15 in.
    read = DamageRead(
        damage_visible=True, damage_type="water_stain", damage_type_confidence=0.9, material="drywall",
        material_confidence=0.8, surface_seen="wall", surface_confidence=0.9, severity="moderate",
        severity_confidence=0.7, damage_box=Box(x0=0.1, y0=0.1, x1=0.5, y1=0.4), damage_box_confidence=0.8,
        reference_objects=[ReferenceObject(kind="outlet_or_switch_plate", box=Box(x0=0.6, y0=0.5, x1=0.655, y1=0.59), confidence=0.9)],
        estimated_width_in=None, estimated_height_in=None, size_confidence=0.2, description="stain", issues=[],
    )
    est = size_from_references(read, 1000, 1000)
    assert est is not None
    assert est["width_in"] == pytest.approx(20.0, abs=0.2)
    assert est["height_in"] == pytest.approx(15.0, abs=0.2)
