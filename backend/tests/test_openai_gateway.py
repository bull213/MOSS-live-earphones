import asyncio
import base64
import json

import httpx2
import pytest
from openai import AsyncOpenAI

from app.errors import AssistantError
from app.openai_gateway import OpenAIVisionGateway


def provider_response(text=None):
    return {
        "id": "resp_test", "object": "response", "created_at": 1793793600,
        "model": "gpt-6.1-terra", "status": "completed", "error": None,
        "output": [{
            "id": "msg_test", "type": "message", "role": "assistant",
            "status": "completed", "phase": "final_answer",
            "content": [{
                "type": "output_text", "annotations": [],
                "text": text if text is not None else json.dumps({"reply": "Please take a closer photo of the labels.", "action": "take_photo"}),
            }],
        }],
        "usage": {"input_tokens": 120, "output_tokens": 35, "total_tokens": 155},
    }


def run_gateway(handler, jpeg=b"test-jpeg", timeout=30):
    async def run():
        async with AsyncOpenAI(
            api_key="unit-test-only-key", max_retries=0,
            base_url="https://api.openai.com/v1",
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
        ) as client:
            return await OpenAIVisionGateway(
                client, "gpt-6.1-terra", timeout_seconds=timeout,
            ).answer("Which button?", jpeg)

    return asyncio.run(run())


def test_real_sdk_serialization_and_structured_parsing(jpeg_bytes):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx2.Response(200, json=provider_response())

    result = run_gateway(handler, jpeg_bytes)
    assert result.answer.action == "take_photo"
    assert result.input_tokens == 120 and result.output_tokens == 35
    assert result.model_ms >= 0
    assert len(requests) == 1
    request = requests[0]
    assert request.url.path == "/v1/responses"
    payload = json.loads(request.content)
    assert payload["model"] == "gpt-6.1-terra"
    assert payload["store"] is False
    assert payload["reasoning"] == {"effort": "low"}
    assert payload["max_output_tokens"] == 1500
    assert payload["text"]["format"]["strict"] is True
    assert payload["text"]["format"]["schema"]["additionalProperties"] is False
    assert "Never invent" in payload["instructions"]
    assert "untrusted scene content" in payload["instructions"]
    assert "tools" not in payload and "previous_response_id" not in payload
    image = payload["input"][0]["content"][1]
    assert image["type"] == "input_image" and image["detail"] == "high"
    assert base64.b64decode(image["image_url"].split(",", 1)[1]) == jpeg_bytes


@pytest.mark.parametrize("status,code,expected_status,expected_code,retryable", [
    (401, "invalid_api_key", 503, "provider_configuration_error", False),
    (403, "permission_denied", 503, "provider_configuration_error", False),
    (404, "model_not_found", 503, "provider_configuration_error", False),
    (400, "invalid_request", 503, "provider_configuration_error", False),
    (422, "invalid_request", 503, "provider_configuration_error", False),
    (429, "rate_limit_exceeded", 429, "provider_rate_limited", True),
    (429, "insufficient_quota", 503, "provider_quota_exhausted", False),
    (500, "server_error", 502, "provider_unavailable", True),
])
def test_provider_errors_are_sanitized_without_retry(status, code, expected_status, expected_code, retryable):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx2.Response(status, json={"error": {"message": "PRIVATE_QUESTION_AND_KEY", "code": code, "type": "test_error"}})

    with pytest.raises(AssistantError) as caught:
        run_gateway(handler)
    assert caught.value.status_code == expected_status
    assert caught.value.code == expected_code
    assert caught.value.retryable is retryable
    assert "PRIVATE_QUESTION_AND_KEY" not in str(caught.value)
    assert len(requests) == 1


@pytest.mark.parametrize("text", [
    "not JSON", "{}",
    '{"reply":"   ","action":"answer"}',
    '{"reply":"Something","action":"invented_action"}',
    json.dumps({"reply": "x" * 1001, "action": "answer"}),
    '{"reply":"Answer","action":"answer","unexpected":"field"}',
])
def test_invalid_structured_output_is_not_returned(text):
    with pytest.raises(AssistantError) as caught:
        run_gateway(lambda request: httpx2.Response(200, json=provider_response(text)))
    assert caught.value.code == "invalid_model_response"
    assert caught.value.status_code == 502


@pytest.mark.parametrize("variant", ["incomplete", "failed", "empty", "partial_message", "commentary"])
def test_incomplete_or_missing_final_answer_is_not_returned(variant):
    payload = provider_response()
    if variant in {"incomplete", "failed"}:
        payload["status"] = variant
    elif variant == "empty":
        payload["output"] = []
    elif variant == "partial_message":
        payload["output"][0]["status"] = "incomplete"
    else:
        payload["output"][0]["phase"] = "commentary"
    with pytest.raises(AssistantError) as caught:
        run_gateway(lambda request: httpx2.Response(200, json=payload))
    assert caught.value.code == "invalid_model_response"


def test_refusal_becomes_safe_stop_reply():
    payload = provider_response()
    payload["output"][0]["content"] = [{"type": "refusal", "refusal": "PRIVATE_PROVIDER_TEXT"}]
    result = run_gateway(lambda request: httpx2.Response(200, json=payload))
    assert result.answer.action == "stop"
    assert "PRIVATE_PROVIDER_TEXT" not in result.answer.reply


def test_total_operation_deadline():
    async def handler(request):
        await asyncio.sleep(1)
        return httpx2.Response(200, json=provider_response())

    with pytest.raises(AssistantError) as caught:
        run_gateway(handler, timeout=0.01)
    assert caught.value.status_code == 504
    assert caught.value.code == "model_timeout"


@pytest.mark.parametrize("error_type,expected_status", [(httpx2.ReadTimeout, 504), (httpx2.ConnectError, 502)])
def test_transport_failures(error_type, expected_status):
    calls = []

    def handler(request):
        calls.append(request)
        raise error_type("PRIVATE_TRANSPORT_DETAILS", request=request)

    with pytest.raises(AssistantError) as caught:
        run_gateway(handler)
    assert caught.value.status_code == expected_status
    assert "PRIVATE_TRANSPORT_DETAILS" not in str(caught.value)
    assert len(calls) == 1
