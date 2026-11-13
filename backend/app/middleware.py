"""Request correlation and bounded reads before multipart files are created."""

import logging
import secrets
from time import perf_counter
from uuid import uuid4

from starlette.datastructures import Headers
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.config import MAX_REQUEST_BYTES
from app.errors import AssistantError, error_response


logger = logging.getLogger("uvicorn.error.visual_assistant")


class RequestMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        state = scope.setdefault("state", {})
        state["request_id"] = uuid4()
        state["started_at"] = perf_counter()
        response_started = False

        async def send_with_id(message: Message) -> None:
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
                message["headers"] = list(message.get("headers", [])) + [
                    (b"x-request-id", str(state["request_id"]).encode("ascii"))
                ]
            await send(message)

        try:
            # Bearer token verification if api_bearer_token is configured
            path = scope.get("path", "").rstrip("/")
            method = scope.get("method", "")
            if path != "/health" and method != "OPTIONS":
                app = scope.get("app")
                state_obj = getattr(app, "state", None)
                settings = getattr(state_obj, "settings", None) if state_obj else None
                if settings is not None and getattr(settings, "api_bearer_token", None) is not None:
                    expected = settings.api_bearer_token.get_secret_value()
                    if expected:
                        headers = Headers(scope=scope)
                        auth_header = headers.get("authorization", "")
                        scheme, _, param = auth_header.partition(" ")
                        if scheme.lower() != "bearer" or not secrets.compare_digest(param.strip(), expected):
                            raise AssistantError(401, "unauthorized", "Valid Bearer token required.")

            if scope["method"] == "POST" and scope["path"].rstrip("/") in {"/v1/turns", "/v1/assistant/turns"}:
                headers = Headers(scope=scope)
                if headers.get("content-type", "").split(";", 1)[0].strip().lower() != "multipart/form-data":
                    raise AssistantError(
                        415, "unsupported_media_type", "Send a question and image using multipart form data."
                    )
                declared_size = headers.get("content-length")
                if declared_size is not None:
                    if not declared_size.isascii() or not declared_size.isdecimal():
                        raise AssistantError(400, "invalid_request", "The request length is invalid.")
                    if len(declared_size) > 10 or int(declared_size) > MAX_REQUEST_BYTES:
                        raise AssistantError(413, "request_too_large", "The upload exceeds the 5 MiB request limit.")

                body = bytearray()
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    chunk = message.get("body", b"")
                    if len(body) + len(chunk) > MAX_REQUEST_BYTES:
                        raise AssistantError(413, "request_too_large", "The upload exceeds the 5 MiB request limit.")
                    body.extend(chunk)
                    if not message.get("more_body", False):
                        break

                delivered = False

                async def bounded_receive() -> Message:
                    nonlocal delivered
                    if not delivered:
                        delivered = True
                        payload = bytes(body)
                        body.clear()
                        return {"type": "http.request", "body": payload, "more_body": False}
                    return await receive()

                await self.app(scope, bounded_receive, send_with_id)
            else:
                await self.app(scope, receive, send_with_id)
        except AssistantError as exc:
            response = error_response(exc, state["request_id"])
            await response(scope, receive, send_with_id)
        except Exception as exc:
            # Do not log exception text/tracebacks: they can contain user data or keys.
            logger.error(
                "request_failed request_id=%s error_type=%s",
                state["request_id"], type(exc).__name__,
            )
            if response_started:
                raise
            response = error_response(
                AssistantError(500, "internal_error", "The assistant could not process this request."),
                state["request_id"],
            )
            await response(scope, receive, send_with_id)
