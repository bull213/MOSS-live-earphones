"""Loopback HTTP adapter for phone-assisted PDF ingestion."""

from __future__ import annotations

import json

from fastapi import APIRouter, Request
from starlette.concurrency import run_in_threadpool

from app.errors import AssistantError
from app.knowledge.book_import_service import (
    MAX_OCR_JSON_BYTES,
    MAX_UPLOAD_BYTES,
    PhoneBookImportService,
)
from app.security import require_loopback_or_private_subnet


router = APIRouter(prefix="/v1/knowledge", tags=["knowledge ingestion"])


def _service(request: Request) -> PhoneBookImportService:
    service = request.app.state.phone_book_import_service
    if service is None:
        raise AssistantError(503, "knowledge_unavailable", "The local book library is not available right now.", retryable=True)
    return service


def _require_loopback(request: Request) -> None:
    require_loopback_or_private_subnet(request, "Book imports are available only through local or private network connections.")


def _check_content_length(request: Request, limit: int, code: str, message: str) -> None:
    declared = request.headers.get("content-length")
    if declared is not None and (
        not declared.isascii() or not declared.isdecimal()
        or len(declared) > len(str(limit)) or int(declared) > limit
    ):
        raise AssistantError(413, code, message)


async def _read_bounded_body(request: Request, limit: int) -> bytes:
    body = bytearray()
    async for chunk in request.stream():
        if len(body) + len(chunk) > limit:
            raise AssistantError(413, "ocr_results_too_large", "The OCR results exceed the 16 MiB limit.")
        body.extend(chunk)
    return bytes(body)


@router.post("/inspect", summary="Inspect a local PDF and identify pages that need phone OCR")
async def inspect_import(request: Request) -> dict:
    _require_loopback(request)
    if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/pdf":
        raise AssistantError(415, "unsupported_media_type", "Send a PDF document as application/pdf.")
    _check_content_length(request, MAX_UPLOAD_BYTES, "upload_too_large", "The PDF exceeds the 64 MiB limit.")
    return await _service(request).inspect_upload(
        request.stream(), content_length=request.headers.get("content-length"),
        document_name=request.headers.get("x-document-name", "book.pdf"),
    )


@router.post("/import", summary="Index a staged PDF with page text recognized on the phone")
async def commit_import(request: Request) -> dict:
    _require_loopback(request)
    if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
        raise AssistantError(415, "unsupported_media_type", "Send the page OCR results as JSON.")
    _check_content_length(request, MAX_OCR_JSON_BYTES, "ocr_results_too_large", "The OCR results exceed the 16 MiB limit.")
    try:
        payload = json.loads(await _read_bounded_body(request, MAX_OCR_JSON_BYTES))
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise AssistantError(422, "invalid_ocr_results", "The page OCR results are invalid or exceed their limits.") from None
    return await run_in_threadpool(_service(request).commit, payload)
