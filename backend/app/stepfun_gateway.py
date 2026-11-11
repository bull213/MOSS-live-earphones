"""StepFun Chat Completions adapter; input and provider details stay private."""

import asyncio
import base64
from time import perf_counter
from typing import Literal

from openai import (
    APIConnectionError, APIError, APIResponseValidationError, APIStatusError,
    APITimeoutError, AsyncOpenAI, AuthenticationError, BadRequestError,
    NotFoundError, PermissionDeniedError, RateLimitError, UnprocessableEntityError,
)
from pydantic import ValidationError

from app.assistant import INSTRUCTIONS, TEXT_INSTRUCTIONS, VISUAL_WITH_HISTORY_INSTRUCTIONS
from app.config import MAX_OUTPUT_TOKENS, MODEL_TIMEOUT_SECONDS
from app.errors import AssistantError
from app.openai_gateway import VisionResult, invalid_response
from app.schemas import AssistantReply


REPLY_SCHEMA = {
    "type": "object",
    "properties": {
        "reply": {"type": "string"},
        "action": {"type": "string", "enum": ["answer", "do_step", "take_photo", "clarify", "complete", "stop"]},
    },
    "required": ["reply", "action"],
    "additionalProperties": False,
}


def translate_provider_error(exc: Exception) -> AssistantError:
    if isinstance(exc, (APITimeoutError, TimeoutError)):
        return AssistantError(504, "model_timeout", "The assistant took too long to respond. Please try again.", retryable=True)
    if isinstance(exc, RateLimitError):
        if exc.code in {"insufficient_quota", "billing_hard_limit_reached"}:
            return AssistantError(503, "provider_quota_exhausted", "The backend's AI quota is exhausted. Check API billing before retrying.")
        return AssistantError(429, "provider_rate_limited", "The assistant is busy. Wait a moment before trying again.", retryable=True)
    if isinstance(exc, (AuthenticationError, PermissionDeniedError, NotFoundError, BadRequestError, UnprocessableEntityError)):
        return AssistantError(503, "provider_configuration_error", "Check the backend's API key, model access and configuration before retrying.")
    if isinstance(exc, APIResponseValidationError):
        return invalid_response()
    if isinstance(exc, (APIConnectionError, APIStatusError)):
        return AssistantError(502, "provider_unavailable", "The AI service could not be reached successfully. Please try again.", retryable=True)
    return invalid_response()


class StepFunGateway:
    def __init__(self, client: AsyncOpenAI, model: str, *,
                 effort: Literal["low", "medium", "high"] = "low",
                 timeout_seconds: float = MODEL_TIMEOUT_SECONDS) -> None:
        self.client = client
        self.model = model
        self.effort = effort
        self.timeout_seconds = timeout_seconds

    async def answer(self, question: str, jpeg: bytes | None, *, history: list[dict] | None = None) -> VisionResult:
        start = perf_counter()
        content: list[dict] = [{"type": "text", "text": question}]
        if jpeg is not None:
            content.append({"type": "image_url", "image_url": {
                "url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode("ascii"),
                "detail": "high",
            }})
        instructions = (VISUAL_WITH_HISTORY_INSTRUCTIONS if history else INSTRUCTIONS) if jpeg is not None else TEXT_INSTRUCTIONS
        try:
            async with asyncio.timeout(self.timeout_seconds):
                response = await self.client.chat.completions.create(
                    model=self.model,
                    messages=([{"role": "system", "content": instructions}] +
                              [{"role": item["role"], "content": item["text"]} for item in history or []] +
                              [{"role": "user", "content": content}]),
                    reasoning_effort=self.effort,
                    max_tokens=MAX_OUTPUT_TOKENS,
                    response_format={"type": "json_schema", "json_schema": {
                        "name": "assistant_reply", "strict": True, "schema": REPLY_SCHEMA,
                    }},
                    stream=False,
                )
                if not response.choices:
                    raise invalid_response()
                choice = response.choices[0]
                if choice.finish_reason != "stop" or choice.message.tool_calls:
                    raise invalid_response()
                if choice.message.refusal:
                    answer = AssistantReply(reply="I cannot help with that request.", action="stop")
                else:
                    if not choice.message.content:
                        raise invalid_response()
                    answer = AssistantReply.model_validate_json(choice.message.content)
                usage = response.usage
                return VisionResult(answer=answer, model_ms=round((perf_counter() - start) * 1000),
                                    input_tokens=usage.prompt_tokens if usage else None,
                                    output_tokens=usage.completion_tokens if usage else None)
        except (APIError, TimeoutError) as exc:
            raise translate_provider_error(exc) from None
        except (ValidationError, ValueError, TypeError, AttributeError, IndexError):
            raise invalid_response() from None
