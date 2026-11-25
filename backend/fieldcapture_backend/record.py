"""Assembles the job record (schemas/job_record.schema.json) from uploaded items + extractions."""

from __future__ import annotations

import json
import re
import statistics
from datetime import datetime
from typing import Any

from jsonschema import Draft202012Validator

from . import lineitems, note as note_mod
from .config import REPO_DIR
from .storage import Store

REVIEW_THRESHOLD = 0.6
CORE = {"nameplate": ("make", "model", "serial", "manufacture_date"), "damage": ("room", "surface", "damage_type", "size")}
SCHEMA = json.loads((REPO_DIR / "schemas" / "job_record.schema.json").read_text(encoding="utf-8"))
_VALIDATOR = Draft202012Validator(SCHEMA)


def ocr_fallback_fields(ocr_lines: list[str]) -> dict:
    """No vision model (offline back office): pull model/serial from device OCR with low confidence."""
    text = "\n".join(ocr_lines)

    def grab(label: str) -> str | None:
        m = re.search(rf"\b(?:{label})\b[\s.:#-]*(?:no\.?|number)?[\s.:#-]*([A-Z0-9][A-Z0-9-]{{3,}})", text, re.IGNORECASE)
        return m.group(1).upper() if m else None

    fields = {
        "make": {"value": ocr_lines[0].strip() if ocr_lines else None, "confidence": 0.2 if ocr_lines else 0.0, "source": "device_ocr"},
        "model": {"value": grab("model|mod|m/n"), "confidence": 0.0, "source": "device_ocr"},
        "serial": {"value": grab("serial|ser|s/n|sn"), "confidence": 0.0, "source": "device_ocr"},
        "manufacture_date": {"value": None, "confidence": 0.0, "source": "device_ocr"},
    }
    for k in ("model", "serial"):
        if fields[k]["value"]:
            fields[k]["confidence"] = 0.35
    return fields


def _flags(kind: str, fields: dict, status: str) -> list[str]:
    flags = []
    for name in CORE.get(kind, ()):
        f = fields.get(name) or {}
        if f.get("value") in (None, "") or float(f.get("confidence") or 0) < REVIEW_THRESHOLD:
            flags.append(f"low_confidence:{name}")
    if status == "error":
        flags.append("extraction_failed")
    elif status == "pending":
        flags.append("extraction_pending")
    return flags


def _ms_between(a: str | None, b: str | None) -> float | None:
    try:
        return (datetime.fromisoformat(b.replace("Z", "+00:00")) - datetime.fromisoformat(a.replace("Z", "+00:00"))).total_seconds() * 1000
    except Exception:
        return None


def assemble(store: Store, job_id: str, extract_enabled: bool = True) -> dict[str, Any]:
    items = store.items(job_id)
    ex = store.extractions(job_id)
    header = next((i.meta for i in reversed(items) if i.kind == "job"), {})
    events = next((i.meta for i in reversed(items) if i.kind == "events"), [])
    finalized = any(i.kind == "finalize" for i in items)

    captures: list[dict] = []
    notes: list[dict] = []
    calls: list[dict] = []
    latencies: list[float] = []
    review: list[dict] = []
    prompts_total = 0
    glasses_name = None

    for it in items:
        m = it.meta
        if it.kind == "capture":
            if m.get("deleted"):
                continue
            e = ex.get(it.id)
            status = e["status"] if e else ("pending" if extract_enabled else "disabled")
            ocr_lines = [ln["text"] for ln in (m.get("ocr") or {}).get("lines", [])]
            if e and e["status"] == "done" and e["result"]:
                fields = e["result"]["fields"]
            elif m["type"] == "nameplate":
                fields = ocr_fallback_fields(ocr_lines)
            else:
                slot = float(m.get("asrConfidence") or 0.5)
                fields = {
                    "room": {"value": m.get("room"), "confidence": slot if m.get("room") else 0.0, "source": "voice"},
                    "surface": {"value": m.get("surface"), "confidence": slot if m.get("surface") else 0.0, "source": "voice"},
                    "damage_type": {"value": None, "confidence": 0.0, "source": None},
                    "size": {"value": None, "confidence": 0.0, "source": None},
                }
            if e:
                calls += e.get("calls") or []
                if e["status"] == "done":
                    lat = _ms_between(m.get("createdAt"), e.get("updated_at"))
                    if lat is not None and lat >= 0:
                        latencies.append(lat)
            q = m.get("quality") or {}
            prompts_total += int(q.get("prompts") or 0)
            flags = _flags(m["type"], fields, status)
            if q.get("override"):
                flags.append("quality_override:" + ",".join(q.get("issues") or []))
            if m["type"] == "damage" and not m.get("room"):
                flags.append("room_missing")
            glasses_name = glasses_name or (m.get("glasses") or {}).get("model")
            ev = m.get("evidence") or {}
            captures.append(
                {
                    "capture_id": it.id,
                    "type": m["type"],
                    "captured_at": m.get("createdAt"),
                    "room": m.get("room"),
                    "surface": m.get("surface"),
                    "command": {
                        "transcript": m.get("transcript", ""),
                        "asr_confidence": float(m.get("asrConfidence") or 0),
                        "unparsed_slots": m.get("unparsedSlots"),
                    },
                    "evidence": {
                        "url": f"/files/{it.image_path}" if it.image_path else None,
                        "stream_frame_url": f"/files/{it.stream_image_path}" if it.stream_image_path else None,
                        "source": ev.get("source", "stream_frame"),
                        "width": ev.get("width"),
                        "height": ev.get("height"),
                        "sha256": ev.get("sha256"),
                        "photo_latency_ms": ev.get("photoLatencyMs"),
                        "photo_error": ev.get("photoError"),
                    },
                    "quality": {
                        "passed": bool(q.get("passed")),
                        "issues": q.get("issues") or [],
                        "prompts": int(q.get("prompts") or 0),
                        "override": bool(q.get("override")),
                        "sharpness": q.get("sharpness"),
                        "mean_luma": q.get("meanLuma"),
                        "thresholds_version": q.get("thresholdsVersion"),
                    },
                    "ocr_text": ocr_lines,
                    "timing": m.get("timing"),
                    "extraction_status": status,
                    "extraction_error": e.get("error") if e else None,
                    "fields": fields,
                    "review_flags": flags,
                    "replaces": m.get("replaces"),
                }
            )
            review += [{"item": it.id, "flag": f} for f in flags]
        elif it.kind == "note":
            if m.get("deleted"):
                continue
            e = ex.get(it.id)
            status = e["status"] if e else ("pending" if extract_enabled else "disabled")
            if e and e["status"] == "done" and e["result"]:
                fields = e["result"]["fields"]
            else:
                fields = note_mod.offline_fields(m.get("text", ""), m.get("room"))
            if e:
                calls += e.get("calls") or []
            flags = [f"low_confidence:{k}" for k in ("category", "component") if float((fields.get(k) or {}).get("confidence") or 0) < REVIEW_THRESHOLD]
            if status == "error":
                flags.append("extraction_failed")
            notes.append(
                {
                    "note_id": it.id,
                    "recorded_at": m.get("createdAt"),
                    "room": m.get("room"),
                    "transcript": m.get("transcript", ""),
                    "text": m.get("text", ""),
                    "asr_confidence": float(m.get("asrConfidence") or 0),
                    "extraction_status": status,
                    "fields": fields,
                    "review_flags": flags,
                }
            )
            review += [{"item": it.id, "flag": f} for f in flags]

    rooms: dict[str, dict] = {}
    for c in captures:
        r = rooms.setdefault(c["room"] or "unassigned", {"name": c["room"] or "unassigned", "captures": [], "notes": []})
        r["captures"].append(c["capture_id"])
    for n in notes:
        r = rooms.setdefault(n["room"] or "unassigned", {"name": n["room"] or "unassigned", "captures": [], "notes": []})
        r["notes"].append(n["note_id"])

    line_items = lineitems.derive(
        [{"id": c["capture_id"], "type": c["type"], "fields": c["fields"]} for c in captures],
        [{"id": n["note_id"], "fields": n["fields"], "text": n["text"]} for n in notes],
    )
    for li in line_items:
        if li["needs_measurement"]:
            review.append({"item": li["line_id"], "flag": f"needs_measurement:{li['code']}"})

    cost = sum(float(c.get("cost_usd") or 0) for c in calls)
    model_latency = [c["latency_ms"] for c in calls if c.get("latency_ms") and not c.get("from_cache")]
    record = {
        "schema_version": "1.0",
        "job_id": job_id,
        "status": "complete" if finalized else "in_progress",
        "created_at": header.get("createdAt"),
        "ended_at": header.get("endedAt"),
        "device": {
            "glasses": glasses_name,
            "mock_device_kit": True,
            "dat_sdk": (header.get("app") or {}).get("dat_sdk"),
            "phone": (header.get("device") or {}).get("phone"),
            "app_version": (header.get("app") or {}).get("version"),
        },
        "session": {
            "events": [{"at": e.get("at"), "type": e.get("type"), "detail": e.get("detail", "")} for e in events if e.get("type") in TIMELINE_TYPES],
            "disconnects": sum(1 for e in events if e.get("type") == "reconnect"),
            "retake_prompts": prompts_total,
        },
        "rooms": list(rooms.values()),
        "captures": captures,
        "notes": notes,
        "line_items": line_items,
        "review": {"needs_review": bool(review), "flags": review},
        "processing": {
            "models": sorted({c.get("model") for c in calls if c.get("model")}),
            "model_calls": len(calls),
            "input_tokens": sum(int(c.get("input_tokens") or 0) for c in calls),
            "output_tokens": sum(int(c.get("output_tokens") or 0) for c in calls),
            "cost_usd": round(cost, 5),
            "model_latency_ms_p50": statistics.median(model_latency) if model_latency else None,
            "capture_to_extracted_ms_p50": statistics.median(latencies) if latencies else None,
            "capture_to_extracted_ms_max": max(latencies) if latencies else None,
        },
    }
    errors = sorted(_VALIDATOR.iter_errors(record), key=lambda e: list(e.path))
    record["processing"]["schema_valid"] = not errors
    if errors:
        record["processing"]["schema_errors"] = [f"{list(e.path)}: {e.message}" for e in errors[:10]]
    return record


TIMELINE_TYPES = {
    "job", "walk", "phase", "session", "session_state", "session_error", "stream_state", "stream_error", "camera",
    "watchdog", "link", "reconnect", "command", "capture", "capture_refused", "retake_prompt", "note", "room",
    "device", "photo", "first_frame", "mock", "upload",
}
