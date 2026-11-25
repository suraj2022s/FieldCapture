"""Voice note -> structured fields. Claude Haiku when online; a keyword parser as the offline fallback."""

from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel

from .llm import Llm

SYSTEM = """You turn a restoration technician's spoken site note into structured fields for an estimator.
The note was transcribed by speech recognition and may contain recognition errors.
Only use what the note says; do not add facts. Use null for anything not stated.
Each *_confidence is your probability (0-1) that the field is right."""


class NoteRead(BaseModel):
    category: Literal[
        "damage_observation", "moisture", "mold_odor", "structural", "safety_hazard", "access",
        "customer_info", "equipment", "measurement", "other",
    ]
    category_confidence: float
    component: str | None
    component_confidence: float
    condition: str | None
    condition_confidence: float
    location: str | None
    location_confidence: float
    severity: Literal["low", "medium", "high", "unknown"]
    severity_confidence: float
    measurement_value: float | None
    measurement_unit: str | None
    measurement_confidence: float
    follow_up_needed: bool
    summary: str


def extract(llm: Llm, model: str, effort: str, text: str, room: str | None) -> tuple[NoteRead, dict]:
    res = llm.call(
        model=model,
        system=SYSTEM,
        content=[{"type": "text", "text": f"Room context: {room or 'unknown'}\nNote: {text}"}],
        output_model=NoteRead,
        effort=effort,
        max_tokens=4000,
    )
    return res.parsed, res.stats.to_dict()


def to_fields(read: NoteRead, room: str | None) -> dict:
    return {
        "room": {"value": room, "confidence": 0.8 if room else 0.0, "source": "context"},
        "category": {"value": read.category, "confidence": read.category_confidence, "source": "language_model"},
        "component": {"value": read.component, "confidence": read.component_confidence if read.component else 0.0, "source": "language_model"},
        "condition": {"value": read.condition, "confidence": read.condition_confidence if read.condition else 0.0, "source": "language_model"},
        "location": {"value": read.location, "confidence": read.location_confidence if read.location else 0.0, "source": "language_model"},
        "severity": {"value": read.severity, "confidence": read.severity_confidence, "source": "language_model"},
        "measurement": {
            "value": {"value": read.measurement_value, "unit": read.measurement_unit} if read.measurement_value is not None else None,
            "confidence": read.measurement_confidence if read.measurement_value is not None else 0.0,
            "source": "language_model",
        },
        "follow_up_needed": {"value": read.follow_up_needed, "confidence": 0.7, "source": "language_model"},
        "summary": {"value": read.summary, "confidence": 0.7, "source": "language_model"},
    }


# ---------------------------------------------------------------------- offline fallback

COMPONENTS = [
    "subfloor", "floor", "drywall", "wall", "ceiling", "cabinet", "vanity", "baseboard", "carpet", "pad", "insulation",
    "water heater", "pipe", "supply line", "drain", "toilet", "sink", "tub", "shower", "window", "door", "roof",
    "joist", "stud", "tile", "laminate", "hardwood", "outlet",
]
CONDITIONS = {
    "soft": "damage_observation", "spongy": "damage_observation", "wet": "moisture", "damp": "moisture",
    "soaked": "moisture", "saturated": "moisture", "swollen": "damage_observation", "warped": "damage_observation",
    "seepage": "moisture", "peeling": "damage_observation", "flaking": "damage_observation",
    "stained": "damage_observation", "cracked": "damage_observation", "leaking": "moisture", "dripping": "moisture",
    "moldy": "mold_odor", "mold": "mold_odor", "musty": "mold_odor", "smell": "mold_odor", "rotten": "structural",
    "sagging": "structural", "rusted": "equipment", "corroded": "equipment", "exposed": "safety_hazard",
    "sparking": "safety_hazard",
}


def offline_fields(text: str, room: str | None) -> dict:
    """Keyword extraction used with no signal or when the model call fails. Deliberately low confidence."""
    t = text.lower()
    component = next((c for c in sorted(COMPONENTS, key=len, reverse=True) if re.search(rf"\b{re.escape(c)}\b", t)), None)
    condition = next((c for c in CONDITIONS if re.search(rf"\b{c}\b", t)), None)
    m = re.search(r"\b(?:by|near|under|behind|above|below|next to|beside|around) the ([a-z ]{3,30})", t)
    location = m.group(0) if m else None
    return {
        "room": {"value": room, "confidence": 0.8 if room else 0.0, "source": "context"},
        "category": {"value": CONDITIONS.get(condition or "", "other"), "confidence": 0.4, "source": "keyword_rules"},
        "component": {"value": component, "confidence": 0.5 if component else 0.0, "source": "keyword_rules"},
        "condition": {"value": condition, "confidence": 0.5 if condition else 0.0, "source": "keyword_rules"},
        "location": {"value": location, "confidence": 0.4 if location else 0.0, "source": "keyword_rules"},
        "severity": {"value": "unknown", "confidence": 0.3, "source": "keyword_rules"},
        "measurement": {"value": None, "confidence": 0.0, "source": "keyword_rules"},
        "follow_up_needed": {"value": True, "confidence": 0.3, "source": "keyword_rules"},
        "summary": {"value": text, "confidence": 0.5, "source": "transcript"},
    }
