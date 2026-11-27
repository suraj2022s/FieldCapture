"""A reviewer's clone has no API key: extraction must switch itself off and the offline path must fill fields,
instead of calling Claude per photo and logging an auth error each time (found 2026-11-27)."""

import json

from fastapi.testclient import TestClient

from fieldcapture_backend.app import create_app
from fieldcapture_backend.config import settings
from tests.test_backend import JOB, capture_meta, post


def test_extraction_defaults_to_off_without_a_key(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.delenv("FIELDCAPTURE_EXTRACT", raising=False)
    assert settings().extract is False
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    assert settings().extract is True
    monkeypatch.setenv("FIELDCAPTURE_EXTRACT", "0")
    assert settings().extract is False  # an explicit off still wins


def test_no_key_clone_gives_ocr_fields_not_errors(monkeypatch, tmp_path):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.delenv("FIELDCAPTURE_EXTRACT", raising=False)
    client = TestClient(create_app(data_dir=tmp_path))  # no extract override: the default decides
    assert client.get("/healthz").json()["extract"] is False
    post(client, "capture", "np1", capture_meta("np1", "nameplate", ocr=["MEKTRON", "MODEL- MT450", "SERIAL NO 450AW55230192"]))
    r = client.get(f"/v1/jobs/{JOB}/record").json()
    cap = r["captures"][0]
    assert cap["extraction_status"] == "disabled"
    assert cap["fields"]["model"]["value"] == "MT450" and cap["fields"]["model"]["source"] == "device_ocr"
    assert "extraction_failed" not in json.dumps(r["review"])
