"""Damage extraction: type, material, surface, severity and rough size, each with confidence.

Size without depth (stretch goal): the glasses have no depth sensor, so a damaged area is scaled
from reference objects of known size found in the same image (switch plates, tiles, doors, a
card or sheet of paper). The vision model only *locates* things (normalised boxes); the
arithmetic is ours and reproducible. The model's own guess is kept as a second method so the
eval can compare both against a tape measure.
"""

from __future__ import annotations

import statistics
from typing import Literal

from pydantic import BaseModel, Field

from .llm import Llm, jpeg_block

SYSTEM = """You assess building damage in photos from a restoration technician's smart glasses (720p, may be dim or slightly blurred).
Report only what is visible.

- damage_type: the main visible damage. Use "none" if no damage is visible.
- material / surface_seen: what the damaged surface is made of and what it is.
- damage_box: tight box around the damaged area, as fractions of image width/height (x0,y0,x1,y1 in 0..1). null if none visible.
- reference_objects: objects of standard size near the damage that could set the scale (electrical outlet or switch plates,
  doors, floor/wall/ceiling tiles, bricks, a credit card or a sheet of paper), each with a tight box in the same 0..1 coordinates.
  Only list objects that are clearly visible and roughly in the same plane as the damage.
- estimated_width_in / estimated_height_in: your own estimate of the damaged area's width and height in inches, or null.
- Every *_confidence is your probability (0-1) that the answer is right. Be honest: size from one photo is hard."""

USER_TEMPLATE = "Damage capture. The technician said: room = {room}, surface = {surface}."


class Box(BaseModel):
    x0: float
    y0: float
    x1: float
    y1: float


RefKind = Literal[
    "outlet_or_switch_plate", "door", "floor_tile", "wall_tile", "ceiling_tile", "brick", "baseboard",
    "sheet_of_paper", "card", "cabinet_door", "window", "other",
]


class ReferenceObject(BaseModel):
    kind: RefKind
    box: Box
    confidence: float


DamageType = Literal[
    "water_stain", "active_leak", "swelling_warping", "peeling_bubbling_paint", "mold_mildew", "crack", "hole",
    "rot", "efflorescence", "other", "none",
]
Material = Literal[
    "drywall", "plaster", "wood", "tile", "laminate", "vinyl", "carpet", "concrete", "brick", "paint",
    "cabinet_board", "ceiling_board", "other", "unknown",
]
Surface = Literal["wall", "ceiling", "floor", "cabinet", "door", "window", "fixture", "other", "unknown"]


class DamageRead(BaseModel):
    damage_visible: bool
    damage_type: DamageType
    damage_type_confidence: float
    material: Material
    material_confidence: float
    surface_seen: Surface
    surface_confidence: float
    severity: Literal["minor", "moderate", "severe"]
    severity_confidence: float
    damage_box: Box | None
    damage_box_confidence: float
    reference_objects: list[ReferenceObject]
    estimated_width_in: float | None
    estimated_height_in: float | None
    size_confidence: float
    description: str = Field(description="one short sentence a back-office estimator can read")
    issues: list[Literal["glare", "blur", "low_light", "too_close", "cut_off", "angle"]]


# Nominal sizes (inches, width x height) for reference kinds. Ambiguous kinds (tiles) carry low
# trust; the eval overrides these with the tape-measured size of the actual reference in view.
DEFAULT_REFERENCE_SIZES_IN: dict[str, tuple[float, float, float]] = {
    # kind: (short side, long side, trust 0..1)
    "outlet_or_switch_plate": (2.75, 4.5, 0.7),
    "door": (32.0, 80.0, 0.6),
    "card": (2.125, 3.37, 0.9),
    "sheet_of_paper": (8.27, 11.69, 0.85),  # A4 (US letter 8.5x11 is within 3%)
    "brick": (2.25, 8.0, 0.5),
    "ceiling_tile": (24.0, 24.0, 0.4),
    "floor_tile": (12.0, 12.0, 0.25),
    "wall_tile": (8.0, 12.0, 0.25),
}


def extract(llm: Llm, model: str, effort: str, jpeg: bytes, max_edge: int, room: str | None, surface: str | None):
    block, size = jpeg_block(jpeg, max_edge)
    res = llm.call(
        model=model,
        system=SYSTEM,
        content=[block, {"type": "text", "text": USER_TEMPLATE.format(room=room or "unknown", surface=surface or "unknown")}],
        output_model=DamageRead,
        effort=effort,
    )
    return res.parsed, res.stats.to_dict(), size


def size_from_references(
    read: DamageRead,
    image_w: int,
    image_h: int,
    reference_sizes: dict[str, tuple[float, float, float]] | None = None,
) -> dict | None:
    """Method A: scale the damage box by each reference's pixels-per-inch; median over references."""
    if not read.damage_box or not read.reference_objects:
        return None
    sizes = reference_sizes or DEFAULT_REFERENCE_SIZES_IN
    dw = max(0.0, read.damage_box.x1 - read.damage_box.x0) * image_w
    dh = max(0.0, read.damage_box.y1 - read.damage_box.y0) * image_h
    estimates = []
    for ref in read.reference_objects:
        if ref.kind not in sizes or ref.confidence < 0.3:
            continue
        short_in, long_in, trust = sizes[ref.kind]
        rw = max(1e-6, (ref.box.x1 - ref.box.x0) * image_w)
        rh = max(1e-6, (ref.box.y1 - ref.box.y0) * image_h)
        # Match the reference's longer pixel side to its longer real side.
        ref_w_in, ref_h_in = (long_in, short_in) if rw >= rh else (short_in, long_in)
        ppi_x, ppi_y = rw / ref_w_in, rh / ref_h_in
        estimates.append((dw / ppi_x, dh / ppi_y, trust * ref.confidence, ref.kind))
    if not estimates:
        return None
    w = statistics.median(e[0] for e in estimates)
    h = statistics.median(e[1] for e in estimates)
    trust = max(e[2] for e in estimates)
    return {
        "method": "reference_scaling",
        "width_in": round(w, 1),
        "height_in": round(h, 1),
        "area_sqft": round(w * h / 144.0, 2),
        "references": [e[3] for e in estimates],
        "confidence": round(min(0.9, trust * read.damage_box_confidence), 3),
    }


def size_from_model(read: DamageRead) -> dict | None:
    """Method B: the vision model's own estimate."""
    if read.estimated_width_in is None or read.estimated_height_in is None:
        return None
    w, h = read.estimated_width_in, read.estimated_height_in
    return {
        "method": "model_estimate",
        "width_in": round(w, 1),
        "height_in": round(h, 1),
        "area_sqft": round(w * h / 144.0, 2),
        "confidence": round(min(0.8, read.size_confidence), 3),
    }


def to_fields(read: DamageRead, room: str | None, surface: str | None, slot_conf: float, image_size: tuple[int, int], reference_sizes=None) -> dict:
    a = size_from_references(read, image_size[0], image_size[1], reference_sizes)
    b = size_from_model(read)
    chosen = a if a and (not b or a["confidence"] >= 0.3) else b
    q = 1.0 if read.damage_visible else 0.5
    return {
        "room": {"value": room, "confidence": round(slot_conf, 3) if room else 0.0, "source": "voice"},
        "surface": {
            "value": surface or (read.surface_seen if read.surface_seen != "unknown" else None),
            "confidence": round(slot_conf, 3) if surface else round(read.surface_confidence * 0.8, 3),
            "source": "voice" if surface else "vision_model",
        },
        "damage_type": {"value": read.damage_type, "confidence": round(read.damage_type_confidence * q, 3), "source": "vision_model"},
        "material": {"value": read.material, "confidence": round(read.material_confidence * q, 3), "source": "vision_model"},
        "severity": {"value": read.severity, "confidence": round(read.severity_confidence * q, 3), "source": "vision_model"},
        "size": {
            "value": {k: chosen[k] for k in ("width_in", "height_in", "area_sqft")} if chosen else None,
            "confidence": chosen["confidence"] if chosen else 0.0,
            "source": chosen["method"] if chosen else None,
            "alternatives": [x for x in (a, b) if x],
        },
        "description": {"value": read.description, "confidence": 0.7, "source": "vision_model"},
    }
