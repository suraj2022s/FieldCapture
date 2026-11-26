"""Every category in the line-item rules, one by one.

Written after mutation testing (mutmut, 2026-11-26) showed which rules no test pinned down: deleting
"active_leak" from the water types, "laminate" from the floor coverings or "rotted" from the soft
words, changing drying from 3 to 4 days, dropping the room or the baseboard line, all passed the suite.
"""

from __future__ import annotations

import pytest

from fieldcapture_backend import lineitems as li


def dmg(dtype, material="drywall", surface="north wall", room="kitchen", area=10.0, width=24.0, size_conf=0.9, conf=0.9, surface_conf=None):
    return {
        "id": "c1",
        "type": "damage",
        "fields": {
            "damage_type": {"value": dtype, "confidence": conf},
            "material": {"value": material, "confidence": conf},
            "surface": {"value": surface, "confidence": conf if surface_conf is None else surface_conf},
            "room": {"value": room, "confidence": conf},
            "size": {"value": {"area_sqft": area, "width_in": width}, "confidence": size_conf},
        },
    }


def note(text, component="subfloor", condition=None, category="other"):
    return {
        "id": "n1",
        "text": text,
        "fields": {
            "room": {"value": "kitchen", "confidence": 0.9},
            "component": {"value": component, "confidence": 0.9},
            "condition": {"value": condition, "confidence": 0.9 if condition else 0.0},
            "category": {"value": category, "confidence": 0.6},
        },
    }


def by_code(caps=(), notes=()):
    return {i["code"]: i for i in li.derive(list(caps), list(notes))}


# The specification, written out here on purpose: a test that read these lists from lineitems would
# follow the code wherever it went (mutation testing caught exactly that in the first version).
WATER = ["water_stain", "active_leak", "swelling_warping", "peeling_bubbling_paint", "mold_mildew", "rot", "efflorescence"]
FINISH_ONLY = ["water_stain", "peeling_bubbling_paint", "efflorescence"]
BOARD_OR_UNKNOWN = ["drywall", "ceiling_board", "paint", "unknown", "other"]
MASONRY = ["plaster", "concrete", "brick"]
COVERINGS = ["vinyl", "laminate", "tile", "wood", "carpet"]
SOFT = ["soft", "spongy", "rotten", "rotted", "sagging", "wet", "soaked", "saturated", "swollen", "warped"]
SIZE_TRUST = 0.6


# ------------------------------------------------------------------ vocabularies


@pytest.mark.parametrize("dtype", WATER)
def test_every_water_type_gets_one_moisture_reading(dtype):
    m = by_code([dmg(dtype)])["MST-MAP"]
    assert m["quantity"] == 1.0 and not m["needs_measurement"] and not m["assumption"]


@pytest.mark.parametrize("dtype", ["crack", "hole", "other"])
def test_dry_damage_gets_no_moisture_reading(dtype):
    assert "MST-MAP" not in by_code([dmg(dtype)])


@pytest.mark.parametrize("dtype", FINISH_ONLY)
def test_finish_damage_on_board_is_repainted_not_cut_out(dtype):
    c = by_code([dmg(dtype)])
    assert "PNT-SPR" in c and "DRY-RR" not in c


@pytest.mark.parametrize("dtype", ["swelling_warping", "rot", "hole", "mold_mildew", "active_leak"])
def test_substrate_damage_on_board_is_cut_out(dtype):
    assert "DRY-RR" in by_code([dmg(dtype)])


@pytest.mark.parametrize("material", BOARD_OR_UNKNOWN)
def test_board_or_unknown_material_is_cut_out(material):
    assert "DRY-RR" in by_code([dmg("swelling_warping", material=material)])


@pytest.mark.parametrize("material", MASONRY)
def test_masonry_is_repaired_in_place_never_drywall(material):
    c = by_code([dmg("swelling_warping", material=material)])
    assert "DRY-RR" not in c and "PNT-SPR" in c


@pytest.mark.parametrize("material", COVERINGS)
def test_each_floor_covering_is_replaced_without_a_doubt_flag(material):
    f = by_code([dmg("swelling_warping", material=material, surface="floor")])["FLC-RR"]
    assert f["quantity"] == 13.0 and not f["needs_measurement"]  # ceil(10 SF x 1.25 waste)


@pytest.mark.parametrize("word", SOFT)
def test_every_soft_word_in_a_subfloor_note_scopes_the_subfloor(word):
    assert "SBF-RR" in by_code(notes=[note(f"subfloor {word} by the cabinet")])


@pytest.mark.parametrize("word", SOFT)
def test_a_soft_condition_field_alone_scopes_the_subfloor(word):
    assert "SBF-RR" in by_code(notes=[note("subfloor by the cabinet", condition=word)])


# ------------------------------------------------------------------ quantities


def test_drywall_quantity_uses_waste_and_the_minimum_patch():
    assert by_code([dmg("swelling_warping", area=10.0)])["DRY-RR"]["quantity"] == 13.0  # ceil(10 x 1.25)
    assert by_code([dmg("swelling_warping", area=1.0)])["DRY-RR"]["quantity"] == 4.0  # minimum patch
    assert by_code([dmg("swelling_warping", area=0.5)])["DRY-RR"]["quantity"] == 4.0  # small, but real
    assert by_code([dmg("swelling_warping", area=10.0)])["PNT-SPR"]["quantity"] == 20.0  # paint blends x2


def test_drying_is_three_days_and_an_assumption():
    d = by_code([dmg("active_leak", surface="floor")])["WTR-DRY"]
    assert d["quantity"] == 3.0 and d["assumption"]


def test_baseboard_follows_a_wet_wall_and_its_width():
    b = by_code([dmg("swelling_warping", width=30.0)])["BSB-RR"]
    assert b["quantity"] == 3.0 and b["assumption"] and not b["needs_measurement"]  # ceil(30 in / 12)
    assert by_code([dmg("swelling_warping", width=6.0)])["BSB-RR"]["quantity"] == 1.0  # never 0, at least 1 LF
    assert "BSB-RR" not in by_code([dmg("water_stain", width=30.0)])  # only wet damage


def test_size_trust_boundary_is_inclusive():
    assert not by_code([dmg("swelling_warping", size_conf=SIZE_TRUST)])["DRY-RR"]["needs_measurement"]
    assert by_code([dmg("swelling_warping", size_conf=SIZE_TRUST - 0.01)])["DRY-RR"]["needs_measurement"]


def test_the_measure_note_appears_only_for_a_guessed_size():
    assert "[size estimated" not in by_code([dmg("swelling_warping")])["DRY-RR"]["basis"]
    assert "[size estimated" not in by_code([dmg("swelling_warping", area=None)])["DRY-RR"]["basis"]
    assert "[size estimated" in by_code([dmg("swelling_warping", size_conf=0.3)])["DRY-RR"]["basis"]


# ------------------------------------------------------------------ room and confidence


def test_line_items_keep_the_capture_room():
    for i in li.derive([dmg("swelling_warping", room="bathroom")], []):
        if i["code"] != "WTR-DRY":  # drying is per job, not per room
            assert i["room"] == "bathroom", i["code"]


def test_a_field_without_a_confidence_is_not_treated_as_certain():
    cap = dmg("swelling_warping")
    del cap["fields"]["damage_type"]["confidence"]
    assert all(i["confidence"] < 0.5 for i in li.derive([cap], []))


def test_a_doubtful_surface_lowers_the_line_confidence():
    assert by_code([dmg("swelling_warping", surface_conf=0.6)])["DRY-RR"]["confidence"] == 0.6
