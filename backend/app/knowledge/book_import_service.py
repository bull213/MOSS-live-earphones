"""Staging and indexing for PDF text recognized by the Android app."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
from threading import Lock
from time import time
from typing import AsyncIterator
from urllib.parse import unquote_plus
from uuid import UUID, uuid4

from starlette.concurrency import run_in_threadpool

from app.errors import AssistantError
from app.knowledge.extraction import ExtractionError, file_hash, inspect_pdf
from app.knowledge.index import KnowledgeIndex


MAX_UPLOAD_BYTES = 64 * 1024 * 1024
MAX_OCR_JSON_BYTES = 16 * 1024 * 1024
MAX_STAGED_BYTES = 256 * 1024 * 1024
MAX_STAGED_JOBS = 4
STAGING_TTL_SECONDS = 60 * 60
MAX_PAGES = 3000
MAX_TEXT_CHARS = 12_000_000
MAX_PAGE_TEXT_CHARS = 250_000


class PhoneBookImportService:
    def __init__(self, data_dir: Path, index: KnowledgeIndex, lock: Lock) -> None:
        self.staging_dir = data_dir / "staging"
        self.source_dir = data_dir / "sources"
        self.index = index
        self.lock = lock

    def cleanup_expired(self) -> None:
        self.staging_dir.mkdir(parents=True, exist_ok=True)
        cutoff = time() - STAGING_TTL_SECONDS
        for path in self.staging_dir.iterdir():
            try:
                if path.is_file() and path.stat().st_mtime < cutoff:
                    path.unlink(missing_ok=True)
            except OSError:
                continue

    async def inspect_upload(
        self,
        chunks: AsyncIterator[bytes],
        *,
        content_length: str | None,
        document_name: str,
    ) -> dict:
        if content_length is not None:
            if (not content_length.isascii() or not content_length.isdecimal()
                    or len(content_length) > len(str(MAX_UPLOAD_BYTES)) or int(content_length) > MAX_UPLOAD_BYTES):
                raise AssistantError(413, "upload_too_large", "The PDF exceeds the 64 MiB limit.")

        self.cleanup_expired()
        job_id = uuid4()
        temporary = self.staging_dir / f"{job_id}.upload"
        pdf_path = self.staging_dir / f"{job_id}.pdf"
        metadata_path = self.staging_dir / f"{job_id}.json"
        staged = False
        total = 0
        try:
            with self.lock:
                active = list(self.staging_dir.glob("*.pdf"))
                staged_size = sum(path.stat().st_size for path in active)
                if len(active) >= MAX_STAGED_JOBS or staged_size >= MAX_STAGED_BYTES:
                    raise AssistantError(429, "import_capacity", "The local import queue is full. Complete an import or wait for a staged upload to expire.", retryable=True)

            with temporary.open("wb") as destination:
                async for chunk in chunks:
                    total += len(chunk)
                    if total > MAX_UPLOAD_BYTES:
                        raise AssistantError(413, "upload_too_large", "The PDF exceeds the 64 MiB limit.")
                    destination.write(chunk)
                destination.flush()
                os.fsync(destination.fileno())

            if total < len(b"%PDF-"):
                raise AssistantError(422, "invalid_document", "The uploaded file is not a readable PDF.")
            with temporary.open("rb") as source:
                if source.read(5) != b"%PDF-":
                    raise AssistantError(415, "invalid_document", "The selected file is not a PDF.")
            os.replace(temporary, pdf_path)

            digest = await run_in_threadpool(file_hash, pdf_path)
            inspection = await run_in_threadpool(inspect_pdf, pdf_path)
            display_name = self._clean_name(unquote_plus(document_name))
            title = inspection["title"] if inspection.get("has_metadata_title") else Path(display_name).stem
            metadata = {
                "created_at": time(), "file_hash": digest, "display_name": display_name,
                "title": title[:300], "page_count": inspection["page_count"],
                "pages_requiring_ocr": inspection["pages_requiring_ocr"], "state": "staged",
            }
            self._write_metadata(metadata_path, metadata)
            staged = True
            return {"job_id": str(job_id), "file_hash": digest, "title": metadata["title"],
                    "page_count": metadata["page_count"], "pages_requiring_ocr": metadata["pages_requiring_ocr"],
                    "expires_in_seconds": STAGING_TTL_SECONDS}
        except AssistantError:
            raise
        except (ExtractionError, OSError, ValueError, KeyError):
            raise AssistantError(422, "invalid_document", "The PDF could not be inspected. Check that it is valid and unencrypted.") from None
        finally:
            if not staged:
                temporary.unlink(missing_ok=True)
                pdf_path.unlink(missing_ok=True)
                metadata_path.unlink(missing_ok=True)

    def commit(self, payload: object) -> dict:
        job_id, page_texts, fingerprint = self._parse_payload(payload)
        source_path, metadata_path, metadata = self._read_job(job_id)
        if metadata.get("state") == "completed":
            if metadata.get("ocr_fingerprint") == fingerprint:
                return metadata["result"]
            raise AssistantError(409, "import_already_completed", "This document was already imported with different OCR results.")

        expected_pages = {int(page) for page in metadata["pages_requiring_ocr"]}
        if set(page_texts) != expected_pages:
            raise AssistantError(422, "incomplete_ocr_results", "Phone OCR must return exactly the pages reported by the inspection step.")
        try:
            staged_hash = file_hash(source_path)
        except ExtractionError:
            staged_hash = ""
        if staged_hash != metadata["file_hash"]:
            raise AssistantError(409, "staged_document_changed", "The staged PDF changed. Select it again to restart the import.")

        original_path = self._preserve_original(source_path, metadata["file_hash"], job_id)
        mobile_ocr = {"page_texts": {str(page): text for page, text in page_texts.items()}, "title": metadata["title"]}
        try:
            with self.lock:
                result = self.index.add(original_path, mobile_ocr=mobile_ocr)
        except (ExtractionError, ValueError) as exc:
            raise AssistantError(422, "document_ingestion_failed", str(exc)) from None
        except (RuntimeError, OSError):
            raise AssistantError(503, "knowledge_unavailable", "The local book library could not finish indexing. Select the PDF again to retry.", retryable=True) from None

        response = {"document_id": result["document_id"], "status": result["status"],
                    "title": metadata["title"], "page_count": metadata["page_count"],
                    "passages": result.get("passages", 0), "warnings": result.get("warnings", [])}
        metadata.update({"state": "completed", "completed_at": time(),
                         "ocr_fingerprint": fingerprint, "result": response})
        self._write_metadata(metadata_path, metadata)
        return response

    def _preserve_original(self, staged_pdf: Path, digest: str, job_id: UUID) -> Path:
        self.source_dir.mkdir(parents=True, exist_ok=True)
        destination = self.source_dir / f"{digest}.pdf"
        if not destination.exists():
            temporary = self.source_dir / f".{digest}.{job_id}.tmp"
            try:
                shutil.copyfile(staged_pdf, temporary)
                os.replace(temporary, destination)
            finally:
                temporary.unlink(missing_ok=True)
        return destination

    def _read_job(self, job_id: UUID) -> tuple[Path, Path, dict]:
        pdf_path = self.staging_dir / f"{job_id}.pdf"
        metadata_path = self.staging_dir / f"{job_id}.json"
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, ValueError, UnicodeDecodeError):
            raise AssistantError(404, "import_expired", "This document import expired. Select the PDF again.") from None
        if time() - float(metadata.get("created_at", 0)) > STAGING_TTL_SECONDS or not pdf_path.is_file():
            raise AssistantError(404, "import_expired", "This document import expired. Select the PDF again.")
        return pdf_path, metadata_path, metadata

    @staticmethod
    def _parse_payload(payload: object) -> tuple[UUID, dict[int, str], str]:
        try:
            if not isinstance(payload, dict):
                raise ValueError
            raw_job_id = payload["job_id"]
            if not isinstance(raw_job_id, str):
                raise ValueError
            job_id = UUID(raw_job_id)
            raw_pages = payload["page_texts"]
            if not isinstance(raw_pages, dict) or len(raw_pages) > MAX_PAGES:
                raise ValueError
            page_texts = {int(page): text for page, text in raw_pages.items()
                          if isinstance(page, str) and page.isdecimal() and isinstance(text, str)}
            if len(page_texts) != len(raw_pages) or any(
                page < 1 or len(text) > MAX_PAGE_TEXT_CHARS for page, text in page_texts.items()
            ) or sum(len(text) for text in page_texts.values()) > MAX_TEXT_CHARS:
                raise ValueError
            fingerprint = hashlib.sha256(json.dumps(page_texts, ensure_ascii=False, sort_keys=True,
                separators=(",", ":")).encode("utf-8")).hexdigest()
            return job_id, page_texts, fingerprint
        except (ValueError, TypeError, KeyError, UnicodeEncodeError):
            raise AssistantError(422, "invalid_ocr_results", "The page OCR results are invalid or exceed their limits.") from None

    @staticmethod
    def _clean_name(name: str) -> str:
        name = Path(name.replace("\\", "/")).name
        name = re.sub(r"[\x00-\x1f\x7f]", "", name).strip().strip(".")[:180]
        return name if name.lower().endswith(".pdf") else (name or "book") + ".pdf"

    @staticmethod
    def _write_metadata(path: Path, value: dict) -> None:
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True), encoding="utf-8")
        os.replace(temporary, path)
