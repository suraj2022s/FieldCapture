"""Property tests (Hypothesis, MPL-2.0): rules that must hold for *every* observation, not just the examples.

The extractors are a vision model; whatever it returns, the line items must stay honest: real codes,
no invented or non-positive quantities, guessed sizes always flagged for measuring, the same result
whatever order the captures arrive in. Profile "ci" (default) runs 300 examples per test;
HYPOTHESIS_PROFILE=quick runs 30 (used by mutation testing).
"""

from __future__ import annotations

import math
import os
import re

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from fieldcapture_backend import lineitems
from fieldcapture_backend.damage import Box, DamageRead, ReferenceObject, size_from_model, size_from_references
from fieldcapture_backend.textmatch import agreement, norm_code, normalize_date

settings.register_profile("ci", max_examples=300, deadline=None, suppress_health_check=[HealthCheck.too_slow])
settings.register_profile("quick", max_examples=30, deadline=None, suppress_health_check=[HealthCheck.too_slow])
settings.load_profile(os.environ.get("HYPOTHESIS_PROFILE", "ci"))

DAMAGE_TYPES = ["water_stain", "active_leak", "swelling_warping", "peeling_bubbling_paint", "mold_mildew", "crack", "hole", "rot", "efflorescence", "other", "none"]
MATERIALS = ["drywall", "plaster", "wood", "tile", "laminate", "vinyl", "carpet", "concrete", "brick", "paint", "cabinet_board", "ceiling_board", "other", "unknown", None]
# Surfaces come from the tech's own words (voice slot), so free text too.
SURFACES = st.one_of(
    st.sampled_from(["wall", "north wall", "ceiling", "floor", "subfloor", "cabinet", "under the sink", "door", "window", "baseboard", "countertop"]),
    st.text(max_size=20),
    st.none(),
)
ROOMS = st.sampled_from(["kitchen", "bathroom", "hallway", None])
conf = st.floats(0.0, 1.0)
# Sizes come from the model's estimate or reference scaling: include zero and negative (a model can
# return any float), and huge.
dims = st.one_of(st.none(), st.floats(-10.0, 5000.0, allow_nan=False, allow_infinity=False))


def field(values):
    return st.fixed_dictionaries({"value": values, "confidence": conf})


size_value = st.one_of(st.none(), st.fixed_dictionaries({"area_sqft": dims, "width_in": dims}))
damage_fields = st.fixed_dictionaries(
    {
        "damage_type": field(st.sampled_from(DAMAGE_TYPES)),
        "material": field(st.sampled_from(MATERIALS)),
        "surface": field(SURFACES),
        "room": field(ROOMS),
        "size": field(size_value),
    }
)
note_fields = st.fixed_dictionaries(
    {
        "room": field(ROOMS),
        "component": field(st.one_of(st.none(), st.sampled_from(["subfloor", "cabinet", "water heater", "ceiling"]))),
        "condition": field(st.one_of(st.none(), st.sampled_from(["soft", "wet", "rusted", "fine", "sagging"]))),
        "category": field(st.one_of(st.none(), st.sampled_from(["moisture", "mold_odor", "structural", "other"]))),
    }
)
note_text = st.one_of(
    st.sampled_from(["subfloor soft by the cabinet", "water heater leaking at the base", "musty smell", "all fine here", "wet carpet"]),
    st.text(max_size=60),
)
captures = st.lists(st.builds(lambda i, f: {"id": f"c{i}", "type": "damage", "fields": f}, st.integers(0, 10**6), damage_fields), max_size=6, unique_by=lambda c: c["id"])
notes = st.lists(st.builds(lambda i, f, t: {"id": f"n{i}", "fields": f, "text": t}, st.integers(0, 10**6), note_fields, note_text), max_size=4, unique_by=lambda n: n["id"])


@given(captures, notes)
def test_line_items_are_always_honest(caps, ns):
    items = lineitems.derive(caps, ns)
    keys = [(i["code"], i["room"]) for i in items]
    assert len(keys) == len(set(keys)), "one line per code and room"
    for i in items:
        assert i["code"] in lineitems.CODES
        q = i["quantity"]
        assert q is None or (math.isfinite(q) and q > 0), f"{i['code']}: quantity {q}"
        if q is None:
            assert i["needs_measurement"], f"{i['code']} has no quantity but isn't flagged for measuring"
        assert 0.0 <= i["confidence"] <= 1.0
        assert i["basis"] and i["evidence"]


@given(damage_fields)
def test_a_guessed_size_is_always_flagged_for_measuring(f):
    sz = f["size"]
    if not isinstance(sz["value"], dict) or sz["confidence"] >= lineitems.SIZE_TRUST:
        return
    for i in lineitems.derive([{"id": "c1", "type": "damage", "fields": f}], []):
        # One reading per room / a fixed drying time don't come from the photo's size.
        if i["code"] in ("MST-MAP", "WTR-DRY") or i["quantity"] is None:
            continue
        assert i["needs_measurement"], f"{i['code']} quantity {i['quantity']} from a size guessed at {sz['confidence']:.2f} confidence"


@given(st.lists(damage_fields, min_size=1, max_size=5))
def test_nothing_is_invented_from_no_damage(fs):
    for f in fs:
        f["damage_type"]["value"] = "none"
    caps = [{"id": f"c{i}", "type": "damage", "fields": f} for i, f in enumerate(fs)]
    assert lineitems.derive(caps, []) == []


@given(captures)
def test_capture_order_does_not_change_quantities(caps):
    a = {(i["code"], i["room"]): (i["quantity"], i["needs_measurement"]) for i in lineitems.derive(caps, [])}
    b = {(i["code"], i["room"]): (i["quantity"], i["needs_measurement"]) for i in lineitems.derive(list(reversed(caps)), [])}
    assert a == b


# ------------------------------------------------------------------ size without depth

unit = st.floats(0.0, 1.0)


@st.composite
def boxes(draw):
    x0, x1 = sorted((draw(unit), draw(unit)))
    y0, y1 = sorted((draw(unit), draw(unit)))
    return Box(x0=x0, y0=y0, x1=x1, y1=y1)


def read_with(damage_box, refs, w=None, h=None):
    return DamageRead(
        damage_visible=True, damage_type="water_stain", damage_type_confidence=0.9, material="plaster", material_confidence=0.8,
        surface_seen="wall", surface_confidence=0.9, severity="minor", severity_confidence=0.6, damage_box=damage_box,
        damage_box_confidence=0.8, reference_objects=refs, estimated_width_in=w, estimated_height_in=h, size_confidence=0.5,
        description="x", issues=[],
    )


refs = st.lists(
    st.builds(ReferenceObject, kind=st.sampled_from(["sheet_of_paper", "card", "outlet_or_switch_plate", "door", "floor_tile", "other"]), box=boxes(), confidence=conf),
    max_size=4,
)


@given(boxes(), refs, st.integers(100, 4000), st.integers(100, 4000), st.floats(0.25, 4.0))
def test_reference_scaling_is_sane_and_resolution_independent(box, rs, w, h, k):
    a = size_from_references(read_with(box, rs), w, h)
    if a is None:
        return
    for key in ("width_in", "height_in", "area_sqft", "confidence"):
        assert math.isfinite(a[key]) and a[key] >= 0, (key, a[key])
    assert a["confidence"] <= 0.9
    # The same photo at another resolution must give the same size (pixels cancel out).
    b = size_from_references(read_with(box, rs), max(1, round(w * k)), max(1, round(h * k)))
    assert b is not None
    assert math.isclose(a["area_sqft"], b["area_sqft"], rel_tol=0.05, abs_tol=0.05)


@given(dims, dims)
def test_model_size_never_gives_a_negative_or_empty_area(w, h):
    s = size_from_model(read_with(None, [], w, h))
    if s is not None:
        assert s["area_sqft"] > 0 and s["width_in"] > 0 and s["height_in"] > 0, s


# ------------------------------------------------------------------ text matching on nameplates


@given(st.text(max_size=40))
def test_dates_are_well_formed_or_absent(raw):
    d = normalize_date(raw)
    assert d is None or re.fullmatch(r"(19[89]\d|20[0-4]\d)(-(0[1-9]|1[0-2]))?", d), d


@given(st.text(max_size=30), st.lists(st.text(max_size=30), max_size=5))
def test_agreement_is_a_bounded_score(value, lines):
    a = agreement(value, lines)
    assert 0.0 <= a <= 1.0


@given(st.text(max_size=30))
def test_a_reading_agrees_fully_with_itself(value):
    if norm_code(value):
        assert agreement(value, [value]) == 1.0
    assert norm_code(norm_code(value)) == norm_code(value)
