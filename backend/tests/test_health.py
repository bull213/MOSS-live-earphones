"""Tests for the local backend's health contract."""

from fastapi.testclient import TestClient
from pytest import MonkeyPatch

from app.config import Settings
from app.main import create_app


def test_health_without_api_credentials(monkeypatch: MonkeyPatch) -> None:
    # Explicit settings win even when the developer has configured real credentials.
    monkeypatch.setenv("OPENAI_API_KEY", "environment-key-must-not-be-used")
    app = create_app(Settings(_env_file=None, openai_api_key=None, openai_model="test-model"))

    with TestClient(app) as client:
        assert app.state.vision_gateway is None
        response = client.get("/health")

    assert response.status_code == 200
    assert response.headers["content-type"] == "application/json"
    assert response.json() == {"status": "ok"}
