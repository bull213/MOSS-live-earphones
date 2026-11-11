import asyncio
import json
import logging
from unittest.mock import AsyncMock
from uuid import uuid4, UUID

import pytest
from fastapi.testclient import TestClient

from app.config import MAX_IMAGE_BYTES, MAX_REQUEST_BYTES, Settings
from app.errors import AssistantError
from app.main import create_app
from app.middleware import RequestMiddleware


def post_photo(client, image, **fields):
    return client.post(
        "/v1/turns", data={"question": "Which button changes the input?", **fields},
        files={"image": ("remote.jpg", image, "image/jpeg")},
    )


def test_success_and_correlation(client, gateway, jpeg_bytes, uploaded_files):
    request_id = str(uuid4())
    response = post_photo(
        client, jpeg_bytes, question="  Which button changes the input?  ",
        request_id=request_id, photo_id=str(uuid4()), captured_at="2026-11-04T12:00:00Z",
    )
    assert response.status_code == 200
    body = response.json()
    assert body["request_id"] == request_id == response.headers["x-request-id"]
    assert body["action"] == "do_step"
    assert body["reply"] == "Press the visible INPUT button once."
    assert body["timings_ms"]["server_total"] >= body["timings_ms"]["model"] >= 0
    question, photo = gateway.answer.await_args.args
    assert question == "Which button changes the input?"
    assert photo.startswith(b"\xff\xd8")
    assert uploaded_files and all(file.closed for file in uploaded_files)


def test_generates_request_id(client, jpeg_bytes):
    response = post_photo(client, jpeg_bytes)
    assert response.status_code == 200
    assert UUID(response.json()["request_id"])


@pytest.mark.parametrize("question", ["", "   \n\t", "x" * 1001])
def test_invalid_questions_do_not_reach_provider(client, gateway, jpeg_bytes, question, uploaded_files):
    response = post_photo(client, jpeg_bytes, question=question)
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_request"
    gateway.answer.assert_not_awaited()
    assert uploaded_files and all(file.closed for file in uploaded_files)


def test_accepts_maximum_question_length(client, jpeg_bytes):
    assert post_photo(client, jpeg_bytes, question="x" * 1000).status_code == 200


@pytest.mark.parametrize("fields", [
    {"request_id": "invalid"}, {"photo_id": "invalid"},
    {"captured_at": "2026-11-04T12:00:00"}, {"captured_at": "invalid"},
])
def test_validates_optional_metadata(client, gateway, jpeg_bytes, fields, uploaded_files):
    response = post_photo(client, jpeg_bytes, **fields)
    assert response.status_code == 422
    gateway.answer.assert_not_awaited()
    assert all(file.closed for file in uploaded_files)


def test_missing_image(client, gateway):
    response = client.post(
        "/v1/turns", files={"question": (None, "Which button?")},
    )
    assert response.status_code == 422
    gateway.answer.assert_not_awaited()


@pytest.mark.parametrize("payload,mime,status", [
    (b"broken photograph", "image/jpeg", 400),
    (b"broken photograph", "text/plain", 415),
    (b"", "image/png", 400),
    (b"x" * (MAX_IMAGE_BYTES + 1), "image/jpeg", 413),
])
def test_invalid_uploads_are_closed(client, gateway, uploaded_files, payload, mime, status):
    response = client.post(
        "/v1/turns", data={"question": "Which button?"},
        files={"image": ("photo.jpg", payload, mime)},
    )
    assert response.status_code == status
    gateway.answer.assert_not_awaited()
    assert uploaded_files and all(file.closed for file in uploaded_files)


def test_spooled_upload_closes_after_provider_failure(client, gateway, jpeg_bytes, uploaded_files):
    gateway.answer.side_effect = AssistantError(504, "model_timeout", "Please try again.", retryable=True)
    large_image = jpeg_bytes + b"\0" * 1_100_000
    request_id = str(uuid4())
    response = post_photo(client, large_image, request_id=request_id)
    assert response.status_code == 504
    assert response.json() == {
        "request_id": request_id,
        "error": {"code": "model_timeout", "message": "Please try again.", "retryable": True},
    }
    assert uploaded_files and all(file.closed for file in uploaded_files)
    assert any(file._rolled for file in uploaded_files)


def test_missing_credentials_keeps_health_and_docs_available(jpeg_bytes, uploaded_files):
    with TestClient(create_app(Settings(_env_file=None, openai_api_key=None))) as client:
        assert client.get("/health").json() == {"status": "ok"}
        assert client.get("/docs").status_code == 200
        response = post_photo(client, jpeg_bytes)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "ai_not_configured"
    assert response.json()["error"]["retryable"] is False
    assert all(file.closed for file in uploaded_files)


def test_lifespan_configures_and_closes_sdk_client():
    application = create_app(Settings(_env_file=None, openai_api_key="unit-test-only-key"))
    with TestClient(application) as client:
        assert client.get("/health").status_code == 200
        provider = application.state.vision_gateway.client
        assert provider.max_retries == 0
        assert not provider.is_closed()
    assert provider.is_closed()
    assert application.state.vision_gateway is None


def test_rejects_total_request_before_creating_temporary_files(client, gateway, uploaded_files):
    response = client.post(
        "/v1/turns", content=b"x" * (MAX_REQUEST_BYTES + 1),
        headers={"content-type": "multipart/form-data; boundary=test"},
    )
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "request_too_large"
    assert uploaded_files == []
    gateway.answer.assert_not_awaited()


@pytest.mark.parametrize("declared_length", [None, b"1"])
def test_stream_limit_cannot_be_bypassed_by_missing_or_false_length(declared_length):
    async def scenario():
        inner = AsyncMock()
        headers = [(b"content-type", b"multipart/form-data; boundary=test")]
        if declared_length is not None:
            headers.append((b"content-length", declared_length))
        scope = {"type": "http", "method": "POST", "path": "/v1/turns", "headers": headers}
        messages = iter([
            {"type": "http.request", "body": b"x" * (1024 * 1024), "more_body": True}
            for _ in range(6)
        ])

        async def receive():
            return next(messages)

        sent = []

        async def send(message):
            sent.append(message)

        await RequestMiddleware(inner)(scope, receive, send)
        inner.assert_not_awaited()
        assert sent[0]["status"] == 413
        assert json.loads(sent[1]["body"])["error"]["code"] == "request_too_large"

    asyncio.run(scenario())


def test_malformed_multipart_closes_partial_file(client, uploaded_files, gateway):
    body = (
        b'--test\r\nContent-Disposition: form-data; name="image"; filename="photo.jpg"\r\n'
        b'Content-Type: image/jpeg\r\n\r\npartial image\r\n'
        b'--test\r\nContent-Disposition: form-data\r\n\r\ninvalid unnamed part\r\n--test--\r\n'
    )
    response = client.post("/v1/turns", content=body, headers={"content-type": "multipart/form-data; boundary=test"})
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"
    assert uploaded_files and all(file.closed for file in uploaded_files)
    gateway.answer.assert_not_awaited()


def test_json_request_returns_consistent_error(client):
    response = client.post("/v1/turns", json={"question": "Which button?"})
    assert response.status_code == 415
    assert response.json()["error"]["code"] == "unsupported_media_type"


def test_unexpected_exception_does_not_expose_text(client, gateway, jpeg_bytes, caplog):
    gateway.answer.side_effect = RuntimeError("private-question-and-secret-key")
    response = post_photo(client, jpeg_bytes)
    assert response.status_code == 500
    assert response.json()["error"]["code"] == "internal_error"
    assert "private-question-and-secret-key" not in response.text + caplog.text


def test_logs_metrics_without_question_or_image(client, jpeg_bytes, caplog):
    caplog.set_level(logging.INFO, logger="uvicorn.error.visual_assistant")
    response = post_photo(client, jpeg_bytes, question="PRIVATE_QUESTION_DO_NOT_LOG")
    assert response.status_code == 200
    assert "input_tokens=100" in caplog.text
    assert "prompt_version=visual-assistant-v1" in caplog.text
    assert "PRIVATE_QUESTION_DO_NOT_LOG" not in caplog.text
    assert "base64" not in caplog.text


@pytest.mark.parametrize("failure,status,code,retryable", [
    ("validation", 422, "invalid_request", False),
    ("media_type", 415, "unsupported_media_type", False),
    ("provider", 504, "model_timeout", True),
])
def test_error_logs_only_safe_metadata(client, gateway, jpeg_bytes, caplog, failure, status, code, retryable):
    question = "PRIVATE_QUESTION_DO_NOT_LOG"
    if failure == "validation":
        response = post_photo(client, jpeg_bytes, question=question * 50)
    elif failure == "media_type":
        response = client.post("/v1/turns", json={"question": question})
    else:
        gateway.answer.side_effect = AssistantError(
            504, "model_timeout", "PRIVATE_PROVIDER_DETAIL_DO_NOT_LOG", retryable=True,
        )
        response = post_photo(client, jpeg_bytes, question=question)

    assert response.status_code == status
    records = [record for record in caplog.records if record.getMessage().startswith("request_error ")]
    assert len(records) == 1
    assert records[0].getMessage() == (
        f"request_error request_id={response.headers['x-request-id']} "
        f"status_code={status} code={code} retryable={retryable}"
    )
    assert response.json()["request_id"] == response.headers["x-request-id"]
    assert question not in caplog.text
    assert "PRIVATE_PROVIDER_DETAIL_DO_NOT_LOG" not in caplog.text
    assert "base64" not in caplog.text
    assert all(record.exc_info is None for record in records)


def test_openapi_describes_multipart_and_error_contract(client):
    schema = client.get("/openapi.json").json()
    endpoint = schema["paths"]["/v1/turns"]["post"]
    assert "multipart/form-data" in endpoint["requestBody"]["content"]
    assert endpoint["responses"]["422"]["content"]["application/json"]["schema"]["$ref"].endswith("/ErrorResponse")
