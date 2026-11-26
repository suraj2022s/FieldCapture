"""Access code: the phone sends a Bearer token, a browser answers the Basic prompt; /healthz stays open."""

import base64

from fastapi.testclient import TestClient

from fieldcapture_backend.app import authorized, create_app

CODE = "7KQ2-M9XA"


def basic(password: str, user: str = "office") -> str:
    return "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()


def test_no_code_configured_means_open(tmp_path):
    c = TestClient(create_app(data_dir=tmp_path, extract=False, token=""))
    assert c.get("/v1/jobs").status_code == 200


def test_code_required_everywhere_but_health(tmp_path):
    c = TestClient(create_app(data_dir=tmp_path, extract=False, token=CODE))
    assert c.get("/healthz").status_code == 200  # the phone's reachability probe
    for path in ("/", "/v1/jobs", "/files/x.jpg", "/jobs/abc", "/v1/jobs/abc/record"):
        r = c.get(path)
        assert r.status_code == 401, path
        assert r.headers["www-authenticate"].startswith("Basic"), path  # the browser shows a password prompt
    assert c.post("/v1/jobs/abc/items", data={"item": "{}"}).status_code == 401


def test_phone_bearer_and_browser_basic_get_in(tmp_path):
    c = TestClient(create_app(data_dir=tmp_path, extract=False, token=CODE))
    assert c.get("/v1/jobs", headers={"Authorization": f"Bearer {CODE}"}).status_code == 200
    assert c.get("/", headers={"Authorization": basic(CODE)}).status_code == 200


def test_wrong_or_malformed_credentials_are_refused():
    assert not authorized(None, CODE)
    assert not authorized(f"Bearer {CODE}x", CODE)
    assert not authorized(basic("nope"), CODE)
    assert not authorized("Basic !!!not-base64", CODE)
    assert not authorized(f"Token {CODE}", CODE)
    assert authorized(f"bearer {CODE}", CODE)  # scheme is case-insensitive
