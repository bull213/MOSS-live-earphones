"""Loopback-facing WebSocket gateway for real MOSS Live sessions."""

import asyncio
from collections import Counter
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import time
from urllib.parse import quote
from uuid import uuid4

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

from app.config import Settings

router = APIRouter()
logger = logging.getLogger(__name__)
_diagnostics_path = Path(__file__).resolve().parents[1] / ".data" / "moss-live-actions.log"
_diagnostics_path.parent.mkdir(parents=True, exist_ok=True)
_diagnostics_handler = RotatingFileHandler(_diagnostics_path, maxBytes=512 * 1024, backupCount=1, encoding="utf-8")
_diagnostics_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
logger.addHandler(_diagnostics_handler)
logger.setLevel(logging.INFO)
logger.propagate = False

MOSS_URL = "wss://api.mosi.cn/v1/realtime"
SESSION_INSTRUCTIONS = (
    "You are Airflow, a patient visual assistant for older adults. "
    "Use short, clear language and give one practical step at a time. "
    "Only describe visual details you can verify. Ask a brief question when "
    "important information is unclear. Stop and recommend appropriate help "
    "for unsafe situations. Do not claim an action was completed unless the "
    "user confirms it."
)
MAX_ANDROID_TEXT_BYTES = 32 * 1024
MAX_ANDROID_BINARY_BYTES = 512 * 1024
MAX_PROVIDER_TEXT_BYTES = 64 * 1024
MAX_PROVIDER_BINARY_BYTES = 512 * 1024
PROTOCOL_TIMEOUT_SECONDS = 20
ALLOWED_CLIENT_ACTIONS = {
    "mic_state",
    "media.mode",
    "video.state",
    "video.stream_focus",
    "session.instructions.append",
    "playback.chunk.completed",
    "playback.completed",
    "playback.stopped",
    "ping",
}
SAFE_PROVIDER_ACTIONS = {
    "session_attached", "session.updated", "session.closing", "input.speech.started",
    "input.speech.stopped", "input.transcript.updated", "input.transcript.done",
    "input.transcript.revised", "output.transcript.updated", "output.transcript.done",
    "response.audio.started", "response.audio.context", "response.audio.done",
    "response.audio.stop", "response.ended", "response.interrupted", "error",
    "session.instructions.appended",
}


def _event(action: str, data: dict | None = None) -> str:
    return json.dumps({"action": action, "data": data or {}}, separators=(",", ":"))


def _client_command(action: str, **fields: object) -> str:
    return json.dumps({"action": action, **fields}, separators=(",", ":"))


def _valid_android_event(message: str) -> bool:
    if len(message.encode("utf-8")) > MAX_ANDROID_TEXT_BYTES:
        return False
    try:
        event = json.loads(message)
    except (json.JSONDecodeError, UnicodeError):
        return False
    return isinstance(event, dict) and event.get("action") in ALLOWED_CLIENT_ACTIONS


async def _provider_event(socket) -> dict:
    message = await asyncio.wait_for(socket.recv(), timeout=PROTOCOL_TIMEOUT_SECONDS)
    if not isinstance(message, str) or len(message.encode("utf-8")) > MAX_PROVIDER_TEXT_BYTES:
        raise ValueError("Unexpected provider initialization message")
    try:
        event = json.loads(message)
    except (json.JSONDecodeError, UnicodeError) as exc:
        raise ValueError("Malformed provider initialization event") from exc
    if not isinstance(event, dict) or not isinstance(event.get("action"), str):
        raise ValueError("Malformed provider initialization event")
    return event


async def _notify_error(android: WebSocket, code: str, message: str) -> None:
    if android.client_state == WebSocketState.CONNECTED and android.application_state == WebSocketState.CONNECTED:
        await android.send_text(_event("error", {"code": code, "message": message}))


def _upstream_status(error: Exception) -> int | None:
    response = getattr(error, "response", None)
    status = getattr(response, "status_code", None)
    return status if isinstance(status, int) else None


def _upstream_error_message(status: int) -> str:
    if status == 503:
        return (
            "The MOSS service returned HTTP 503 (Service Unavailable). "
            "The phone reached the backend; this is an upstream MOSS service response. "
            "Try again later or check MOSS service availability."
        )
    if status in {401, 403}:
        return (
            f"MOSS rejected API authentication (HTTP {status}). "
            "Verify the key is active and enabled for realtime access."
        )
    if status == 429:
        return (
            "MOSS rate-limited the connection (HTTP 429). "
            "Check the account's quota and rate limits before retrying."
        )
    return f"MOSS rejected the backend connection (HTTP {status}). Check the model and provider account configuration."


@router.websocket("/v1/realtime/moss-live")
async def moss_live_gateway(android: WebSocket) -> None:
    settings: Settings = android.app.state.settings
    await android.accept()
    if settings.moss_api_key is None:
        await _notify_error(android, "moss_not_configured", "MOSS Live is not configured on this computer.")
        await android.close(code=1013, reason="MOSS Live is not configured")
        return

    upstream = None
    session_id = uuid4().hex[:12]
    started_at = time.monotonic()
    shutdown_initiator = "unknown"
    close_code = None
    provider_events: Counter[str] = Counter()
    android_controls: Counter[str] = Counter()
    android_audio_frames = 0
    android_audio_bytes = 0
    android_camera_frames = 0
    android_camera_bytes = 0
    provider_audio_chunks = 0
    provider_audio_bytes = 0
    try:
        url = f"{MOSS_URL}?model={quote(settings.moss_model, safe='')}"
        upstream = await connect(
            url,
            additional_headers={"Authorization": f"Bearer {settings.moss_api_key.get_secret_value()}"},
            open_timeout=10,
            close_timeout=2,
            ping_interval=20,
            ping_timeout=20,
            max_size=MAX_PROVIDER_BINARY_BYTES,
            max_queue=8,
            write_limit=64 * 1024,
        )

        await upstream.send(_client_command("attach_session", session_id=None))
        attached = await _provider_event(upstream)
        if attached["action"] != "session_attached":
            await _notify_error(android, "moss_session_failed", "MOSS could not start a conversation.")
            await android.close(code=1011, reason="MOSS session setup failed")
            return
        provider_events["session_attached"] += 1
        await android.send_text(json.dumps(attached, separators=(",", ":")))

        await upstream.send(_client_command(
            "session.update", session={"instructions": SESSION_INSTRUCTIONS},
        ))
        configured = await _provider_event(upstream)
        if configured["action"] != "session.updated" or configured.get("data", {}).get("error"):
            await _notify_error(android, "moss_configuration_failed", "MOSS could not prepare the conversation.")
            await android.close(code=1011, reason="MOSS session configuration failed")
            return
        provider_events["session.updated"] += 1
        await android.send_text(json.dumps(configured, separators=(",", ":")))

        async def android_to_provider() -> None:
            nonlocal android_audio_frames, android_audio_bytes, android_camera_frames, android_camera_bytes, shutdown_initiator, close_code
            while True:
                packet = await android.receive()
                kind = packet["type"]
                if kind == "websocket.disconnect":
                    shutdown_initiator = "android_disconnect"
                    close_code = packet.get("code")
                    return
                if kind == "websocket.receive":
                    text = packet.get("text")
                    binary = packet.get("bytes")
                    if text is not None:
                        if not _valid_android_event(text):
                            await _notify_error(android, "invalid_event", "The realtime session received an invalid control message.")
                            return
                        try:
                            client_action = json.loads(text).get("action")
                        except (json.JSONDecodeError, AttributeError):
                            client_action = "other"
                        await upstream.send(text)
                        android_controls[client_action if client_action in ALLOWED_CLIENT_ACTIONS else "other"] += 1
                    elif binary is not None:
                        if len(binary) > MAX_ANDROID_BINARY_BYTES:
                            await _notify_error(android, "media_too_large", "A realtime media frame exceeded the allowed size.")
                            return
                        await upstream.send(binary)
                        if len(binary) > 0 and binary[0] == 0x01:
                            android_camera_frames += 1
                            android_camera_bytes += len(binary)
                        else:
                            android_audio_frames += 1
                            android_audio_bytes += len(binary)

        async def provider_to_android() -> None:
            nonlocal provider_audio_chunks, provider_audio_bytes, shutdown_initiator, close_code
            while True:
                message = await upstream.recv()
                if isinstance(message, bytes):
                    if len(message) > MAX_PROVIDER_BINARY_BYTES:
                        await _notify_error(android, "provider_message_too_large", "MOSS sent an unsupported media frame.")
                        return
                    await android.send_bytes(message)
                    provider_audio_chunks += 1
                    provider_audio_bytes += len(message)
                else:
                    if len(message.encode("utf-8")) > MAX_PROVIDER_TEXT_BYTES:
                        await _notify_error(android, "provider_message_too_large", "MOSS sent an unsupported event.")
                        return
                    try:
                        event = json.loads(message)
                    except (json.JSONDecodeError, UnicodeError):
                        await _notify_error(android, "provider_protocol_error", "MOSS returned an unreadable event.")
                        return
                    if not isinstance(event, dict) or not isinstance(event.get("action"), str):
                        await _notify_error(android, "provider_protocol_error", "MOSS returned an unreadable event.")
                        return
                    action = event["action"]
                    await android.send_text(message)
                    provider_events[action if action in SAFE_PROVIDER_ACTIONS else "other"] += 1

        tasks = {
            asyncio.create_task(android_to_provider(), name="moss-android-to-provider"),
            asyncio.create_task(provider_to_android(), name="moss-provider-to-android"),
        }
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            if task.cancelled():
                continue
            error = task.exception()
            if task.get_name() == "moss-provider-to-android":
                shutdown_initiator = "provider_socket_closed"
            elif task.get_name() == "moss-android-to-provider" and error is None:
                shutdown_initiator = "android_disconnect"
            if error is not None and not isinstance(error, (WebSocketDisconnect, ConnectionClosed, asyncio.CancelledError)):
                await _notify_error(android, "moss_connection_lost", "The MOSS connection ended. Stop and start Live again.")
            if isinstance(error, ConnectionClosed):
                close_code = error.code
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
    except WebSocketDisconnect:
        shutdown_initiator = "android_disconnect"
        pass
    except ConnectionClosed as exc:
        shutdown_initiator = "provider_socket_closed"
        close_code = exc.code
        await _notify_error(android, "moss_connection_lost", "The MOSS connection ended. Stop and start Live again.")
    except asyncio.TimeoutError:
        shutdown_initiator = "provider_timeout"
        await _notify_error(android, "moss_timeout", "MOSS did not respond in time. Stop and try again.")
    except Exception as exc:
        shutdown_initiator = "relay_exception"
        # Exception text and provider bodies may contain sensitive handshake details.
        status = _upstream_status(exc)
        logger.warning(
            "MOSS Live upstream failed (exception=%s, http_status=%s)",
            type(exc).__name__,
            status,
        )
        if status is not None:
            await _notify_error(
                android,
                "moss_upstream_rejected",
                _upstream_error_message(status),
            )
        else:
            await _notify_error(
                android,
                "moss_unavailable",
                "The backend could not connect to MOSS. Check the backend log for a sanitized failure reason.",
            )
    finally:
        logger.info(
            "MOSS Live session=%s elapsed_ms=%d shutdown=%s close_code=%s "
            "android_audio_frames=%d android_audio_bytes=%d android_camera_frames=%d android_camera_bytes=%d "
            "provider_audio_chunks=%d provider_audio_bytes=%d "
            "provider_events=%s android_controls_forwarded=%s",
            session_id,
            int((time.monotonic() - started_at) * 1000),
            shutdown_initiator,
            close_code,
            android_audio_frames,
            android_audio_bytes,
            android_camera_frames,
            android_camera_bytes,
            provider_audio_chunks,
            provider_audio_bytes,
            dict(provider_events),
            dict(android_controls),
        )
        if upstream is not None:
            try:
                await upstream.close()
            except Exception:
                pass
        if android.application_state == WebSocketState.CONNECTED:
            try:
                await android.close()
            except Exception:
                pass
