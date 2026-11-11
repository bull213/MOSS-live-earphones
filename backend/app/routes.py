"""HTTP interface for a single image-and-question turn."""

import logging
from time import perf_counter
from typing import Annotated, Literal, cast
from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from pydantic import AwareDatetime, TypeAdapter, ValidationError
from starlette.concurrency import run_in_threadpool

from app.assistant import PROMPT_VERSION
from app.cancellation import run_until_disconnect
from app.config import MAX_IMAGE_BYTES
from app.errors import AssistantError
from app.images import normalize_image
from app.openai_gateway import VisionGateway
from app.knowledge.agent import KnowledgeAssistant
from app.schemas import AssistantTurnResponse, ContextMessage, ErrorResponse, Question, Timings, TurnResponse
from app.stepfun_gateway import StepFunGateway


logger = logging.getLogger("uvicorn.error.visual_assistant")
router = APIRouter()


def get_gateway(request: Request) -> VisionGateway | None:
    return cast(VisionGateway | None, request.app.state.vision_gateway)


async def read_normalized_image(image: UploadFile) -> bytes:
    try:
        data = await image.read(MAX_IMAGE_BYTES + 1)
        return await run_in_threadpool(normalize_image, data, image.content_type)
    finally:
        await image.close()


@router.post(
    "/v1/turns", response_model=TurnResponse, tags=["assistant"],
    summary="Answer a question about one photograph",
    responses={code: {"model": ErrorResponse} for code in (400, 413, 415, 422, 429, 500, 502, 503, 504)},
)
async def create_turn(
    request: Request,
    question: Annotated[Question, Form(description="A question of 1 to 1,000 characters.")],
    image: Annotated[UploadFile, File(description="One JPEG or PNG, at most 4 MiB and 20 megapixels.")],
    gateway: Annotated[VisionGateway | None, Depends(get_gateway)],
    request_id: Annotated[UUID | None, Form(description="Optional client request identifier.")] = None,
    photo_id: Annotated[UUID | None, Form(description="Optional photograph identifier.")] = None,
    captured_at: Annotated[AwareDatetime | None, Form(description="Optional capture time, including timezone.")] = None,
) -> TurnResponse:
    if request_id is not None:
        request.state.request_id = request_id
    jpeg = await read_normalized_image(image)

    if gateway is None:
        raise AssistantError(
            503, "ai_not_configured",
            "Configure the selected AI provider in backend/.env and restart the backend to enable image answers.",
        )
    result = await gateway.answer(question, jpeg)
    elapsed = round((perf_counter() - request.state.started_at) * 1000)
    logger.info(
        "turn_completed request_id=%s photo_id=%s model=%s prompt_version=%s "
        "model_ms=%s server_total_ms=%s input_tokens=%s output_tokens=%s",
        request.state.request_id, photo_id, request.app.state.settings.active_model,
        PROMPT_VERSION, result.model_ms, elapsed, result.input_tokens, result.output_tokens,
    )
    return TurnResponse(
        request_id=request.state.request_id,
        reply=result.answer.reply, action=result.answer.action,
        timings_ms=Timings(model=result.model_ms, server_total=elapsed),
    )


@router.post(
    "/v1/assistant/turns", response_model=AssistantTurnResponse, tags=["assistant"],
    summary="Answer a text question with an optional photograph",
    responses={code: {"model": ErrorResponse} for code in (400, 413, 415, 422, 429, 500, 502, 503, 504)},
)
async def create_assistant_turn(
    request: Request,
    question: Annotated[Question, Form(description="A question of 1 to 1,000 characters.")],
    gateway: Annotated[VisionGateway | None, Depends(get_gateway)],
    image: Annotated[UploadFile | None, File(description="Optional JPEG or PNG photograph.")] = None,
    request_id: Annotated[UUID | None, Form()] = None,
    history_json: Annotated[str | None, Form(description="Optional bounded JSON conversation history.")] = None,
    library_mode: Annotated[Literal["auto", "use", "avoid"], Form()] = "auto",
) -> AssistantTurnResponse:
    if request_id is not None:
        request.state.request_id = request_id
    jpeg = await read_normalized_image(image) if image is not None else None
    settings = request.app.state.settings
    if gateway is None:
        raise AssistantError(503, "ai_not_configured", "Configure the selected AI provider in backend/.env and restart the backend.")
    history: list[dict] = []
    if history_json is not None:
        if len(history_json.encode("utf-8")) > 16_000:
            raise AssistantError(422, "invalid_context", "Conversation context exceeds the allowed size.")
        try:
            parsed = TypeAdapter(list[ContextMessage]).validate_json(history_json)
            if len(parsed) > 8 or sum(len(item.text) for item in parsed) > 4000:
                raise ValueError("Context is too large")
            history = [item.model_dump() for item in parsed]
        except (ValidationError, ValueError):
            raise AssistantError(422, "invalid_context", "Conversation context is invalid or too large.") from None
    if jpeg is None and settings.assistant_provider != "stepfun":
        raise AssistantError(503, "text_provider_unavailable", "Configure the StepFun provider to answer without a photograph.")
    if settings.assistant_provider == "stepfun":
        assistant = KnowledgeAssistant(cast(StepFunGateway, gateway), request.app.state.knowledge_index, settings)
        outcome = await run_until_disconnect(request, assistant.answer(question, jpeg, history, library_mode))
        result = outcome.result
    else:
        if history or library_mode == "use":
            raise AssistantError(503, "provider_feature_unavailable", "Configure StepFun to use conversation context or the knowledge library.")
        outcome = None
        result = await run_until_disconnect(request, gateway.answer(question, jpeg))  # type: ignore[arg-type]
    elapsed = round((perf_counter() - request.state.started_at) * 1000)
    logger.info("assistant_turn_completed request_id=%s provider=%s model=%s model_ms=%s server_total_ms=%s input_tokens=%s output_tokens=%s",
                request.state.request_id, settings.assistant_provider, settings.active_model,
                result.model_ms, elapsed, result.input_tokens, result.output_tokens)
    return AssistantTurnResponse(request_id=request.state.request_id, reply=result.answer.reply, action=result.answer.action,
        timings_ms=Timings(model=result.model_ms, server_total=elapsed),
        sources=outcome.sources if outcome else [], applied_methods=outcome.methods if outcome else [],
        retrieval_status=outcome.retrieval_status if outcome else "unused")
