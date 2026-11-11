"""All default tests are offline, even if local AI credentials exist."""

from io import BytesIO
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx2
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.config import Settings
from app.main import create_app
from app.openai_gateway import VisionResult
from app.routes import get_gateway
from app.schemas import AssistantReply


@pytest.fixture(autouse=True)
def block_outbound_http(monkeypatch):
    def blocked(*args, **kwargs):
        raise AssertionError("Tests must use a mock transport; outbound HTTP is forbidden.")

    async def blocked_async(*args, **kwargs):
        blocked()

    monkeypatch.setattr(httpx2.HTTPTransport, "handle_request", blocked)
    monkeypatch.setattr(httpx2.AsyncHTTPTransport, "handle_async_request", blocked_async)


@pytest.fixture
def jpeg_bytes():
    with Image.new("RGB", (64, 32), "navy") as image, BytesIO() as output:
        image.save(output, format="JPEG")
        return output.getvalue()


@pytest.fixture
def gateway():
    result = VisionResult(
        answer=AssistantReply(reply="Press the visible INPUT button once.", action="do_step"),
        model_ms=0, input_tokens=100, output_tokens=30,
    )
    return SimpleNamespace(answer=AsyncMock(return_value=result))


@pytest.fixture
def application(gateway):
    app = create_app(Settings(_env_file=None, openai_api_key=None))
    app.dependency_overrides[get_gateway] = lambda: gateway
    return app


@pytest.fixture
def client(application):
    with TestClient(application) as test_client:
        yield test_client


@pytest.fixture
def uploaded_files(monkeypatch):
    from starlette import formparsers

    original = formparsers.SpooledTemporaryFile
    files = []

    def track_file(*args, **kwargs):
        file = original(*args, **kwargs)
        files.append(file)
        return file

    monkeypatch.setattr(formparsers, "SpooledTemporaryFile", track_file)
    return files
