"""HTTP interface for a single image-and-question turn."""

import logging
from time import perf_counter
from typing import Annotated, Any, Literal, cast
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from pydantic import AwareDatetime, TypeAdapter, ValidationError
from starlette.concurrency import run_in_threadpool

from app.assistant import PROMPT_VERSION
from app.cancellation import run_until_disconnect
from app.config import MAX_IMAGE_BYTES
from app.errors import AssistantError
from app.images import normalize_image
from app.knowledge.agent import KnowledgeAssistant
from app.notebooklm import explicit_notebook_target, sanitize_grounded_voice_summary
from app.notebooklm.client import NotebookLMAuthExpiredError
from app.openai_gateway import VisionGateway
from app.schemas import AssistantTurnResponse, ContextMessage, ErrorResponse, Question, SourceReference, Timings, TurnResponse, nlm_citations_to_sources
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


def _build_notebook_turn_response(
    request: Request,
    notebook_id: str,
    notebook_title: str,
    nlm_result: Any,
    started_at: float,
) -> AssistantTurnResponse:
    elapsed = round((perf_counter() - started_at) * 1000)
    server_total = round((perf_counter() - request.state.started_at) * 1000)
    if nlm_result is None or not getattr(nlm_result, "answer", None):
        return AssistantTurnResponse(
            request_id=request.state.request_id,
            reply=f"I couldn't get a response from {notebook_title}. Please try again.",
            action="clarify",
            timings_ms=Timings(model=elapsed, server_total=server_total),
            sources=[],
            applied_methods=[],
            retrieval_status="unused",
            notebook_title=notebook_title,
            read_aloud=True,
        )
    sources = nlm_citations_to_sources(getattr(nlm_result, "citations", []), notebook_id, notebook_title)
    clean_voice_reply = sanitize_grounded_voice_summary(nlm_result.answer, max_chars=800)
    if not clean_voice_reply:
        clean_voice_reply = nlm_result.answer[:800].strip()
    logger.info("notebooklm_turn_completed request_id=%s notebook=%s elapsed_ms=%s",
                request.state.request_id, notebook_title, elapsed)
    return AssistantTurnResponse(
        request_id=request.state.request_id,
        reply=clean_voice_reply,
        action="answer",
        timings_ms=Timings(model=elapsed, server_total=server_total),
        sources=sources,
        applied_methods=[],
        retrieval_status="notebooklm",
        notebook_title=notebook_title,
        read_aloud=True,
    )


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
    photo_id: Annotated[str | None, Form(description="Optional client UUID for the attached photograph.")] = None,
    request_id: Annotated[UUID | None, Form()] = None,
    history_json: Annotated[str | None, Form(description="Optional bounded JSON conversation history.")] = None,
    library_mode: Annotated[Literal["auto", "use", "avoid"], Form()] = "auto",
    notebook_id: Annotated[str | None, Form(description="Optional specific NotebookLM notebook ID to query directly.")] = None,
) -> AssistantTurnResponse:
    if request_id is not None:
        request.state.request_id = request_id
    jpeg = await read_normalized_image(image) if image is not None else None
    if jpeg is not None:
        cached_id = photo_id or str(uuid4())
        photo_cache = getattr(request.app.state, "photo_cache", None)
        if photo_cache is not None:
            try:
                photo_cache.store_photo(cached_id, jpeg)
            except Exception as exc:
                logger.warning("Failed to cache photo %s: %s", cached_id, exc)
    settings = request.app.state.settings
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

    nlm_service = getattr(request.app.state, "notebooklm_service", None)
    if notebook_id:
        if nlm_service is None or not getattr(nlm_service, "has_auth", lambda: False)():
            raise AssistantError(401, "notebooklm_unauthenticated", "NotebookLM authentication is missing or expired. Connect in Settings.")
        notebooks = await nlm_service.list_notebooks()
        matched_nb = next((nb for nb in notebooks if nb.get("id") == notebook_id), None)
        nb_title = matched_nb.get("title", "Notebook") if matched_nb else "Notebook"
        started = perf_counter()
        try:
            nlm_result = await run_until_disconnect(
                request,
                nlm_service.ask_chat(notebook_id, question, timeout=45.0),
            )
        except NotebookLMAuthExpiredError:
            raise AssistantError(401, "notebooklm_unauthenticated", "Google NotebookLM authentication expired. Reconnect in Settings.")
        return _build_notebook_turn_response(request, notebook_id, nb_title, nlm_result, started)

    if nlm_service is not None and getattr(nlm_service, "has_auth", lambda: False)():
        target_name, stripped_query = explicit_notebook_target(question)
        if target_name:
            matched_nb = await nlm_service.find_notebook(target_name)
            if matched_nb:
                target_query = stripped_query if stripped_query else question
                started = perf_counter()
                nlm_result = await run_until_disconnect(
                    request,
                    nlm_service.ask_chat(matched_nb["id"], target_query, timeout=45.0),
                )
                return _build_notebook_turn_response(request, matched_nb["id"], matched_nb["title"], nlm_result, started)
            elif question.strip().startswith("@") or question.strip().lower().startswith("notebook:"):
                server_total = round((perf_counter() - request.state.started_at) * 1000)
                return AssistantTurnResponse(
                    request_id=request.state.request_id,
                    reply=f"I couldn't find a notebook named '{target_name}' in your NotebookLM account. Please check the notebook name or ask to list your notebooks.",
                    action="clarify",
                    timings_ms=Timings(model=0, server_total=server_total),
                    sources=[],
                    applied_methods=[],
                    retrieval_status="unused",
                    notebook_title=None,
                    read_aloud=True,
                )

    if gateway is None:
        raise AssistantError(503, "ai_not_configured", "Configure the selected AI provider in backend/.env and restart the backend.")

    if settings.assistant_provider == "stepfun":
        assistant = KnowledgeAssistant(
            cast(StepFunGateway, gateway),
            request.app.state.knowledge_index,
            settings,
            notebooklm_service=getattr(request.app.state, "notebooklm_service", None),
            photo_cache=getattr(request.app.state, "photo_cache", None),
        )
        outcome = await run_until_disconnect(request, assistant.answer(question, jpeg, history, library_mode))
        result = outcome.result
    else:
        if library_mode == "use":
            raise AssistantError(503, "provider_feature_unavailable", "Configure StepFun to use the knowledge library.")
        outcome = None
        result = await run_until_disconnect(request, gateway.answer(question, jpeg, history=history))
    elapsed = round((perf_counter() - request.state.started_at) * 1000)
    logger.info("assistant_turn_completed request_id=%s provider=%s model=%s model_ms=%s server_total_ms=%s input_tokens=%s output_tokens=%s",
                request.state.request_id, settings.assistant_provider, settings.active_model,
                result.model_ms, elapsed, result.input_tokens, result.output_tokens)
    return AssistantTurnResponse(
        request_id=request.state.request_id, reply=result.answer.reply, action=result.answer.action,
        timings_ms=Timings(model=result.model_ms, server_total=elapsed),
        sources=outcome.sources if outcome else [], applied_methods=outcome.methods if outcome else [],
        retrieval_status=outcome.retrieval_status if outcome else "unused",
        notebook_title=outcome.notebook_title if outcome else None,
        read_aloud=outcome.read_aloud if outcome else False,
    )
