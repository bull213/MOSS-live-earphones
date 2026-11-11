"""Bounded document extraction in a separate, resource-limited process."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_RESULT_BYTES = 50 * 1024 * 1024
EXTRACTION_TIMEOUT_SECONDS = 60
OCR_EXTRACTION_TIMEOUT_SECONDS = 30 * 60


class ExtractionError(ValueError):
    pass


def file_hash(path: Path) -> str:
    size = path.stat().st_size
    if size == 0 or size > MAX_FILE_BYTES:
        raise ExtractionError("Document is empty or exceeds the 64 MiB limit.")
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            total += len(block)
            if total > MAX_FILE_BYTES:
                raise ExtractionError("Document exceeds the 64 MiB limit.")
            digest.update(block)
    return digest.hexdigest()


def _run_worker(path: Path, *, mode: str, stdin: bytes | None = None, timeout: int = EXTRACTION_TIMEOUT_SECONDS) -> dict:
    try:
        result = subprocess.run(
            [sys.executable, "-m", "app.knowledge.extract_worker", str(path), mode],
            input=stdin, stdin=subprocess.DEVNULL if stdin is None else None,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=timeout, check=False, cwd=Path(__file__).resolve().parents[2],
            env={**os.environ, "PYTHONIOENCODING": "utf-8"},
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
    except subprocess.TimeoutExpired:
        raise ExtractionError("Document extraction exceeded the time limit.") from None
    if len(result.stdout) > MAX_RESULT_BYTES:
        raise ExtractionError("Extracted document exceeds the size limit.")
    try:
        payload = json.loads(result.stdout)
    except (ValueError, UnicodeDecodeError):
        raise ExtractionError("Document extraction failed or exceeded its resource limit.") from None
    if result.returncode != 0 or not payload.get("ok"):
        raise ExtractionError(payload.get("error", "Document extraction failed."))
    return payload


def inspect_pdf(path: Path) -> dict:
    path = path.resolve(strict=True)
    if path.suffix.lower() != ".pdf" or not path.is_file():
        raise ExtractionError("Choose a PDF document.")
    file_hash(path)
    return _run_worker(path, mode="--inspect", timeout=EXTRACTION_TIMEOUT_SECONDS)["inspection"]


def extract_document(path: Path, *, mobile_ocr: dict | None = None) -> dict:
    path = path.resolve(strict=True)
    supported = {".pdf", ".epub", ".txt", ".png", ".jpg", ".jpeg", ".webp", ".tif", ".tiff"}
    if not path.is_file() or path.suffix.lower() not in supported:
        raise ExtractionError("Choose a PDF, EPUB, TXT, or supported page image.")
    before = file_hash(path)
    worker_input = None
    if mobile_ocr is not None:
        worker_input = json.dumps(mobile_ocr, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(worker_input) > 16 * 1024 * 1024:
            raise ExtractionError("Phone OCR results exceed the 16 MiB limit.")
    payload = _run_worker(
        path, mode="--mobile-ocr" if mobile_ocr is not None else "--extract", stdin=worker_input,
        timeout=OCR_EXTRACTION_TIMEOUT_SECONDS if path.suffix.lower() in {".pdf", ".png", ".jpg", ".jpeg", ".webp", ".tif", ".tiff"} else EXTRACTION_TIMEOUT_SECONDS,
    )
    if file_hash(path) != before:
        raise ExtractionError("The document changed during extraction; try again.")
    document = payload["document"]
    document["source_path"] = str(path)
    document["source_hash"] = before
    identity = {
        "source_hash": before,
        "processing_fingerprint": document.get("processing_fingerprint", "native-extraction-v1"),
        "title": document.get("title"),
        "author": document.get("author"),
        "sections": document.get("sections", []),
    }
    document["content_hash"] = hashlib.sha256(
        json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return document
