import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import create_app, inspect_segments
import app.main as inspection_module

OP = "00000000-0000-4000-8000-000000000001"
KEY = "synthetic-inspection-credential-for-tests-only"
ROOT = Path(__file__).resolve().parents[3]


def segment(text, stage="prompt"):
    return {"id": "s0", "stage": stage, "path": "/messages/0/content", "text": text}


@pytest.fixture
def client():
    return TestClient(create_app(KEY))


def test_auth_before_parsing(client):
    result = client.post("/internal/v1/inspect", content="not json")
    assert result.status_code == 401
    assert client.get("/health/live").json() == {"status": "up"}


@pytest.mark.parametrize("body", [b'{"operation_id":1,"operation_id":2}', b'{"secret":"do-not-echo"}', b'{"x":NaN}', b'\xff', b'[]'])
def test_invalid_bodies_do_not_echo(client, body):
    result = client.post("/internal/v1/inspect", content=body,
                         headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    assert result.status_code == 400
    assert "do-not-echo" not in result.text


def test_unicode_byte_offsets_and_no_matched_text(client):
    text = "👋 café: alice@example.test"
    result = client.post("/internal/v1/inspect", json={"operation_id": OP, "segments": [segment(text)]},
                         headers={"Authorization": "Bearer " + KEY})
    assert result.status_code == 200
    finding = result.json()["findings"][0]
    assert text.encode()[finding["start_byte"]:finding["end_byte"]].decode() == "alice@example.test"
    assert "alice@example.test" not in result.text
    assert "score" not in finding


def test_overflow_is_explicit_failure():
    report = inspect_segments(OP, [segment(" ".join(["alice@example.test"] * 101))])
    assert report["status"] == "failed" and report["truncated"] is True
    assert report["findings"] == []


def test_phase0_content_fixtures():
    cases = json.loads((ROOT / "evaluation/fixtures/phase0-cases.json").read_text())["cases"]
    for case in cases:
        if "text" not in case:
            continue
        findings = inspect_segments(OP, [segment(case["text"], case["stage"])])["findings"]
        if "expected_category" in case:
            assert case["expected_category"] in {f["category"] for f in findings}, case["id"]
        else:
            assert findings == [], case["id"]


def test_request_bound_and_unknown_role(client):
    headers = {"Authorization": "Bearer " + KEY, "Content-Type": "application/json"}
    assert client.post("/internal/v1/inspect", content=b" " * 262145, headers=headers).status_code == 413
    payload = {"operation_id": OP, "segments": [segment("hello")], "role": "operator"}
    assert client.post("/internal/v1/inspect", json=payload, headers=headers).status_code == 400


def test_container_directory_does_not_evaluate_source_fallback(monkeypatch):
    monkeypatch.setenv("SENTINEL_SCHEMAS_DIR", str(ROOT / "contracts/schemas"))
    monkeypatch.setattr(inspection_module, "__file__", "/app/app/main.py")
    assert TestClient(create_app(KEY)).get("/health/live").status_code == 200


def test_unrelated_schema_without_optional_title_does_not_break_startup(tmp_path,monkeypatch):
    import shutil
    for path in (ROOT/"contracts/schemas").glob("*.json"):
        shutil.copy(path,tmp_path/path.name)
    (tmp_path/"unrelated.schema.json").write_text(json.dumps({"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://schemas.sentinellm.local/v1/unrelated.schema.json","type":"string"}))
    monkeypatch.setenv("SENTINEL_SCHEMAS_DIR",str(tmp_path))
    assert TestClient(create_app(KEY)).get("/health/live").status_code==200
