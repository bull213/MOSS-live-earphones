import asyncio
import base64
import json

import httpx2
import pytest
from openai import AsyncOpenAI

from app.errors import AssistantError
from app.stepfun_gateway import StepFunGateway


def response(text=None, *, finish="stop", refusal=None, tool_calls=None):
    return {
        "id": "test", "object": "chat.completion", "created": 1, "model": "step-5-preview",
        "choices": [{"index": 0, "finish_reason": finish, "message": {
            "role": "assistant", "content": text if text is not None else '{"reply":"Try the INPUT button.","action":"do_step"}',
            "refusal": refusal, "tool_calls": tool_calls,
        }}],
        "usage": {"prompt_tokens": 120, "completion_tokens": 35, "total_tokens": 155},
    }


def run_gateway(handler, jpeg=None, timeout=30):
    async def run():
        async with AsyncOpenAI(api_key="test-key", max_retries=0, base_url="https://api.stepfun.ai/v1",
                http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(handler))) as client:
            return await StepFunGateway(client, "step-5-preview", timeout_seconds=timeout).answer("My question", jpeg)
    return asyncio.run(run())


@pytest.mark.parametrize("with_image", [True, False])
def test_sdk_sends_documented_chat_format_and_validates_reply(with_image, jpeg_bytes):
    requests = []
    def handler(request):
        requests.append(request)
        return httpx2.Response(200, json=response())
    result = run_gateway(handler, jpeg_bytes if with_image else None)
    assert result.answer.action == "do_step"
    assert (result.input_tokens, result.output_tokens) == (120, 35)
    assert requests[0].url.path == "/v1/chat/completions"
    payload = json.loads(requests[0].content)
    assert payload["model"] == "step-5-preview"
    assert payload["reasoning_effort"] == "low"
    assert payload["max_tokens"] == 1500
    assert payload["response_format"]["json_schema"]["strict"] is True
    assert "tools" not in payload
    parts = payload["messages"][1]["content"]
    assert parts[0] == {"type": "text", "text": "My question"}
    if with_image:
        assert parts[1]["image_url"]["detail"] == "high"
        assert base64.b64decode(parts[1]["image_url"]["url"].split(",", 1)[1]) == jpeg_bytes
    else:
        assert len(parts) == 1


@pytest.mark.parametrize("content", ["not json", "{}", '{"reply":" ","action":"answer"}',
    '{"reply":"ok","action":"unknown"}', '{"reply":"ok","action":"answer","extra":1}'])
def test_invalid_content_is_rejected(content):
    with pytest.raises(AssistantError) as caught:
        run_gateway(lambda request: httpx2.Response(200, json=response(content)))
    assert caught.value.code == "invalid_model_response"


@pytest.mark.parametrize("payload", [response(finish="length"), {"id": "test", "choices": []},
    response(finish="tool_calls", tool_calls=[{"id": "call", "type": "function", "function": {"name": "x", "arguments": "{}"}}])])
def test_incomplete_or_unexpected_tool_call_is_rejected(payload):
    with pytest.raises(AssistantError) as caught:
        run_gateway(lambda request: httpx2.Response(200, json=payload))
    assert caught.value.code == "invalid_model_response"


def test_refusal_is_a_safe_stop_without_provider_text():
    result = run_gateway(lambda request: httpx2.Response(200, json=response(refusal="PRIVATE")))
    assert result.answer.action == "stop"
    assert "PRIVATE" not in result.answer.reply


@pytest.mark.parametrize("status,code,expected", [
    (401, "invalid_api_key", "provider_configuration_error"),
    (429, "insufficient_quota", "provider_quota_exhausted"),
    (429, "rate_limit", "provider_rate_limited"),
    (500, "error", "provider_unavailable"),
])
def test_provider_errors_are_sanitized_without_retry(status, code, expected):
    requests = []
    def handler(request):
        requests.append(request)
        return httpx2.Response(status, json={"error": {"message": "PRIVATE", "code": code}})
    with pytest.raises(AssistantError) as caught:
        run_gateway(handler)
    assert caught.value.code == expected
    assert "PRIVATE" not in str(caught.value)
    assert len(requests) == 1


def test_timeout():
    async def handler(request):
        await asyncio.sleep(1)
        return httpx2.Response(200, json=response())
    with pytest.raises(AssistantError) as caught:
        run_gateway(handler, timeout=.01)
    assert caught.value.code == "model_timeout"
