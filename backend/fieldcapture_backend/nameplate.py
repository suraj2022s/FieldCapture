"""Nameplate extraction: make, model, serial, manufacture date (+ capacity/fuel/voltage), each with confidence."""

from __future__ import annotations

import math
from typing import Literal

from pydantic import BaseModel, Field

from .llm import Llm, jpeg_block
from .textmatch import agreement, normalize_date, plausible_model, plausible_serial

SYSTEM = """You read equipment nameplates (rating / data plates) photographed by a field technician's smart glasses.
Images are often small, dim, angled or partly glary.

Rules:
- Transcribe exactly what is printed. Never guess, complete or infer a value that is not legible. Use null instead.
- "evidence" is the verbatim text on the plate the value came from (including its label, e.g. "SERIAL NO. 1234").
- "confidence" is your probability (0-1) that the value is exactly right, character for character.
  Be strict: if any character is uncertain, confidence must be below 0.6.
- make = brand or manufacturer name. model = model number. serial = serial number.
- manufacture_date = the manufacture date as printed (it may be absent; many plates only encode it in the serial).
- If this is not a nameplate at all, set equipment_type to "not_a_nameplate" and all values to null."""

USER = "Read this nameplate."


class Reading(BaseModel):
    value: str | None = Field(description="the value exactly as printed, or null if not legible")
    evidence: str | None = Field(description="verbatim plate text the value was read from")
    confidence: float = Field(description="0-1 probability the value is exactly right")


EquipmentType = Literal[
    "water_heater", "furnace", "air_handler", "ac_outdoor_unit", "ac_indoor_unit", "heat_pump", "boiler",
    "refrigerator", "freezer", "washer", "dryer", "dishwasher", "range_oven", "microwave", "electrical_panel",
    "pump", "inverter_ups", "other", "not_a_nameplate",
]


class NameplateRead(BaseModel):
    equipment_type: EquipmentType
    make: Reading
    model: Reading
    serial: Reading
    manufacture_date: Reading
    capacity: Reading = Field(description="e.g. 50 GAL, 1.5 TON, 3 kW")
    fuel_or_power: Reading = Field(description="e.g. natural gas, propane, electric")
    voltage: Reading
    legibility: Literal["good", "partial", "poor"]
    issues: list[Literal["glare", "blur", "cut_off", "angle", "low_light", "too_small", "occluded"]]


CORE_FIELDS = ("make", "model", "serial", "manufacture_date")


def extract(llm: Llm, model: str, effort: str, jpeg: bytes, max_edge: int) -> tuple[NameplateRead, dict]:
    block, _ = jpeg_block(jpeg, max_edge)
    res = llm.call(
        model=model,
        system=SYSTEM,
        content=[block, {"type": "text", "text": USER}],
        output_model=NameplateRead,
        effort=effort,
    )
    return res.parsed, res.stats.to_dict()


def _sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))


def field_confidence(
    name: str,
    reading: Reading,
    ocr_lines: list[str],
    legibility: str,
    quality_passed: bool,
    coef: dict[str, float] | None = None,
) -> tuple[float, dict[str, float]]:
    """
    Final confidence for one field: a logistic model over independent signals.
    [coef] comes from eval calibration (eval/calibrate.py); the defaults are pre-data priors.
    Returns (confidence, features) so the eval can refit the coefficients.
    """
    f = {
        "self": max(0.0, min(1.0, reading.confidence)),
        "ocr_agree": agreement(reading.value, ocr_lines) if name in ("model", "serial", "make") else 0.5,
        "format_ok": 1.0
        if (name == "serial" and plausible_serial(reading.value))
        or (name == "model" and plausible_model(reading.value))
        or (name == "manufacture_date" and normalize_date(reading.value) is not None)
        or name == "make"
        else 0.0,
        "legible": {"good": 1.0, "partial": 0.5, "poor": 0.0}[legibility],
        "qc_pass": 1.0 if quality_passed else 0.0,
    }
    c = coef or {"bias": -3.0, "self": 3.0, "ocr_agree": 2.0, "format_ok": 1.0, "legible": 1.0, "qc_pass": 0.5}
    z = c.get("bias", 0.0) + sum(c.get(k, 0.0) * v for k, v in f.items())
    return round(_sigmoid(z), 3), f


def to_fields(
    read: NameplateRead,
    ocr_lines: list[str],
    quality_passed: bool,
    coef: dict[str, float] | None = None,
) -> dict:
    """Job-record fields: {name: {value, confidence, source, evidence, features}}."""
    out: dict[str, dict] = {}
    for name in ("make", "model", "serial", "manufacture_date", "capacity", "fuel_or_power", "voltage"):
        r: Reading = getattr(read, name)
        value = r.value.strip() if r.value else None
        if value is None:
            out[name] = {"value": None, "confidence": 0.0, "source": "vision_model", "evidence": None}
            continue
        conf, feats = field_confidence(name, r, ocr_lines, read.legibility, quality_passed, coef)
        entry = {"value": value, "confidence": conf, "source": "vision_model", "evidence": r.evidence, "features": feats}
        if name == "manufacture_date":
            entry["normalized"] = normalize_date(value)
        if name in ("model", "serial"):
            entry["value"] = value.upper()
        out[name] = entry
    out["equipment_type"] = {"value": read.equipment_type, "confidence": 0.9 if read.legibility != "poor" else 0.6, "source": "vision_model"}
    return out
