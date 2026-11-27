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


@pytest.mark.parametrize(
    ("item", "meta"),
    [
        ("0", "{}"),
        ("null", "null"),
        ('{"id": "x", "kind": "note", "jobId": "j"}', "[]"),
        ('{"id": "x", "kind": "events", "jobId": "j"}', "{}"),
        ('{"id": "x", "kind": "events", "jobId": "j"}', "[0]"),  # would crash the record page later
    ],
)
def test_json_that_is_not_an_object_is_a_400_not_a_crash(client, item, meta):
    # Found by Schemathesis (2026-11-26): item="0" raised AttributeError -> 500.
    r = client.post("/v1/jobs/j/items", data={"item": item}, files={"meta": ("m.json", meta, "application/json")})
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


def test_job_list_shows_real_jobs_and_hides_empty_ones(client):
    post(client, "capture", "dm9", capture_meta("dm9", "damage", room="kitchen", surface="wall"))
    empty = {"id": "job-empty", "kind": "job", "jobId": "empty-job", "metaFile": "x", "createdAt": "2026-11-25T09:00:00Z"}
    client.post("/v1/jobs/empty-job/items", data={"item": json.dumps(empty)},
                files={"meta": ("m.json", json.dumps({"jobId": "empty-job", "createdAt": "2026-11-25T09:00:00Z"}), "application/json")},
                headers={"Idempotency-Key": "job-empty"})
    page = client.get("/").text
    assert f"/jobs/{JOB}" in page and "1 capture · 0 notes" in page and "kitchen" in page
    assert "/jobs/empty-job" not in page and "1 empty job (nothing captured) not shown" in page


def _items(dtype, material, surface, area=None, size_conf=0.7):
    fields = {
        "damage_type": {"value": dtype, "confidence": 0.9},
        "material": {"value": material, "confidence": 0.8},
        "surface": {"value": surface, "confidence": 0.9},
        "room": {"value": "kitchen", "confidence": 0.9},
        "size": {"value": {"width_in": 24, "height_in": 36, "area_sqft": area} if area else None, "confidence": size_conf if area else 0.0},
    }
    return {li["code"]: li for li in lineitems.derive([{"id": "d1", "type": "damage", "fields": fields}], [])}


def test_dry_wall_stain_is_stain_block_and_paint_not_replacement():
    items = _items("water_stain", "drywall", "north wall", area=6.0)
    assert "DRY-RR" not in items  # a dry, intact stain is sealed and painted, not cut out
    assert items["PNT-SPR"]["quantity"] == 12.0 and not items["PNT-SPR"]["needs_measurement"]  # 6 SF x 2 blend
    assert items["MST-MAP"]["quantity"] == 1.0
    assert "WTR-DRY" not in items  # a dry stain doesn't need drying equipment


def test_swollen_drywall_is_replaced_and_dried():
    items = _items("swelling_warping", "drywall", "north wall", area=6.0)
    assert items["DRY-RR"]["quantity"] == 8.0  # 6 SF x 1.25 waste = 7.5 -> 8
    assert items["PNT-SPR"]["quantity"] == 12.0
    assert items["WTR-DRY"]["assumption"]


def test_plaster_seepage_is_repainted_never_drywall_and_a_guessed_size_must_be_measured():
    # The first real job: peeling paint on a plaster wall, size guessed by the model at 0.4.
    items = _items("peeling_bubbling_paint", "plaster", "wall", area=25.0, size_conf=0.4)
    assert "DRY-RR" not in items
    assert items["PNT-SPR"]["quantity"] == 50.0 and items["PNT-SPR"]["needs_measurement"]
    assert "measure" in items["PNT-SPR"]["basis"]
    assert items["MST-MAP"]["quantity"] == 1.0  # seepage means a moisture source to find


def test_hole_in_plaster_is_left_for_the_estimator_to_scope():
    items = _items("hole", "plaster", "wall", area=1.0)
    assert "DRY-RR" not in items and items["PNT-SPR"]["needs_measurement"]


def test_concrete_floor_stain_has_no_floor_covering_to_replace():
    items = _items("water_stain", "concrete", "floor", area=7.5)
    assert "FLC-RR" not in items
    assert items["MST-MAP"]["quantity"] == 1.0


def test_tile_wall_is_not_repainted():
    items = _items("water_stain", "tile", "wall", area=4.0)
    assert "PNT-SPR" not in items and "DRY-RR" not in items


def test_unknown_size_is_never_invented():
    items = _items("water_stain", None, "ceiling")
    assert items["PNT-SPR"]["quantity"] is None and items["PNT-SPR"]["needs_measurement"]
    assert items["INS-RR"]["quantity"] is None and items["INS-RR"]["assumption"]  # board ceiling: check above


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
