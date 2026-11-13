"""One provider adapter; no transport or SDK details escape this module."""

import asyncio
import base64
from dataclasses import dataclass
from time import perf_counter
from typing import Protocol

from openai import (
    APIConnectionError, APIError, APIResponseValidationError, APIStatusError,
    APITimeoutError, AsyncOpenAI, AuthenticationError, BadRequestError,
    NotFoundError, PermissionDeniedError, RateLimitError, UnprocessableEntityError,
)
from pydantic import ValidationError

from app.assistant import INSTRUCTIONS, TEXT_INSTRUCTIONS, VISUAL_WITH_HISTORY_INSTRUCTIONS
from app.config import MAX_OUTPUT_TOKENS, MODEL_TIMEOUT_SECONDS
from app.errors import AssistantError
from app.schemas import AssistantReply


@dataclass(frozen=True)
class VisionResult:
    answer: AssistantReply
    model_ms: int
    input_tokens: int | None = None
    output_tokens: int | None = None


class VisionGateway(Protocol):
    async def answer(self, question: str, jpeg: bytes | None = None) -> VisionResult: ...


def invalid_response() -> AssistantError:
    return AssistantError(
        502, "invalid_model_response",
        "The assistant could not produce a complete answer. Please try again.",
        retryable=True,
    )


class OpenAIVisionGateway:
    def __init__(
        self, client: AsyncOpenAI, model: str,
        *, timeout_seconds: float = MODEL_TIMEOUT_SECONDS,
    ) -> None:
        self.client = client
        self.model = model
        self.timeout_seconds = timeout_seconds

    async def answer(self, question: str, jpeg: bytes | None = None, *, history: list[dict] | None = None) -> VisionResult:
        started = perf_counter()
        try:
            instructions = (VISUAL_WITH_HISTORY_INSTRUCTIONS if history else INSTRUCTIONS) if jpeg is not None else TEXT_INSTRUCTIONS
            user_content: list[dict] = [{"type": "input_text", "text": question}]
            if jpeg is not None:
                user_content.append({
                    "type": "input_image", "detail": "high",
                    "image_url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode("ascii"),
                })
            input_items: list[dict] = []
            if history:
                for item in history:
                    input_items.append({
                        "role": item["role"],
                        "content": [{"type": "input_text", "text": item["text"]}],
                    })
            input_items.append({"role": "user", "content": user_content})

            async with asyncio.timeout(self.timeout_seconds):
                response = await self.client.responses.parse(
                    model=self.model,
                    instructions=instructions,
                    input=input_items,
                    text_format=AssistantReply,
                    reasoning={"effort": "low"},
                    max_output_tokens=MAX_OUTPUT_TOKENS,
                    store=False,
                )
                if response.status != "completed" or response.error is not None:
                    raise invalid_response()
                messages = [item for item in response.output if item.type == "message"]
                if not messages or any(item.status != "completed" for item in messages):
                    raise invalid_response()
                refused = any(
                    part.type == "refusal" for item in messages for part in item.content
                )
                if refused:
                    answer = AssistantReply(
                        reply="I cannot help with that request. Try asking about a routine control or a visible message.",
                        action="stop",
                    )
                else:
                    parsed = response.output_parsed
                    if not isinstance(parsed, AssistantReply):
                        raise invalid_response()
                    answer = parsed
                usage = response.usage
                return VisionResult(
                    answer=answer,
                    model_ms=round((perf_counter() - started) * 1000),
                    input_tokens=usage.input_tokens if usage else None,
                    output_tokens=usage.output_tokens if usage else None,
                )
        except (APITimeoutError, TimeoutError):
            raise AssistantError(504, "model_timeout", "The assistant took too long to respond. Please try again.", retryable=True) from None
        except RateLimitError as exc:
            if exc.code in {"insufficient_quota", "billing_hard_limit_reached"}:
                raise AssistantError(503, "provider_quota_exhausted", "The backend's AI quota is exhausted. Check API billing before retrying.") from None
            raise AssistantError(429, "provider_rate_limited", "The assistant is busy. Wait a moment before trying again.", retryable=True) from None
        except (AuthenticationError, PermissionDeniedError, NotFoundError, BadRequestError, UnprocessableEntityError):
            raise AssistantError(503, "provider_configuration_error", "Check the backend's API key, model access and configuration before retrying.") from None
        except APIResponseValidationError:
            raise invalid_response() from None
        except (APIConnectionError, APIStatusError):
            raise AssistantError(502, "provider_unavailable", "The AI service could not be reached successfully. Please try again.", retryable=True) from None
        except APIError:
            raise invalid_response() from None
        except (ValidationError, ValueError, TypeError, AttributeError):
            raise invalid_response() from None
