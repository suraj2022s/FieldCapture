"""Observation -> draft line items. Deterministic and auditable: every item cites its evidence and says why.

The model only *observes* (damage type, material, size). Turning observations into scope is a
business rule an estimator must be able to read, test and argue with, so it lives here, not in a prompt.
Unknown quantities stay null with needs_measurement=true: nothing is invented.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from typing import Any

from .config import REPO_DIR

CODES: dict[str, dict[str, str]] = {
    c["code"]: c for c in json.loads((REPO_DIR / "schemas" / "line_item_codes.json").read_text(encoding="utf-8"))["codes"]
}
ORDER = list(CODES)

WATER_TYPES = {"water_stain", "active_leak", "swelling_warping", "peeling_bubbling_paint", "mold_mildew", "rot", "efflorescence"}
WET_TYPES = {"active_leak", "swelling_warping"}
WALL_MATERIALS = {"drywall", "plaster", "paint", "unknown", "other"}
SOFT_CONDITIONS = {"soft", "spongy", "rotten", "rotted", "sagging", "wet", "soaked", "saturated", "swollen", "warped"}

MIN_PATCH_SF = 4  # smallest practical drywall patch
WASTE = 1.25  # cut to studs / sheet edges
PAINT_BLEND = 2.0  # paint past the patch so it blends
DEFAULT_DRY_DAYS = 3  # IICRC S500 typical drying duration; flagged as an assumption


@dataclass
class Draft:
    code: str
    room: str | None
    quantity: float | None
    confidence: float
    basis: str
    evidence: list[str] = field(default_factory=list)
    needs_measurement: bool = False
    assumption: bool = False

    def key(self) -> tuple[str, str | None]:
        return self.code, self.room


def _ceil(x: float | None) -> float | None:
    return None if x is None else float(math.ceil(x - 1e-9))


def _val(fields: dict, name: str):
    f = fields.get(name) or {}
    return f.get("value"), float(f.get("confidence") or 0.0)


def from_damage(capture_id: str, fields: dict) -> list[Draft]:
    dtype, c_type = _val(fields, "damage_type")
    material, _ = _val(fields, "material")
    surface, c_surf = _val(fields, "surface")
    room, _ = _val(fields, "room")
    size, c_size = _val(fields, "size")
    area = size.get("area_sqft") if isinstance(size, dict) else None
    width_in = size.get("width_in") if isinstance(size, dict) else None
    if not dtype or dtype == "none":
        return []
    surf = (surface or "").lower()
    out: list[Draft] = []
    ev = [capture_id]
    base_conf = min(c_type, max(c_surf, 0.5))

    def qty(min_sf: float, factor: float) -> tuple[float | None, bool]:
        return (_ceil(max(min_sf, area * factor)), False) if area is not None else (None, True)

    water = dtype in WATER_TYPES
    if "wall" in surf and (material in WALL_MATERIALS or material is None):
        q, nm = qty(MIN_PATCH_SF, WASTE)
        out.append(Draft("DRY-RR", room, q, round(min(base_conf, c_size if q else base_conf), 3), f"{dtype} on wall drywall, {area or '?'} SF x {WASTE} waste (min {MIN_PATCH_SF} SF)", ev, nm))
        q2, nm2 = qty(8, PAINT_BLEND)
        out.append(Draft("PNT-SPR", room, q2, round(base_conf * 0.9, 3), "re-paint patched area plus blend", ev, nm2))
    elif "ceiling" in surf:
        q, nm = qty(MIN_PATCH_SF, WASTE)
        out.append(Draft("CLG-RR", room, q, round(min(base_conf, c_size if q else base_conf), 3), f"{dtype} on ceiling, {area or '?'} SF x {WASTE}", ev, nm))
        q2, nm2 = qty(8, PAINT_BLEND)
        out.append(Draft("PNT-SPR", room, q2, round(base_conf * 0.9, 3), "stain-block and paint ceiling", ev, nm2))
        if water:
            qi, nmi = qty(MIN_PATCH_SF, WASTE)
            out.append(Draft("INS-RR", room, qi, round(base_conf * 0.5, 3), "consider: wet insulation above a stained ceiling", ev, nmi))
    elif "floor" in surf:
        q, nm = qty(10, WASTE)
        out.append(Draft("FLC-RR", room, q, round(base_conf * 0.8, 3), f"{dtype} on floor covering", ev, nm))
        if dtype in {"swelling_warping", "rot"}:
            out.append(Draft("SBF-RR", room, None, round(base_conf * 0.5, 3), "consider: subfloor under swollen/rotten flooring", ev, True))
        if dtype == "active_leak":
            q3, nm3 = qty(10, 1.5)
            out.append(Draft("WTR-EXT", room, q3, round(base_conf * 0.8, 3), "standing/active water on floor", ev, nm3))
    elif "cabinet" in surf:
        out.append(Draft("CAB-DR", room, None, round(base_conf * 0.7, 3), f"{dtype} at base cabinet; measure cabinet run", ev, True))

    if dtype == "mold_mildew":
        q, nm = (area, False) if area is not None else (None, True)
        basis = "visible mold" + (" (>10 SF: containment)" if area and area > 10 else "")
        out.append(Draft("MLD-REM", room, _ceil(q), round(base_conf, 3), basis, ev, nm))
    if dtype in WET_TYPES or dtype == "mold_mildew":
        q, nm = qty(MIN_PATCH_SF, 1.0)
        out.append(Draft("ANT-APP", room, q, round(base_conf * 0.8, 3), "treat wet / microbial-affected surface", ev, nm))
    if water:
        out.append(Draft("MST-MAP", room, 1.0, round(base_conf * 0.9, 3), "water-related damage in room", ev))
    if dtype in WET_TYPES:
        out.append(Draft("WTR-DRY", None, float(DEFAULT_DRY_DAYS), 0.4, f"wet materials; {DEFAULT_DRY_DAYS}-day drying assumed (verify with moisture readings)", ev, assumption=True))
    if width_in and "wall" in surf and dtype in WET_TYPES:
        out.append(Draft("BSB-RR", room, _ceil(width_in / 12.0), round(base_conf * 0.5, 3), "consider: baseboard below wet wall, width of damage", ev, assumption=True))
    return out


def from_note(note_id: str, fields: dict, text: str) -> list[Draft]:
    room, _ = _val(fields, "room")
    component, c_comp = _val(fields, "component")
    condition, c_cond = _val(fields, "condition")
    category, c_cat = _val(fields, "category")
    t = (text or "").lower()
    comp = (component or "").lower()
    cond = (condition or "").lower()
    conf = round(min(max(c_comp, 0.3), max(c_cond, 0.3)), 3)
    ev = [note_id]
    out: list[Draft] = []
    if "subfloor" in comp or "subfloor" in t:
        if cond in SOFT_CONDITIONS or any(w in t for w in SOFT_CONDITIONS):
            out.append(Draft("SBF-RR", room, None, conf, f"note: subfloor {cond or 'damaged'}; measure affected area", ev, True))
            out.append(Draft("FLC-RR", room, None, round(conf * 0.8, 3), "floor covering must come up to reach subfloor", ev, True))
            if "cabinet" in t or "vanity" in t:
                out.append(Draft("CAB-DR", room, None, round(conf * 0.8, 3), "subfloor damage at cabinet: detach and reset", ev, True))
    if category == "mold_odor" or "mold" in t or "musty" in t:
        out.append(Draft("MLD-REM", room, None, round(c_cat * 0.6, 3), "note reports mold/odor; investigate extent", ev, True))
    if category == "moisture" or any(w in t for w in ("wet", "damp", "leak", "standing water")):
        out.append(Draft("MST-MAP", room, 1.0, round(max(c_cat, 0.4) * 0.8, 3), "note reports moisture", ev))
    if "water heater" in t and any(w in t for w in ("leak", "rust", "corrod", "pan", "dripping")):
        out.append(Draft("WH-RR", room, 1.0, 0.4, "consider: note reports a leaking/corroded water heater", ev, assumption=True))
    return out


def derive(captures: list[dict[str, Any]], notes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """captures/notes: [{"id":..., "type":..., "fields": {...}, "text": ...}]. Returns merged draft line items."""
    drafts: list[Draft] = []
    for c in captures:
        if c.get("type") == "damage":
            drafts += from_damage(c["id"], c.get("fields") or {})
    for n in notes:
        drafts += from_note(n["id"], n.get("fields") or {}, n.get("text", ""))
    # Water-heater replacement is only suggested when a nameplate for one exists AND evidence of a leak.
    has_heater = any((c.get("fields") or {}).get("equipment_type", {}).get("value") == "water_heater" for c in captures if c.get("type") == "nameplate")
    drafts = [d for d in drafts if d.code != "WH-RR" or has_heater]

    merged: dict[tuple[str, str | None], Draft] = {}
    for d in drafts:
        k = d.key()
        if d.code in ("MST-MAP", "WTR-DRY"):
            # one per room / one per job, not summed
            if k not in merged:
                merged[k] = d
            else:
                merged[k].evidence = sorted(set(merged[k].evidence + d.evidence))
                merged[k].confidence = max(merged[k].confidence, d.confidence)
            continue
        if k not in merged:
            merged[k] = Draft(d.code, d.room, d.quantity, d.confidence, d.basis, list(d.evidence), d.needs_measurement, d.assumption)
        else:
            m = merged[k]
            m.quantity = None if (m.quantity is None or d.quantity is None) else m.quantity + d.quantity
            m.needs_measurement = m.needs_measurement or d.needs_measurement
            m.confidence = round(min(m.confidence, d.confidence), 3)
            m.evidence = sorted(set(m.evidence + d.evidence))
            m.basis = f"{m.basis}; {d.basis}"

    items = sorted(merged.values(), key=lambda d: (ORDER.index(d.code), d.room or ""))
    return [
        {
            "line_id": f"L{i + 1:02d}",
            "code": d.code,
            "description": CODES[d.code]["description"],
            "unit": CODES[d.code]["unit"],
            "room": d.room,
            "quantity": d.quantity,
            "needs_measurement": d.needs_measurement,
            "confidence": round(d.confidence, 3),
            "basis": d.basis,
            "assumption": d.assumption,
            "evidence": d.evidence,
            "status": "draft",
        }
        for i, d in enumerate(items)
    ]
