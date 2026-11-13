import asyncio
import json
import queue
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.config import Settings
from app.main import create_app
from app.notebooklm import GroundedChatResult, NotebookLMAuthExpiredError
from app.moss_live import (
    _client_command,
    _event,
    _query_notebook_and_inject,
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
    app = create_app(Settings(_env_file=None, moss_api_key="test-key", knowledge_data_dir=tmp_path, notebooklm_data_dir=tmp_path / "notebooklm"))

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


def test_prepare_grounded_voice_summary():
    from app.moss_live import _prepare_grounded_voice_summary
    raw = (
        "The **fun criterion** is an epistemological concept [1, 2]. "
        "Formulated by physicist David Deutsch [3-5]! 💡 "
        "Here are more details about why it matters.\n\n"
        "Second paragraph with information."
    )
    result = _prepare_grounded_voice_summary(raw, max_chars=100)
    assert "[1, 2]" not in result
    assert "[3-5]" not in result
    assert "**" not in result
    assert "💡" not in result
    assert len(result) <= 100


def test_find_matching_notebook_meta_queries_and_matching():
    from app.moss_live import _find_matching_notebook
    notebooks = [
        {"id": "1", "title": "Reason is fun"},
        {"id": "2", "title": "HOPFIELD NETWORKS IS ALL YOU NEED"},
        {"id": "3", "title": "Success Forces - sugarman"},
    ]

    # Meta-queries should NOT match any notebook
    assert _find_matching_notebook("Can you access my notebooks?", notebooks)[0] is None
    assert _find_matching_notebook("Do you have access to my notebooks?", notebooks)[0] is None
    assert _find_matching_notebook("What notebooks do I have?", notebooks)[0] is None
    assert _find_matching_notebook("List my notebooks", notebooks)[0] is None

    # Specific notebook queries should match
    nb, q = _find_matching_notebook("Check Reason is fun. What is the fun criterion?", notebooks)
    assert nb is not None and nb["title"] == "Reason is fun"

    nb, q = _find_matching_notebook("Can you check Sugarman?", notebooks)
    assert nb is not None and nb["title"] == "Success Forces - sugarman"

    # Contextual follow-up
    nb, q = _find_matching_notebook("What does it say about that?", notebooks, current_notebook=notebooks[0])
    assert nb is not None and nb["title"] == "Reason is fun"


@pytest.mark.anyio
async def test_query_notebook_and_inject_success():
    upstream = AsyncMock()
    android = AsyncMock()
    service = AsyncMock()
    service.ask_chat.return_value = GroundedChatResult(
        answer="The fun criterion requires problem solving to be fun."
    )
    notebook = {"id": "nb-1", "title": "Reason is fun"}
    completed_tasks = []

    await _query_notebook_and_inject(
        upstream=upstream,
        android=android,
        service=service,
        notebook=notebook,
        query="What is fun?",
        on_complete=completed_tasks.append,
        timeout=5.0,
    )

    assert len(completed_tasks) == 1
    # Check that session.instructions.append and response.create were sent
    calls = upstream.send.call_args_list
    assert len(calls) == 2
    cmd1 = json.loads(calls[0][0][0])
    assert cmd1["action"] == "session.instructions.append"
    assert "According to Reason is fun" in cmd1["content"]
    cmd2 = json.loads(calls[1][0][0])
    assert cmd2["action"] == "response.create"
    # Check that video.stream_resume was dispatched to Android
    android.send_text.assert_called_once_with('{"action":"video.stream_resume"}')


@pytest.mark.anyio
async def test_query_notebook_and_inject_timeout():
    upstream = AsyncMock()
    android = AsyncMock()
    service = AsyncMock()

    async def _slow_chat(*args, **kwargs):
        await asyncio.sleep(0.5)

    service.ask_chat.side_effect = _slow_chat
    notebook = {"id": "nb-1", "title": "Reason is fun"}
    completed_tasks = []

    await _query_notebook_and_inject(
        upstream=upstream,
        android=android,
        service=service,
        notebook=notebook,
        query="What is fun?",
        on_complete=completed_tasks.append,
        timeout=0.05,
    )

    assert len(completed_tasks) == 1
    calls = upstream.send.call_args_list
    assert len(calls) == 2
    cmd1 = json.loads(calls[0][0][0])
    assert cmd1["action"] == "session.instructions.append"
    assert "couldn't retrieve information from your Reason is fun notebook in time" in cmd1["content"]
    cmd2 = json.loads(calls[1][0][0])
    assert cmd2["action"] == "response.create"
    android.send_text.assert_called_once_with('{"action":"video.stream_resume"}')


@pytest.mark.anyio
async def test_query_notebook_and_inject_auth_expired():
    upstream = AsyncMock()
    android = AsyncMock()
    service = AsyncMock()
    service.ask_chat.side_effect = NotebookLMAuthExpiredError("Expired")
    notebook = {"id": "nb-1", "title": "Reason is fun"}
    completed_tasks = []

    await _query_notebook_and_inject(
        upstream=upstream,
        android=android,
        service=service,
        notebook=notebook,
        query="What is fun?",
        on_complete=completed_tasks.append,
        timeout=5.0,
    )

    assert len(completed_tasks) == 1
    calls = upstream.send.call_args_list
    assert len(calls) == 2
    cmd1 = json.loads(calls[0][0][0])
    assert cmd1["action"] == "session.instructions.append"
    assert "Google session credentials expired" in cmd1["content"]
    cmd2 = json.loads(calls[1][0][0])
    assert cmd2["action"] == "response.create"
    android.send_text.assert_called_once_with('{"action":"video.stream_resume"}')


def test_camera_frame_gating_and_preemption(monkeypatch, tmp_path):
    class GatingFakeUpstream:
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

    upstream = GatingFakeUpstream()

    async def fake_connect(*args, **kwargs):
        return upstream

    monkeypatch.setattr("app.moss_live.connect", fake_connect)

    app = create_app(Settings(_env_file=None, moss_api_key="test-key", knowledge_data_dir=tmp_path, notebooklm_data_dir=tmp_path / "notebooklm"))
    mock_service = AsyncMock()
    mock_service.has_auth = lambda: True
    mock_service.list_notebooks.return_value = [{"id": "nb-1", "title": "Reason is fun"}]
    # Simulate a slow query in flight
    async def _long_query(*args, **kwargs):
        await asyncio.sleep(5.0)
    mock_service.ask_chat.side_effect = _long_query

    with TestClient(app) as client:
        client.app.state.notebooklm_service = mock_service
        with client.websocket_connect("/v1/realtime/moss-live") as socket:
            assert socket.receive_json()["action"] == "session_attached"
            assert socket.receive_json()["action"] == "session.updated"
            # Drain initial commands
            assert json.loads(upstream.sent.get(timeout=2))["action"] == "attach_session"
            assert json.loads(upstream.sent.get(timeout=2))["action"] == "session.update"

            # 1. Normal state: camera frame passes through
            camera_frame = b"\x01\x10\x20"
            socket.send_bytes(camera_frame)
            assert upstream.sent.get(timeout=2) == camera_frame

            # 2. Trigger notebook query via input.transcript.done
            upstream.send_to_android('{"action":"input.transcript.done","data":{"text":"Check Reason is fun. What is it?"}}')
            # Android receives video.stream_pause, then the transcript event
            assert socket.receive_json()["action"] == "video.stream_pause"
            assert socket.receive_json()["action"] == "input.transcript.done"

            # Upstream receives response.cancel, then holding phrase session.instructions.append, then response.create
            cmd_cancel = json.loads(upstream.sent.get(timeout=2))
            assert cmd_cancel["action"] == "response.cancel"
            cmd_hold = json.loads(upstream.sent.get(timeout=2))
            assert cmd_hold["action"] == "session.instructions.append"
            assert "Checking your 'Reason is fun' notebook now" in cmd_hold["content"]
            cmd_create = json.loads(upstream.sent.get(timeout=2))
            assert cmd_create["action"] == "response.create"

            # 3. Audio frame passes through during retrieval
            audio_frame = b"\x00\xaa\xbb"
            socket.send_bytes(audio_frame)
            assert upstream.sent.get(timeout=2) == audio_frame

            # 4. Camera frame is dropped/gated while retrieval is in flight
            dropped_camera_frame = b"\x01\xcc\xdd"
            socket.send_bytes(dropped_camera_frame)
            # Audio frame follows to verify queue ordering and that camera frame was dropped
            audio_frame2 = b"\x00\xee\xff"
            socket.send_bytes(audio_frame2)
            # The next message received by upstream MUST be audio_frame2, NOT dropped_camera_frame!
            assert upstream.sent.get(timeout=2) == audio_frame2

            # 5. User says "cancel that" -> preemption cancellation
            upstream.send_to_android('{"action":"input.transcript.done","data":{"text":"cancel that"}}')
            assert socket.receive_json()["action"] == "video.stream_resume"

            # 6. Camera frames now resume forwarding
            resumed_camera_frame = b"\x01\x77\x88"
            socket.send_bytes(resumed_camera_frame)
            assert upstream.sent.get(timeout=2) == resumed_camera_frame


def test_client_quiescent_disconnect_closes_upstream_code_1000(monkeypatch, tmp_path):
    class CloseCaptureUpstream:
        def __init__(self):
            self.sent = queue.Queue()
            self.closed_code = None
            self.initial = iter((
                '{"action":"session_attached","data":{"session_id":"s1"}}',
                '{"action":"session.updated","data":{"error":""}}',
            ))

        async def send(self, message):
            self.sent.put(message)

        async def recv(self):
            try:
                return next(self.initial)
            except StopIteration:
                await asyncio.sleep(10.0)
                return ""

        async def close(self, code=1000, reason=""):
            self.closed_code = code

    upstream = CloseCaptureUpstream()

    async def fake_connect(*args, **kwargs):
        return upstream

    monkeypatch.setattr("app.moss_live.connect", fake_connect)

    app = create_app(Settings(_env_file=None, moss_api_key="test-key", knowledge_data_dir=tmp_path))
    with TestClient(app) as client:
        with client.websocket_connect("/v1/realtime/moss-live") as socket:
            assert socket.receive_json()["action"] == "session_attached"
            assert socket.receive_json()["action"] == "session.updated"
            socket.close(code=1000)

    assert upstream.closed_code == 1000


@pytest.mark.anyio
async def test_mic_state_disabled_starts_and_stops_ping_loop():
    from app.moss_live import MossLiveRelaySession

    upstream = AsyncMock()
    upstream.send = AsyncMock()
    upstream.ping = AsyncMock()
    android = AsyncMock()

    relay = MossLiveRelaySession(
        session_id="test-session",
        android=android,
        upstream=upstream,
        service=None,
        notebook_list=[],
        started_at=0.0,
    )

    # Initially ping_task is None
    assert relay.ping_task is None

    # Android sends mic_state: false -> starts ping loop
    handled = await relay._handle_android_text('{"action":"mic_state","enabled":false}')
    assert handled
    assert relay.ping_task is not None
    assert not relay.ping_task.done()

    # Android sends mic_state: true -> stops ping loop
    handled = await relay._handle_android_text('{"action":"mic_state","enabled":true}')
    assert handled
    assert relay.ping_task is None

    # Mic_state false again -> starts ping loop, and close_in_flight cancels it
    await relay._handle_android_text('{"action":"mic_state","enabled":false}')
    assert relay.ping_task is not None
    relay.close_in_flight()
    assert relay.ping_task is None



