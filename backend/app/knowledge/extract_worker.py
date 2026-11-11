"""Private extraction worker. It writes only a bounded JSON result to stdout."""

from __future__ import annotations

import json
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
import posixpath
import re
import sys
import hashlib
import os
from urllib.parse import unquote, urlsplit
from zipfile import ZipFile


MAX_PAGES = 3000
MAX_SECTIONS = 5000
MAX_CHARS = 12_000_000
MAX_ZIP_FILES = 5000
MAX_ZIP_UNCOMPRESSED = 256 * 1024 * 1024
MAX_ZIP_MEMBER = 16 * 1024 * 1024
MEMORY_LIMIT = 768 * 1024 * 1024
OCR_MEMORY_LIMIT = 3 * 1024 * 1024 * 1024
_job_handle = None


class DocumentError(ValueError):
    pass


def limit_memory(memory_limit: int = MEMORY_LIMIT, cpu_seconds: int = 55) -> None:
    if sys.platform != "win32":
        import resource
        resource.setrlimit(resource.RLIMIT_AS, (memory_limit, memory_limit))
        resource.setrlimit(resource.RLIMIT_CPU, (cpu_seconds, cpu_seconds))
        return
    import ctypes
    from ctypes import wintypes

    class BasicLimits(ctypes.Structure):
        _fields_ = [("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64),
                    ("flags", wintypes.DWORD), ("min_work", ctypes.c_size_t),
                    ("max_work", ctypes.c_size_t), ("process_limit", wintypes.DWORD),
                    ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD),
                    ("scheduling", wintypes.DWORD)]

    class IoCounters(ctypes.Structure):
        _fields_ = [(name, ctypes.c_uint64) for name in ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]

    class ExtendedLimits(ctypes.Structure):
        _fields_ = [("basic", BasicLimits), ("io", IoCounters), ("process_memory", ctypes.c_size_t),
                    ("job_memory", ctypes.c_size_t), ("peak_process", ctypes.c_size_t), ("peak_job", ctypes.c_size_t)]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    kernel.CreateJobObjectW.restype = wintypes.HANDLE
    kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    kernel.SetInformationJobObject.restype = wintypes.BOOL
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    kernel.AssignProcessToJobObject.restype = wintypes.BOOL
    job = kernel.CreateJobObjectW(None, None)
    limits = ExtendedLimits()
    limits.basic.flags = 0x100  # JOB_OBJECT_LIMIT_PROCESS_MEMORY
    limits.process_memory = memory_limit
    if not job or not kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)) or not kernel.AssignProcessToJobObject(job, kernel.GetCurrentProcess()):
        raise DocumentError("Document extraction could not establish its memory limit.")
    global _job_handle
    _job_handle = job


def document(title: str, author: str | None, sections: list[dict], warnings: list[str] | None = None,
             processing_fingerprint: str = "native-extraction-v1", extraction_metadata: dict | None = None) -> dict:
    if len(sections) > MAX_SECTIONS or sum(len(s["text"]) for s in sections) > MAX_CHARS:
        raise DocumentError("The extracted document exceeds the text or section limit.")
    sections = [section for section in sections if section["text"].strip()]
    if not sections:
        raise DocumentError("No readable text was found after extraction and OCR.")
    return {"title": title, "author": author, "sections": sections, "warnings": warnings or [],
            "processing_fingerprint": processing_fingerprint,
            "extraction_metadata": extraction_metadata or {}}


def read_txt(path: Path) -> dict:
    try:
        text = path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError:
        raise DocumentError("TXT documents must be UTF-8 encoded.") from None
    if len(text) > MAX_CHARS:
        raise DocumentError("The extracted document exceeds the text limit.")
    sections: list[dict] = []
    heading = None
    lines: list[str] = []
    def flush() -> None:
        body = "\n".join(lines).strip()
        if body:
            sections.append({"chapter": heading, "section": heading, "page": None, "text": body})
        lines.clear()
    for line in text.splitlines():
        if re.match(r"^\s*(?:#{1,3}\s+|chapter\s+\d+\b)", line, re.IGNORECASE) and len(line) <= 160:
            flush()
            heading = line.strip().lstrip("#").strip()
        lines.append(line)
    flush()
    return document(path.stem, None, sections)


def _native_text_quality(text: str, page_area: float) -> tuple[bool, str | None]:
    stripped = text.strip()
    if not stripped:
        return False, "no_text"
    visible = sum(ch.isprintable() for ch in stripped)
    alphanumeric = sum(ch.isalnum() for ch in stripped)
    replacement = stripped.count("\ufffd")
    if replacement / max(1, len(stripped)) > 0.01 or visible / max(1, len(stripped)) < 0.90:
        return False, "corrupt_text"
    # Very sparse hidden text layers often consist of a few OCR fragments on a
    # full-page scan. Keep short but plausible text (e.g. a title or footnote).
    expected = max(24, min(100, int(page_area / 5000)))
    if len(stripped) < expected or alphanumeric / max(1, len(stripped)) < 0.45:
        return False, "sparse_text"
    return True, None


def _readable_ocr_text(text: str) -> str:
    text = text.replace("\x00", "")
    return "\n".join(line.rstrip() for line in text.splitlines()).strip()


def _ocr_pages(images: list[tuple[int, Path]]) -> tuple[dict[int, dict], dict, str]:
    from app.knowledge.ocr import get_ocr_provider
    try:
        provider = get_ocr_provider()
    except RuntimeError as exc:
        raise DocumentError(str(exc)) from None
    results: dict[int, dict] = {}
    try:
        recognized = []
        for start in range(0, len(images), 8):
            recognized.extend(provider.recognize_many([item[1] for item in images[start:start + 8]]))
    except RuntimeError as exc:
        raise DocumentError(str(exc)) from None
    if len(recognized) != len(images):
        raise DocumentError("OCR returned a different number of pages than requested.")
    for (page_number, image_path), result in zip(images, recognized):
        text = _readable_ocr_text(result.text)
        if not text:
            result_warning = "OCR returned no readable text."
        else:
            result_warning = None
        results[page_number] = {"text": text, "warning": result_warning,
                                "provenance": {"source": "ocr", "provider": result.provider,
                                               "model_version": result.model_version,
                                               "diagnostics": result.diagnostics}}
    fingerprint = hashlib.sha256(json.dumps({"provider": provider.name,
        "version": provider.version, "dpi": 220, "preprocessing": "orientation-v1"},
        sort_keys=True).encode()).hexdigest()
    metadata = {"provider": provider.name, "model_version": provider.version,
                "pages_processed": len(images), "render_dpi": 220}
    return results, metadata, f"ocr-v1:{fingerprint}"


def inspect_pdf(path: Path) -> dict:
    from pypdf import PdfReader
    reader = PdfReader(str(path), strict=False)
    if reader.is_encrypted:
        raise DocumentError("Encrypted PDFs are not supported.")
    if len(reader.pages) > MAX_PAGES:
        raise DocumentError("The PDF exceeds the page limit.")
    info = reader.metadata
    title = str(info.title).strip() if info and info.title else path.stem
    author = str(info.author).strip() if info and info.author else None
    needs_ocr = []
    for number, page in enumerate(reader.pages):
        text = page.extract_text() or ""
        area = float(page.mediabox.width) * float(page.mediabox.height)
        good, _ = _native_text_quality(text, area)
        if not good:
            needs_ocr.append(number + 1)
    return {"title": title, "has_metadata_title": bool(info and info.title), "author": author, "page_count": len(reader.pages),
            "pages_requiring_ocr": needs_ocr}


def read_pdf(path: Path, mobile_ocr: dict[int, str] | None = None) -> dict:
    from pypdf import PdfReader
    reader = PdfReader(str(path), strict=False)
    if reader.is_encrypted:
        raise DocumentError("Encrypted PDFs are not supported.")
    if len(reader.pages) > MAX_PAGES:
        raise DocumentError("The PDF exceeds the page limit.")
    info = reader.metadata
    title = str(info.title).strip() if info and info.title else path.stem
    author = str(info.author).strip() if info and info.author else None
    headings: list[tuple[int, str]] = []
    def walk(items: list) -> None:
        for item in items:
            if isinstance(item, list):
                walk(item)
            else:
                try:
                    page = reader.get_destination_page_number(item)
                    label = str(item.title).strip()
                    if page >= 0 and label:
                        headings.append((page, label))
                except (AttributeError, TypeError, ValueError, KeyError):
                    continue
    walk(reader.outline)
    headings.sort(key=lambda item: item[0])
    sections = []
    total = 0
    needs_ocr: list[tuple[int, Path]] = []
    native_pages: dict[int, tuple[str, str | None, bool]] = {}
    temp_dir: Path | None = None
    try:
        import fitz
    except ImportError:
        fitz = None
    for number, page in enumerate(reader.pages):
        text = page.extract_text() or ""
        area = float(page.mediabox.width) * float(page.mediabox.height)
        good, reason = _native_text_quality(text, area)
        native_pages[number] = (text, reason, good)
    suspect = [number for number, (_, _, good) in native_pages.items() if not good]
    if suspect:
        if mobile_ocr is not None:
            if set(mobile_ocr) != {number + 1 for number in suspect}:
                raise DocumentError("Phone OCR results do not match the pages that need OCR.")
            ocr_results = {}
            for page_number, raw_text in mobile_ocr.items():
                text = _readable_ocr_text(raw_text)
                if len(text) > 250_000:
                    raise DocumentError(f"Phone OCR output for page {page_number} exceeds the page text limit.")
                warning = "OCR returned no readable text." if not text else None
                if text and sum(ch.isprintable() for ch in text) / len(text) < 0.90:
                    warning = "OCR output contains suspicious non-printable characters."
                if text and len(text) >= 40 and sum(ch.isalnum() for ch in text) / len(text) < 0.25:
                    warning = "OCR output has very little readable alphanumeric text."
                ocr_results[page_number - 1] = {
                    "text": text, "warning": warning,
                    "provenance": {"source": "ocr", "provider": "google_mlkit",
                                   "model_version": "text-recognition-16.0.1",
                                   "diagnostics": {"processing_location": "android-device"}},
                }
            fingerprint = hashlib.sha256(b"google_mlkit:text-recognition-16.0.1:latin:page-v1").hexdigest()
            ocr_metadata = {"provider": "google_mlkit", "model_version": "text-recognition-16.0.1",
                            "processing_location": "android-device", "pages_processed": len(mobile_ocr)}
            processing_fingerprint = f"mobile-ocr-v1:{fingerprint}"
        else:
            from app.knowledge.ocr import ocr_provider_available
            if not ocr_provider_available():
                reasons = sorted({native_pages[number][1] for number in suspect})
                raise DocumentError("OCR is required for page(s) " + ", ".join(str(n + 1) for n in suspect[:20]) +
                    (" and more" if len(suspect) > 20 else "") + ". Install a local OCR provider (see backend README). " +
                    "Detection: " + ", ".join(reasons) + ".")
            if fitz is None:
                raise DocumentError("OCR needs PyMuPDF to render PDF pages; install the optional OCR requirements.")
            import tempfile
            temp_dir = Path(tempfile.mkdtemp(prefix="visual-assistant-ocr-"))
            images = []
            try:
                pdf = fitz.open(path)
                for number in suspect:
                    pdf_page = pdf[number]
                    scale = 220 / 72
                    rect = pdf_page.rect
                    pixels = int(rect.width * scale) * int(rect.height * scale)
                    if pixels > 25_000_000:
                        scale *= (25_000_000 / pixels) ** 0.5
                    pixmap = pdf_page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
                    image_path = temp_dir / f"page-{number + 1:05}.jpg"
                    image_path.write_bytes(pixmap.tobytes("jpeg", jpg_quality=90))
                    if image_path.stat().st_size > 12 * 1024 * 1024:
                        raise DocumentError(f"Rendered page {number + 1} exceeds the 12 MiB OCR input limit.")
                    images.append((number, image_path))
                pdf.close()
                ocr_results, ocr_metadata, processing_fingerprint = _ocr_pages(images)
            finally:
                import shutil
                shutil.rmtree(temp_dir, ignore_errors=True)
    else:
        if mobile_ocr:
            raise DocumentError("Phone OCR results were supplied for a PDF that does not need OCR.")
        ocr_results, ocr_metadata, processing_fingerprint = {}, {}, "native-pdf-text-v1"
    warnings = []
    duplicate_pages: dict[str, int] = {}
    for page_number, result in ocr_results.items():
        normalized = " ".join(result["text"].casefold().split())
        if len(normalized) < 80:
            continue
        digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
        if digest in duplicate_pages:
            warnings.append(f"Page {page_number + 1}: OCR text duplicates page {duplicate_pages[digest]}; review the source scan.")
        else:
            duplicate_pages[digest] = page_number + 1
    for number, page in enumerate(reader.pages):
        native_text, reason, good = native_pages[number]
        if good:
            text = native_text
            provenance = {"source": "native"}
        else:
            ocr = ocr_results[number]
            text = ocr["text"]
            provenance = ocr["provenance"]
            if reason != "no_text":
                warnings.append(f"Page {number + 1}: embedded text was {reason.replace('_', ' ')}; OCR used.")
            if ocr["warning"]:
                warnings.append(f"Page {number + 1}: {ocr['warning']}")
        total += len(text)
        if total > MAX_CHARS:
            raise DocumentError("The extracted document exceeds the text limit.")
        chapter = next((label for start, label in reversed(headings) if start <= number), None)
        sections.append({"chapter": chapter, "section": None, "page": number + 1,
                         "text": text, "provenance": provenance})
    return document(title, author, sections, warnings, processing_fingerprint, ocr_metadata)


def read_image(path: Path) -> dict:
    from app.knowledge.ocr import get_ocr_provider
    from PIL import Image, ImageOps
    Image.MAX_IMAGE_PIXELS = 25_000_000
    try:
        with Image.open(path) as source:
            source.verify()
        with Image.open(path) as source:
            image = ImageOps.exif_transpose(source).convert("RGB")
            if image.width * image.height > 25_000_000:
                raise DocumentError("The image exceeds the 25 megapixel limit.")
            import tempfile
            temp_dir = Path(tempfile.mkdtemp(prefix="visual-assistant-ocr-"))
            normalized = temp_dir / "page.jpg"
            image.save(normalized, format="JPEG", quality=92, optimize=True)
            if normalized.stat().st_size > 12 * 1024 * 1024:
                raise DocumentError("The normalized image exceeds the 12 MiB OCR input limit.")
    except DocumentError:
        raise
    except Exception:
        raise DocumentError("The page image is invalid or exceeds the image limit.") from None
    try:
        try:
            provider = get_ocr_provider()
            result = provider.recognize(normalized)
        except RuntimeError as exc:
            raise DocumentError(str(exc)) from None
    finally:
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)
    text = _readable_ocr_text(result.text)
    warnings = [] if text else ["OCR returned no readable text."]
    section = {"chapter": None, "section": None, "page": 1, "text": text,
               "provenance": {"source": "ocr", "provider": result.provider,
                              "model_version": result.model_version,
                              "diagnostics": result.diagnostics}}
    fingerprint = hashlib.sha256(f"{result.provider}:{result.model_version}:image-v1".encode()).hexdigest()
    return document(path.stem, None, [section], warnings, f"ocr-image-v1:{fingerprint}",
                    {"provider": result.provider, "model_version": result.model_version, "pages_processed": 1})


class TextHtmlParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.heading: list[str] = []
        self.in_heading = False
        self.ignored = 0

    def handle_starttag(self, tag: str, attrs: list) -> None:
        if tag in {"script", "style", "noscript"}:
            self.ignored += 1
        if not self.ignored and tag in {"h1", "h2"} and not self.heading:
            self.in_heading = True
        if not self.ignored and tag in {"p", "div", "br", "li", "h1", "h2", "h3"}:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "noscript"} and self.ignored:
            self.ignored -= 1
        if tag in {"h1", "h2"}:
            self.in_heading = False
        if not self.ignored and tag in {"p", "div", "li", "h1", "h2", "h3"}:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self.ignored:
            self.parts.append(data)
            if self.in_heading:
                self.heading.append(data)


def safe_member(base: str, name: str) -> str:
    url = urlsplit(name)
    if url.scheme or url.netloc:
        raise DocumentError("External EPUB references are not supported.")
    relative = unquote(url.path)
    if "\\" in relative or relative.startswith("/"):
        raise DocumentError("Invalid EPUB path.")
    result = posixpath.normpath(posixpath.join(base, relative))
    if result in {".", ".."} or result.startswith("../"):
        raise DocumentError("Invalid EPUB path.")
    return str(PurePosixPath(result))


def read_epub(path: Path) -> dict:
    from defusedxml import ElementTree as ET
    with ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ZIP_FILES or sum(i.file_size for i in infos) > MAX_ZIP_UNCOMPRESSED:
            raise DocumentError("The EPUB exceeds the archive limits.")
        if any(i.file_size > MAX_ZIP_MEMBER or (i.file_size > 1024 * 1024 and i.file_size > max(1, i.compress_size) * 200) for i in infos):
            raise DocumentError("The EPUB contains an oversized or highly compressed entry.")
        if len({i.filename for i in infos}) != len(infos):
            raise DocumentError("The EPUB contains duplicate archive entries.")
        container = ET.fromstring(archive.read("META-INF/container.xml"))
        rootfile = next((node.attrib.get("full-path") for node in container.iter() if node.tag.endswith("}rootfile")), None)
        if not rootfile:
            raise DocumentError("The EPUB package is missing.")
        package_path = safe_member("", rootfile)
        package = ET.fromstring(archive.read(package_path))
        title = next((node.text.strip() for node in package.iter() if node.tag.endswith("}title") and node.text and node.text.strip()), path.stem)
        author = next((node.text.strip() for node in package.iter() if node.tag.endswith("}creator") and node.text and node.text.strip()), None)
        manifest = {node.attrib.get("id"): node.attrib.get("href") for node in package.iter()
                    if node.tag.endswith("}item") and node.attrib.get("media-type") in {"application/xhtml+xml", "text/html"}}
        sections = []
        total = 0
        for node in package.iter():
            if not node.tag.endswith("}itemref"):
                continue
            href = manifest.get(node.attrib.get("idref"))
            if not href:
                continue
            data = archive.read(safe_member(posixpath.dirname(package_path), href))
            parser = TextHtmlParser()
            parser.feed(data.decode("utf-8-sig"))
            text = "\n".join(line.strip() for line in "".join(parser.parts).splitlines() if line.strip())
            total += len(text)
            if total > MAX_CHARS or len(sections) >= MAX_SECTIONS:
                raise DocumentError("The extracted EPUB exceeds the text or section limit.")
            heading = " ".join(parser.heading).strip() or None
            sections.append({"chapter": heading, "section": heading, "page": None, "text": text})
    return document(title, author, sections)


def main() -> int:
    try:
        path = Path(sys.argv[1])
        mode = sys.argv[2] if len(sys.argv) > 2 else "--extract"
        is_ocr_candidate = path.suffix.lower() in {".pdf", ".png", ".jpg", ".jpeg", ".webp", ".tif", ".tiff"}
        limit_memory(OCR_MEMORY_LIMIT if is_ocr_candidate else MEMORY_LIMIT,
                     1800 if is_ocr_candidate else 55)
        if mode == "--inspect":
            print(json.dumps({"ok": True, "inspection": inspect_pdf(path)}, ensure_ascii=False))
            return 0
        if mode not in {"--extract", "--mobile-ocr"}:
            raise DocumentError("Invalid document extraction mode.")
        mobile_ocr = None
        if mode == "--mobile-ocr":
            if path.suffix.lower() != ".pdf":
                raise DocumentError("Phone OCR results can only be applied to a PDF.")
            raw = sys.stdin.buffer.read(16 * 1024 * 1024 + 1)
            if len(raw) > 16 * 1024 * 1024:
                raise DocumentError("Phone OCR results exceed the 16 MiB limit.")
            try:
                supplied = json.loads(raw)
                page_texts = supplied["page_texts"]
                mobile_ocr = {int(page): text for page, text in page_texts.items()
                              if isinstance(page, str) and page.isdecimal() and isinstance(text, str)}
                if not isinstance(supplied, dict) or not isinstance(page_texts, dict) or len(mobile_ocr) != len(page_texts):
                    raise ValueError
            except (ValueError, TypeError):
                raise DocumentError("Phone OCR results are invalid.") from None
        readers = {".txt": read_txt, ".pdf": lambda value: read_pdf(value, mobile_ocr), ".epub": read_epub,
                   ".png": read_image, ".jpg": read_image, ".jpeg": read_image,
                   ".webp": read_image, ".tif": read_image, ".tiff": read_image}
        result = readers[path.suffix.lower()](path)
        if mode == "--mobile-ocr":
            supplied_title = supplied.get("title")
            if isinstance(supplied_title, str) and supplied_title.strip():
                result["title"] = supplied_title.strip()[:300]
        print(json.dumps({"ok": True, "document": result}, ensure_ascii=False))
        return 0
    except DocumentError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
    except Exception:
        print(json.dumps({"ok": False, "error": "The document could not be read or exceeded a resource limit."}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
