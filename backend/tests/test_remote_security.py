"""Tests for Tailscale CGNAT subnet validation and Bearer token security."""

from unittest.mock import MagicMock, patch
from fastapi.testclient import TestClient
import pytest

from app.config import Settings
from app.main import create_app
from app.security import is_loopback_or_private, is_tailscale_ip


def test_tailscale_ip_detection() -> None:
    # Loopback
    assert is_loopback_or_private("127.0.0.1")
    assert is_loopback_or_private("::1")
    assert is_loopback_or_private("localhost")
    assert is_loopback_or_private("testclient")

    # Private LAN
    assert is_loopback_or_private("192.168.1.50")
    assert is_loopback_or_private("10.0.0.1")
    assert is_loopback_or_private("172.16.0.1")

    # Tailscale CGNAT (100.64.0.0/10)
    assert is_loopback_or_private("100.64.0.1")
    assert is_loopback_or_private("100.100.50.25")
    assert is_loopback_or_private("100.127.255.254")

    # Tailscale IPv6 ULA
    assert is_loopback_or_private("fd7a:115c:a1e0::1")

    # Public internet IPs must be rejected
    assert not is_loopback_or_private("8.8.8.8")
    assert not is_loopback_or_private("1.1.1.1")
    assert not is_loopback_or_private("142.250.190.46")


def test_bearer_token_unconfigured_allows_access() -> None:
    settings = Settings(_env_file=None, api_bearer_token=None)
    app = create_app(settings)
    with TestClient(app) as client:
        # /health is always 200
        assert client.get("/health").status_code == 200
        # /v1/models is 200 or 404 (not 401 unauthorized)
        assert client.get("/v1/models").status_code != 401


def test_bearer_token_enforcement_on_http_routes() -> None:
    settings = Settings(_env_file=None, api_bearer_token="secret-token-123")
    app = create_app(settings)
    with TestClient(app) as client:
        # /health is exempt from authentication
        health_resp = client.get("/health")
        assert health_resp.status_code == 200
        assert health_resp.json() == {"status": "ok"}

        # Protected route without token returns 401
        resp_no_token = client.get("/v1/notebooklm/status")
        assert resp_no_token.status_code == 401
        assert resp_no_token.json()["error"]["code"] == "unauthorized"

        # Protected route with wrong token returns 401
        resp_wrong = client.get("/v1/notebooklm/status", headers={"Authorization": "Bearer wrong-token"})
        assert resp_wrong.status_code == 401

        # Protected route with valid token succeeds (or proceeds past auth check)
        resp_valid = client.get("/v1/notebooklm/status", headers={"Authorization": "Bearer secret-token-123"})
        assert resp_valid.status_code == 200


def test_websocket_bearer_token_enforcement() -> None:
    settings = Settings(_env_file=None, api_bearer_token="ws-secret-456", moss_api_key=None)
    app = create_app(settings)
    with TestClient(app) as client:
        # WebSocket without token gets rejected with 4401 or unauthorized error
        with client.websocket_connect("/v1/realtime/moss-live") as ws:
            msg = ws.receive_json()
            assert msg["action"] == "error"
            assert msg["data"]["code"] == "unauthorized"

        # WebSocket with valid token query parameter connects
        with client.websocket_connect("/v1/realtime/moss-live?token=ws-secret-456") as ws:
            # Passes auth check; since moss_api_key is None, returns moss_not_configured
            msg = ws.receive_json()
            assert msg["action"] == "error"
            assert msg["data"]["code"] == "moss_not_configured"
