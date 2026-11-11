"""Optional, local OCR providers used only during document ingestion."""

from __future__ import annotations

from dataclasses import dataclass
import base64
import importlib.metadata
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
from contextlib import redirect_stdout
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class OCRResult:
    text: str
    provider: str
    model_version: str
    diagnostics: dict


class OCRProvider:
    name: str
    version: str

    def recognize(self, image: Path) -> OCRResult:
        raise NotImplementedError

    def recognize_many(self, images: list[Path]) -> list[OCRResult]:
        return [self.recognize(image) for image in images]


class TesseractProvider(OCRProvider):
    name = "tesseract"

    def __init__(self, executable: str) -> None:
        self.executable = executable
        try:
            version = subprocess.run([executable, "--version"], capture_output=True, text=True,
                                     timeout=10, check=True).stdout.splitlines()[0]
        except (OSError, subprocess.SubprocessError, IndexError):
            raise RuntimeError("The configured Tesseract executable could not be started.") from None
        self.version = version.strip()

    def recognize(self, image: Path) -> OCRResult:
        try:
            result = subprocess.run(
                [self.executable, str(image), "stdout", "--psm", "3", "-l", "eng"],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                timeout=120, check=False, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        except subprocess.TimeoutExpired:
            raise RuntimeError("OCR timed out while processing a page.") from None
        except OSError:
            raise RuntimeError("Tesseract could not be started.") from None
        if result.returncode != 0:
            raise RuntimeError("Tesseract failed to process a page.")
        return OCRResult(result.stdout.decode("utf-8", errors="replace"), self.name, self.version, {})


class PaddleOCRProvider(OCRProvider):
    name = "paddleocr"

    def __init__(self) -> None:
        try:
            self.version = importlib.metadata.version("paddleocr")
            from paddleocr import PaddleOCR
            with redirect_stdout(sys.stderr):
                self.engine = PaddleOCR(lang="en", use_doc_orientation_classify=False,
                                        use_doc_unwarping=False, use_textline_orientation=True)
        except Exception as exc:
            raise RuntimeError(f"PaddleOCR could not initialize ({type(exc).__name__}).") from None

    def recognize(self, image: Path) -> OCRResult:
        try:
            with redirect_stdout(sys.stderr):
                rows = self.engine.predict(input=str(image))
            texts: list[str] = []
            scores: list[float] = []
            for row in rows:
                payload = getattr(row, "json", row)
                if callable(payload):
                    payload = payload()
                if isinstance(payload, dict):
                    payload = payload.get("res", payload)
                    if isinstance(payload, dict):
                        texts.extend(str(value) for value in payload.get("rec_texts", []) if value)
                        scores.extend(float(value) for value in payload.get("rec_scores", [])
                                      if isinstance(value, (float, int)))
            return OCRResult("\n".join(texts), self.name, self.version,
                             {"line_count": len(texts), "mean_line_confidence":
                              sum(scores) / len(scores) if scores else None})
        except Exception as exc:
            raise RuntimeError(f"PaddleOCR failed to process a page ({type(exc).__name__}).") from None


class UnlimitedOCR2Provider(OCRProvider):
    """Adapter for an explicitly started loopback SGLang OCR-2 service."""

    name = "unlimited-ocr-2"

    def __init__(self) -> None:
        self.endpoint = os.environ.get("UNLIMITED_OCR_ENDPOINT", "http://127.0.0.1:10000/v1/chat/completions")
        parsed = urlsplit(self.endpoint)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise RuntimeError("Unlimited-OCR must use a local HTTP endpoint bound to loopback.")
        if not parsed.path.endswith("/v1/chat/completions"):
            raise RuntimeError("Unlimited-OCR endpoint must end in /v1/chat/completions.")
        self.version = os.environ.get("UNLIMITED_OCR_MODEL_REVISION", "baidu/Unlimited-OCR-2@unverified")
        self.ngram_processor = os.environ.get("UNLIMITED_OCR_NGRAM_PROCESSOR", "").strip()
        if not self.ngram_processor:
            raise RuntimeError("Set UNLIMITED_OCR_NGRAM_PROCESSOR to the pinned SGLang no-repeat processor configuration.")

    def recognize(self, image: Path) -> OCRResult:
        return self.recognize_many([image])[0]

    def recognize_many(self, images: list[Path]) -> list[OCRResult]:
        if not images:
            return []
        if len(images) > 8:
            raise RuntimeError("Unlimited-OCR page windows are limited to eight pages.")
        content = []
        for image in images:
            if image.stat().st_size > 12 * 1024 * 1024:
                raise RuntimeError("A rendered page exceeds the 12 MiB OCR input limit.")
            suffix = image.suffix.lower()
            mime = "image/jpeg" if suffix in {".jpg", ".jpeg"} else f"image/{suffix.lstrip('.') or 'png'}"
            encoded = base64.b64encode(image.read_bytes()).decode("ascii")
            content.append({"type": "image_url", "image_url": {"url": f"data:{mime};base64,{encoded}"}})
        content.append({"type": "text", "text": "<PAGE><image>" * len(images) + "Multi page parsing."})
        body = json.dumps({"model": "Unlimited-OCR-2", "temperature": 0,
                           "max_tokens": 3000 * len(images),
                           "skip_special_tokens": False,
                           "messages": [{"role": "user", "content": content}],
                           "images_config": {"crop_mode": True},
                           "custom_logit_processor": self.ngram_processor,
                           "custom_params": {"ngram_size": 35,
                                             "window_size": 1024 if len(images) > 1 else 128,
                                             "max_page_tokens": 20000}}).encode("utf-8")
        try:
            request = Request(self.endpoint, data=body, headers={"Content-Type": "application/json"})
            with urlopen(request, timeout=1800) as response:
                payload = json.loads(response.read(50 * 1024 * 1024 + 1))
        except Exception as exc:
            raise RuntimeError(f"Unlimited-OCR local service failed ({type(exc).__name__}).") from None
        if len(json.dumps(payload).encode("utf-8")) > 50 * 1024 * 1024:
            raise RuntimeError("Unlimited-OCR returned more than 50 MiB of output.")
        try:
            choice = payload["choices"][0]
            if choice.get("finish_reason") == "length":
                raise RuntimeError("Unlimited-OCR output reached its token limit.")
            output = choice["message"]["content"]
            if not isinstance(output, str):
                output = "".join(part.get("text", "") for part in output if isinstance(part, dict))
        except RuntimeError:
            raise
        except (KeyError, IndexError, TypeError):
            raise RuntimeError("Unlimited-OCR returned an invalid response.") from None
        pages = output.split("<PAGE>")[1:]
        if len(pages) != len(images):
            raise RuntimeError("Unlimited-OCR output page count did not match the input window.")
        return [OCRResult(text.strip(), self.name, self.version,
                          {"window_pages": len(images), "page_index": index + 1})
                for index, text in enumerate(pages)]


_provider: OCRProvider | None = None
_provider_lock = threading.Lock()


def _configured_provider_name() -> str:
    value = os.environ.get("OCR_PROVIDER", "auto").strip().lower()
    if value not in {"auto", "paddleocr", "tesseract", "unlimited-ocr-2"}:
        raise RuntimeError("OCR_PROVIDER must be auto, paddleocr, tesseract, or unlimited-ocr-2.")
    return value


def ocr_provider_available() -> bool:
    try:
        selected = _configured_provider_name()
    except RuntimeError:
        return False
    tesseract = os.environ.get("TESSERACT_CMD", "tesseract")
    if selected == "tesseract":
        return bool(shutil.which(tesseract))
    if selected == "paddleocr":
        return importlib.util.find_spec("paddleocr") is not None
    if selected == "unlimited-ocr-2":
        return True
    return bool(shutil.which(tesseract)) or importlib.util.find_spec("paddleocr") is not None


def get_ocr_provider() -> OCRProvider:
    global _provider
    with _provider_lock:
        if _provider is not None:
            return _provider
        selected = _configured_provider_name()
        tesseract = os.environ.get("TESSERACT_CMD", "tesseract")
        if selected in {"tesseract", "auto"} and shutil.which(tesseract):
            _provider = TesseractProvider(tesseract)
        elif selected == "unlimited-ocr-2":
            _provider = UnlimitedOCR2Provider()
        elif selected in {"paddleocr", "auto"} and importlib.util.find_spec("paddleocr"):
            _provider = PaddleOCRProvider()
        elif selected == "paddleocr":
            raise RuntimeError("PaddleOCR is not installed in the configured OCR environment.")
        else:
            raise RuntimeError("No local OCR provider is available. Install Tesseract or PaddleOCR.")
        return _provider
