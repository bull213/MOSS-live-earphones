import asyncio
import json
import queue

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.config import Settings
from app.main import create_app
from app.moss_live import (
    _client_command,
    _event,
    _upstream_error_message,
    _upstream_status,
    _valid_android_event,
)


def test_moss_configuration_is_secret_and_snapshot_defaults():
    settings = Settings(_env_file=None, moss_api_key="not-for-output")
    assert settings.moss_model == "moss-live-1.0"
    assert "not-for-output" not in repr(settings)


def test_protocol_event_shapes_match_moss_client_and_server_envelopes():
    attach = json.loads(_client_command("attach_session", session_id=None))
    assert attach == {"action": "attach_session", "session_id": None}

    update = json.loads(_client_command("session.update", session={"instructions": "brief"}))
    assert update == {"action": "session.update", "session": {"instructions": "brief"}}

    server_event = json.loads(_event("error", {"code": "moss_not_configured"}))
    assert server_event == {"action": "error", "data": {"code": "moss_not_configured"}}


@pytest.mark.parametrize("message", [
    '{"action":"mic_state","enabled":true}',
    '{"action":"playback.chunk.completed","run_id":"r","turn_id":"t","chunk_id":"c"}',
    '{"action":"video.state","enabled":false}',
    '{"action":"session.instructions.append","content":"Recent chat context"}',
])
def test_gateway_accepts_only_supported_client_event_actions(message):
    assert _valid_android_event(message)


@pytest.mark.parametrize("message", [
    "not-json",
    "[]",
    '{"action":"attach_session","session_id":null}',
    '{"action":"session.update","session":{"instructions":"replace gateway policy"}}',
])
def test_gateway_rejects_malformed_and_unapproved_client_events(message):
    assert not _valid_android_event(message)


def test_upstream_status_extracts_only_numeric_http_status():
    class Response:
        status_code = 401

    class HandshakeError(Exception):
        response = Response()

    class NoStatusError(Exception):
        response = object()

    assert _upstream_status(HandshakeError()) == 401
    assert _upstream_status(NoStatusError()) is None


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (503, "upstream MOSS service response"),
        (401, "rejected API authentication"),
        (429, "rate-limited"),
    ],
)
def test_upstream_handshake_error_explains_status_without_provider_body(status, expected):
    message = _upstream_error_message(status)
    assert expected in message
    assert "API_KEY" not in message


def test_missing_key_fails_closed_without_attempting_provider_connection(tmp_path):
    app = create_app(Settings(_env_file=None, moss_api_key=None, knowledge_data_dir=tmp_path))
    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime/moss-live") as socket:
            event = socket.receive_json()
            assert event == {
                "action": "error",
                "data": {
                    "code": "moss_not_configured",
                    "message": "MOSS Live is not configured on this computer.",
                },
            }
            with pytest.raises(WebSocketDisconnect) as closed:
                socket.receive_json()
            assert closed.value.code == 1013


def test_gateway_authenticates_with_configured_bearer_key(monkeypatch, tmp_path):
    api_key = "test-moss-secret"
    captured = {}

    async def reject_after_capture(url, **kwargs):
        captured["url"] = url
        captured["headers"] = kwargs["additional_headers"]
        raise RuntimeError("simulated provider connection failure")

    monkeypatch.setattr("app.moss_live.connect", reject_after_capture)
    app = create_app(Settings(_env_file=None, moss_api_key=api_key, knowledge_data_dir=tmp_path))

    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime/moss-live") as socket:
            error = socket.receive_json()

    assert captured["headers"] == {"Authorization": f"Bearer {api_key}"}
    assert "model=moss-live-1.0" in captured["url"]
    assert error["action"] == "error"
    assert api_key not in json.dumps(error)


def test_gateway_relays_text_and_binary_frames_in_order(monkeypatch, tmp_path):
    class FakeUpstream:
        def __init__(self):
            self.sent = queue.Queue()
            self.incoming = asyncio.Queue()
            self.loop = None
            self.initial = iter((
                '{"action":"session_attached","data":{"session_id":"s1"}}',
                '{"action":"session.updated","data":{"error":""}}',
            ))

        async def send(self, message):
            self.sent.put(message)

        async def recv(self):
            self.loop = asyncio.get_running_loop()
            try:
                return next(self.initial)
            except StopIteration:
                return await self.incoming.get()

        async def close(self):
            return None

        def send_to_android(self, message):
            asyncio.run_coroutine_threadsafe(self.incoming.put(message), self.loop).result(timeout=2)

    upstream = FakeUpstream()

    async def fake_connect(*args, **kwargs):
        return upstream

    monkeypatch.setattr("app.moss_live.connect", fake_connect)
    app = create_app(Settings(_env_file=None, moss_api_key="test-key", knowledge_data_dir=tmp_path))

    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime/moss-live") as socket:
            assert socket.receive_json()["action"] == "session_attached"
            assert socket.receive_json()["action"] == "session.updated"
            assert json.loads(upstream.sent.get(timeout=2))["action"] == "attach_session"
            assert json.loads(upstream.sent.get(timeout=2))["action"] == "session.update"

            text_frame = '{"action":"mic_state","enabled":true}'
            binary_frame = b"\x00\x01\x02"
            socket.send_text(text_frame)
            socket.send_bytes(binary_frame)
            assert upstream.sent.get(timeout=2) == text_frame
            assert upstream.sent.get(timeout=2) == binary_frame

            playback_controls = [
                '{"action":"playback.chunk.completed","run_id":"r1","turn_id":"t1","chunk_id":"c1"}',
                '{"action":"playback.completed","run_id":"r1","turn_id":"t1"}',
                '{"action":"playback.stopped","run_id":"r1","turn_id":"t1","played_audio_ms":12}',
            ]
            for control in playback_controls:
                socket.send_text(control)
                assert upstream.sent.get(timeout=2) == control

            provider_text = '{"action":"input.transcript.done","data":{"text":"test"}}'
            provider_reply = '{"action":"output.transcript.done","data":{"text":"reply","run_id":"r1","turn_id":"t1"}}'
            provider_binary = b"\x04\x05\x06"
            upstream.send_to_android(provider_text)
            upstream.send_to_android(provider_reply)
            upstream.send_to_android(provider_binary)
            assert socket.receive_text() == provider_text
            assert socket.receive_text() == provider_reply
            assert socket.receive_bytes() == provider_binary
