"""Review flags should be things a reviewer can act on (first Wi-Fi walk, 2026-11-27: 16 flags on 4 captures)."""

from fieldcapture_backend.record import _flags


def f(value, conf):
    return {"value": value, "confidence": conf}


GOOD = {"make": f("MEKTRON", 0.97), "model": f("MT450", 0.97), "serial": f("450AW55230192", 0.9)}


def test_unprinted_date_is_not_a_flag():
    assert _flags("nameplate", {**GOOD, "manufacture_date": f(None, 0.0)}, "done") == []


def test_a_date_read_with_low_confidence_still_is():
    assert _flags("nameplate", {**GOOD, "manufacture_date": f("2016-01", 0.3)}, "done") == ["low_confidence:manufacture_date"]


def test_unread_serial_is_still_flagged():
    flags = _flags("nameplate", {**GOOD, "serial": f(None, 0.0), "manufacture_date": f(None, 0.0)}, "done")
    assert flags == ["low_confidence:serial"]


def test_blank_damage_room_is_left_to_room_missing():
    fields = {"room": f(None, 0.0), "surface": f("wall", 0.9), "damage_type": f("crack", 0.9), "size": f({"area_sqft": 2}, 0.9)}
    assert _flags("damage", fields, "done") == []
    fields["room"] = f("kitchen", 0.4)  # a room that was given but doubted is still worth a look
    assert _flags("damage", fields, "done") == ["low_confidence:room"]
