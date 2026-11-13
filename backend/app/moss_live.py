"""Loopback-facing WebSocket gateway for real MOSS Live sessions."""

import asyncio
from collections import Counter
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import re
import secrets
import time
from typing import Any
from urllib.parse import quote
from uuid import uuid4

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

from app.config import Settings
from app.security import is_loopback_or_private
from app.notebooklm import (
    NotebookLMAuthExpiredError,
    build_moss_live_notebook_instructions,
    match_notebook_from_speech,
    sanitize_grounded_voice_summary,
)

# Backwards compatibility aliases for MOSS Live helper routines
_prepare_grounded_voice_summary = sanitize_grounded_voice_summary
_find_matching_notebook = match_notebook_from_speech

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
SUSPENDED_PING_INTERVAL_SECONDS = 15.0
UPSTREAM_429_COOLDOWN_SECONDS = 5.0
ALLOWED_CLIENT_ACTIONS = {
    "mic_state",
    "media.mode",
    "video.state",
    "video.stream_focus",
    "session.instructions.append",
    "playback.chunk.completed",
    "playback.completed",
    "playback.stopped",
    "response.cancel",
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


async def _query_notebook_and_inject(
    upstream: Any,
    android: Any,
    service: Any,
    notebook: dict[str, Any],
    query: str,
    on_complete: Any = None,
    timeout: float = 25.0,
) -> None:
    current_task = asyncio.current_task()
    logger.info("moss_notebooklm_query_started notebook=%s query=%s", notebook.get("title"), query)
    try:
        async with asyncio.timeout(timeout):
            nlm_result = await service.ask_chat(notebook["id"], query, timeout=timeout)
            if nlm_result and nlm_result.answer:
                summary_text = sanitize_grounded_voice_summary(nlm_result.answer, max_chars=800)
                reanchored = f"According to {notebook.get('title')}, {summary_text}"
                append_msg = (
                    f"[Grounded Knowledge from Notebook '{notebook.get('title')}']\n"
                    f"{reanchored}\n"
                    f"Please speak this grounded information aloud to the user right now in 1-2 conversational sentences."
                )
                command = _client_command("session.instructions.append", content=append_msg[:1000])
                await upstream.send(command)
                await upstream.send(_client_command("response.create"))
                logger.info("moss_notebooklm_query_injected notebook=%s chars=%d", notebook.get("title"), len(append_msg))
    except asyncio.TimeoutError:
        logger.warning("moss_notebooklm_query_timeout notebook=%s", notebook.get("title"))
        recovery_msg = f"Please say aloud to the user: 'I couldn't retrieve information from your {notebook.get('title')} notebook in time. What else can I help you with?'"
        try:
            await upstream.send(_client_command("session.instructions.append", content=recovery_msg))
            await upstream.send(_client_command("response.create"))
        except Exception:
            pass
    except NotebookLMAuthExpiredError:
        logger.warning("moss_notebooklm_auth_expired notebook=%s", notebook.get("title"))
        recovery_msg = "Please say aloud to the user: 'Your Google session credentials expired. Please reconnect in settings.'"
        try:
            await upstream.send(_client_command("session.instructions.append", content=recovery_msg))
            await upstream.send(_client_command("response.create"))
        except Exception:
            pass
    except Exception as exc:
        logger.warning("moss_notebooklm_query_failed notebook=%s error=%s", notebook.get("title"), exc)
    finally:
        if current_task is not None and callable(on_complete):
            try:
                on_complete(current_task)
            except Exception:
                pass
        try:
            await android.send_text(json.dumps({"action": "video.stream_resume"}, separators=(",", ":")))
        except Exception:
            pass


class MossLiveRelaySession:
    """Manages duplex frame gating, turn preemption, and bidirectional relay between Android and MOSS."""

    def __init__(
        self,
        session_id: str,
        android: WebSocket,
        upstream: Any,
        service: Any,
        notebook_list: list[dict[str, Any]],
        started_at: float,
    ) -> None:
        self.session_id = session_id
        self.android = android
        self.upstream = upstream
        self.service = service
        self.notebook_list = notebook_list
        self.started_at = started_at

        self.current_notebook: dict[str, Any] | None = None
        self.pending_confirmation_notebook: dict[str, Any] | None = None
        self.pending_confirmation_query: str = ""
        self.gate_camera_frames: bool = False
        self.active_retrieval_task: asyncio.Task | None = None
        self.retrieval_started_at: float = 0.0

        self.android_audio_frames: int = 0
        self.android_audio_bytes: int = 0
        self.android_camera_frames: int = 0
        self.android_camera_bytes: int = 0
        self.android_camera_frames_suppressed: int = 0
        self.provider_audio_chunks: int = 0
        self.provider_audio_bytes: int = 0
        self.provider_events: Counter[str] = Counter()
        self.android_controls: Counter[str] = Counter()
        self.shutdown_initiator: str = "unknown"
        self.close_code: int | None = None
        self.ping_task: asyncio.Task | None = None

    def _start_ping_loop(self) -> None:
        if self.ping_task is None or self.ping_task.done():
            self.ping_task = asyncio.create_task(self._ping_loop(), name="moss-suspended-ping")

    def _stop_ping_loop(self) -> None:
        if self.ping_task is not None and not self.ping_task.done():
            self.ping_task.cancel()
            self.ping_task = None

    async def _ping_loop(self) -> None:
        try:
            while True:
                await asyncio.sleep(SUSPENDED_PING_INTERVAL_SECONDS)
                try:
                    await self.upstream.send(json.dumps({"action": "ping"}, separators=(",", ":")))
                    if hasattr(self.upstream, "ping"):
                        await self.upstream.ping()
                except Exception as exc:
                    logger.debug("moss_ping_failed error=%s", exc)
                    break
        except asyncio.CancelledError:
            pass

    def on_retrieval_complete(self, task: asyncio.Task) -> None:
        if self.active_retrieval_task is task:
            self.gate_camera_frames = False
            self.active_retrieval_task = None

    async def _handle_android_text(self, text: str) -> bool:
        if not _valid_android_event(text):
            await _notify_error(self.android, "invalid_event", "The realtime session received an invalid control message.")
            return False
        try:
            parsed = json.loads(text)
            client_action = parsed.get("action")
            if client_action == "mic_state":
                enabled = parsed.get("enabled", True)
                if not enabled:
                    self._start_ping_loop()
                else:
                    self._stop_ping_loop()
        except (json.JSONDecodeError, AttributeError):
            client_action = "other"
        await self.upstream.send(text)
        self.android_controls[client_action if client_action in ALLOWED_CLIENT_ACTIONS else "other"] += 1
        return True

    async def _handle_android_binary(self, binary: bytes) -> bool:
        if len(binary) > MAX_ANDROID_BINARY_BYTES:
            await _notify_error(self.android, "media_too_large", "A realtime media frame exceeded the allowed size.")
            return False
        if len(binary) > 0 and binary[0] == 0x01:
            if self.gate_camera_frames:
                self.android_camera_frames_suppressed += 1
                return True
            self.android_camera_frames += 1
            self.android_camera_bytes += len(binary)
            await self.upstream.send(binary)
        else:
            self.android_audio_frames += 1
            self.android_audio_bytes += len(binary)
            await self.upstream.send(binary)
        return True

    async def pump_android_to_provider(self) -> None:
        while True:
            packet = await self.android.receive()
            kind = packet["type"]
            if kind == "websocket.disconnect":
                self.shutdown_initiator = "android_disconnect"
                self.close_code = packet.get("code")
                return
            if kind == "websocket.receive":
                text = packet.get("text")
                binary = packet.get("bytes")
                if text is not None:
                    if not await self._handle_android_text(text):
                        return
                elif binary is not None:
                    if not await self._handle_android_binary(binary):
                        return

    async def _trigger_notebook_retrieval(self, matched_nb: dict[str, Any], query: str) -> bool:
        self.current_notebook = matched_nb
        if self.active_retrieval_task is not None and not self.active_retrieval_task.done():
            self.active_retrieval_task.cancel()
            self.active_retrieval_task = None

        try:
            await self.upstream.send(_client_command("response.cancel"))
        except Exception:
            pass

        self.gate_camera_frames = True
        self.retrieval_started_at = time.monotonic()
        try:
            await self.android.send_text(json.dumps({"action": "video.stream_pause"}, separators=(",", ":")))
        except Exception:
            pass

        holding_text = f"Checking your '{matched_nb.get('title')}' notebook now. One moment..."
        try:
            holding_cmd = _client_command(
                "session.instructions.append",
                content=f"Please say aloud to the user right now: '{holding_text}'"
            )
            await self.upstream.send(holding_cmd)
            await self.upstream.send(_client_command("response.create"))
        except Exception as exc:
            logger.warning("moss_holding_phrase_failed error=%s", exc)

        self.active_retrieval_task = asyncio.create_task(
            _query_notebook_and_inject(
                upstream=self.upstream,
                android=self.android,
                service=self.service,
                notebook=matched_nb,
                query=query,
                on_complete=self.on_retrieval_complete,
            ),
            name=f"moss-nlm-query-{matched_nb.get('id')}"
        )
        return False

    async def _handle_transcript_done(self, user_text: str) -> bool:
        """Handle finalized transcript from provider, checking cancellation, confirmation or notebook matching."""
        logger.info("moss_live_transcript_done session=%s text=%r", self.session_id, user_text)
        if not (user_text and self.service is not None and getattr(self.service, "has_auth", lambda: False)()):
            return False

        lower_text = user_text.lower().strip()

        # Check if awaiting confirmation for an inexact or ambiguous notebook match
        if self.pending_confirmation_notebook is not None:
            affirmative_words = {"yes", "yeah", "yep", "sure", "correct", "that one", "please", "yes please", "right", "כן", "נכון", "בדיוק"}
            negative_words = {"no", "nope", "cancel", "never mind", "wrong", "not that", "stop", "לא", "בטל"}
            tokens = set(re.findall(r"[\w\u0590-\u05fe]+", lower_text))

            if tokens & affirmative_words or any(lower_text.startswith(w) for w in affirmative_words):
                confirmed_nb = self.pending_confirmation_notebook
                query = self.pending_confirmation_query or user_text
                self.pending_confirmation_notebook = None
                self.pending_confirmation_query = ""
                logger.info("moss_notebook_confirmed notebook=%s", confirmed_nb.get("title"))
                return await self._trigger_notebook_retrieval(confirmed_nb, query)
            elif tokens & negative_words or any(lower_text.startswith(w) for w in negative_words):
                self.pending_confirmation_notebook = None
                self.pending_confirmation_query = ""
                logger.info("moss_notebook_confirmation_declined text=%r", user_text)
                return False
            else:
                self.pending_confirmation_notebook = None
                self.pending_confirmation_query = ""

        is_cancel = lower_text in {"cancel", "never mind", "stop", "abort", "cancel that"}
        if is_cancel and self.active_retrieval_task is not None and not self.active_retrieval_task.done():
            self.active_retrieval_task.cancel()
            self.active_retrieval_task = None
            self.gate_camera_frames = False
            try:
                await self.android.send_text(json.dumps({"action": "video.stream_resume"}, separators=(",", ":")))
            except Exception:
                pass
            return True

        matched_nb, stripped_query = match_notebook_from_speech(user_text, self.notebook_list, self.current_notebook)
        if matched_nb is not None:
            query = stripped_query if stripped_query else user_text
            if matched_nb.get("_needs_confirmation"):
                self.pending_confirmation_notebook = matched_nb
                self.pending_confirmation_query = query
                confirmation_prompt = matched_nb.get(
                    "_confirmation_prompt",
                    f"Did you mean the '{matched_nb.get('title')}' notebook?",
                )
                logger.info("moss_notebook_asking_confirmation notebook=%s prompt=%r", matched_nb.get("title"), confirmation_prompt)
                try:
                    prompt_cmd = _client_command(
                        "session.instructions.append",
                        content=f"Please ask the user right now: '{confirmation_prompt}'",
                    )
                    await self.upstream.send(prompt_cmd)
                    await self.upstream.send(_client_command("response.create"))
                except Exception as exc:
                    logger.warning("moss_confirmation_prompt_failed error=%s", exc)
                return False

            return await self._trigger_notebook_retrieval(matched_nb, query)
        return False

    async def pump_provider_to_android(self) -> None:
        while True:
            message = await self.upstream.recv()
            if isinstance(message, bytes):
                if len(message) > MAX_PROVIDER_BINARY_BYTES:
                    await _notify_error(self.android, "provider_message_too_large", "MOSS sent an unsupported media frame.")
                    return
                await self.android.send_bytes(message)
                self.provider_audio_chunks += 1
                self.provider_audio_bytes += len(message)
            else:
                if len(message.encode("utf-8")) > MAX_PROVIDER_TEXT_BYTES:
                    await _notify_error(self.android, "provider_message_too_large", "MOSS sent an unsupported event.")
                    return
                try:
                    event = json.loads(message)
                except (json.JSONDecodeError, UnicodeError):
                    await _notify_error(self.android, "provider_protocol_error", "MOSS returned an unreadable event.")
                    return
                if not isinstance(event, dict) or not isinstance(event.get("action"), str):
                    await _notify_error(self.android, "provider_protocol_error", "MOSS returned an unreadable event.")
                    return
                action = event["action"]
                if action == "error":
                    logger.error("moss_upstream_error_event session=%s data=%s", self.session_id, event.get("data"))
                    # Suppress non-fatal upstream command rejections from crashing the Android call
                    error_data = event.get("data")
                    if isinstance(error_data, dict):
                        err_obj = error_data.get("error") if isinstance(error_data.get("error"), dict) else error_data
                        code = err_obj.get("code")
                        if code in {"invalid_public_request", "unsupported_request", "rate_limit_warning"}:
                            logger.warning("Suppressed non-fatal upstream error event code=%s from disconnecting Android", code)
                            continue
                if action == "input.transcript.done":
                    user_text = event.get("data", {}).get("text", "")
                    consumed = await self._handle_transcript_done(user_text)
                    if consumed:
                        continue
                await self.android.send_text(message)
                self.provider_events[action if action in SAFE_PROVIDER_ACTIONS else "other"] += 1

    def close_in_flight(self) -> None:
        self._stop_ping_loop()
        if self.active_retrieval_task is not None and not self.active_retrieval_task.done():
            self.active_retrieval_task.cancel()

    def log_diagnostics(self) -> None:
        logger.info(
            "MOSS Live session=%s elapsed_ms=%d shutdown=%s close_code=%s "
            "android_audio_frames=%d android_audio_bytes=%d android_camera_frames=%d android_camera_bytes=%d "
            "android_camera_frames_suppressed=%d "
            "provider_audio_chunks=%d provider_audio_bytes=%d "
            "provider_events=%s android_controls_forwarded=%s",
            self.session_id,
            int((time.monotonic() - self.started_at) * 1000),
            self.shutdown_initiator,
            self.close_code,
            self.android_audio_frames,
            self.android_audio_bytes,
            self.android_camera_frames,
            self.android_camera_bytes,
            self.android_camera_frames_suppressed,
            self.provider_audio_chunks,
            self.provider_audio_bytes,
            dict(self.provider_events),
            dict(self.android_controls),
        )


@router.websocket("/v1/realtime/moss-live")
async def moss_live_gateway(android: WebSocket) -> None:
    client_host = android.client.host if android.client else ""
    if not is_loopback_or_private(client_host):
        await android.accept()
        await _notify_error(android, "forbidden", "Access denied from public network.")
        await android.close(code=4403, reason="Forbidden")
        return

    settings: Settings = android.app.state.settings
    if settings.api_bearer_token is not None:
        expected = settings.api_bearer_token.get_secret_value()
        if expected:
            auth_header = android.headers.get("authorization", "")
            token = None
            if auth_header.lower().startswith("bearer "):
                token = auth_header[7:].strip()
            elif "token" in android.query_params:
                token = android.query_params["token"]

            if not token or not secrets.compare_digest(token, expected):
                await android.accept()
                await _notify_error(android, "unauthorized", "Valid Bearer token required.")
                await android.close(code=4401, reason="Unauthorized")
                return

    await android.accept()
    if settings.moss_api_key is None:
        await _notify_error(android, "moss_not_configured", "MOSS Live is not configured on this computer.")
        await android.close(code=1013, reason="MOSS Live is not configured")
        return

    last_429_time = getattr(android.app.state, "last_upstream_429_time", 0.0)
    now = time.monotonic()
    elapsed_since_429 = now - last_429_time
    if elapsed_since_429 < UPSTREAM_429_COOLDOWN_SECONDS:
        remaining = int(UPSTREAM_429_COOLDOWN_SECONDS - elapsed_since_429) + 1
        logger.warning("moss_cooldown_active remaining=%ds", remaining)
        await _notify_error(
            android,
            "moss_upstream_rejected",
            f"MOSS rate limit cooling down. Please wait {remaining} seconds before retrying.",
        )
        await android.close(code=1008, reason="Rate limit cooldown")
        return

    upstream = None
    relay: MossLiveRelaySession | None = None
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
        await android.send_text(json.dumps(attached, separators=(",", ":")))

        service = getattr(android.app.state, "notebooklm_service", None)
        notebook_list: list[dict] = []
        instructions = SESSION_INSTRUCTIONS
        if service is not None and getattr(service, "has_auth", lambda: False)():
            try:
                notebook_list = await service.list_notebooks()
                if notebook_list:
                    titles = [nb["title"] for nb in notebook_list if nb.get("title")]
                    instructions = build_moss_live_notebook_instructions(SESSION_INSTRUCTIONS, titles)
            except Exception as exc:
                logger.warning("moss_notebooklm_discovery_failed error=%s", exc)

        await upstream.send(_client_command(
            "session.update", session={"instructions": instructions},
        ))
        configured = await _provider_event(upstream)
        if configured["action"] != "session.updated" or configured.get("data", {}).get("error"):
            await _notify_error(android, "moss_configuration_failed", "MOSS could not prepare the conversation.")
            await android.close(code=1011, reason="MOSS session configuration failed")
            return
        await android.send_text(json.dumps(configured, separators=(",", ":")))

        session_id = uuid4().hex[:12]
        started_at = time.monotonic()
        relay = MossLiveRelaySession(
            session_id=session_id,
            android=android,
            upstream=upstream,
            service=service,
            notebook_list=notebook_list,
            started_at=started_at,
        )
        relay.provider_events["session_attached"] += 1
        relay.provider_events["session.updated"] += 1

        tasks = {
            asyncio.create_task(relay.pump_android_to_provider(), name="moss-android-to-provider"),
            asyncio.create_task(relay.pump_provider_to_android(), name="moss-provider-to-android"),
        }
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            if task.cancelled():
                continue
            error = task.exception()
            if error is not None:
                logger.error("task_failed name=%s error=%s", task.get_name(), error, exc_info=error)
            if task.get_name() == "moss-provider-to-android":
                relay.shutdown_initiator = "provider_socket_closed"
            elif task.get_name() == "moss-android-to-provider" and error is None:
                relay.shutdown_initiator = "android_disconnect"
            if error is not None and not isinstance(error, (WebSocketDisconnect, ConnectionClosed, asyncio.CancelledError)):
                await _notify_error(android, "moss_connection_lost", "The MOSS connection ended. Stop and start Live again.")
            if isinstance(error, ConnectionClosed):
                relay.close_code = error.code
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
    except WebSocketDisconnect:
        if relay is not None:
            relay.shutdown_initiator = "android_disconnect"
    except ConnectionClosed as exc:
        if relay is not None:
            relay.shutdown_initiator = "provider_socket_closed"
            relay.close_code = exc.code
        await _notify_error(android, "moss_connection_lost", "The MOSS connection ended. Stop and start Live again.")
    except asyncio.TimeoutError:
        if relay is not None:
            relay.shutdown_initiator = "provider_timeout"
        await _notify_error(android, "moss_timeout", "MOSS did not respond in time. Stop and try again.")
    except Exception as exc:
        if relay is not None:
            relay.shutdown_initiator = "relay_exception"
        # Exception text and provider bodies may contain sensitive handshake details.
        status = _upstream_status(exc)
        if status == 429:
            android.app.state.last_upstream_429_time = time.monotonic()
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
        if relay is not None:
            relay.close_in_flight()
            relay.log_diagnostics()
        if upstream is not None:
            try:
                code_to_send = relay.close_code if (relay is not None and relay.close_code is not None) else 1000
                await upstream.close(code=code_to_send)
            except TypeError:
                try:
                    await upstream.close()
                except Exception:
                    pass
            except Exception:
                pass
        if android.application_state == WebSocketState.CONNECTED:
            try:
                await android.close()
            except Exception:
                pass
