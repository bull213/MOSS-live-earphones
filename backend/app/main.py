"""FastAPI application setup, client lifetime and safe error responses."""

from contextlib import asynccontextmanager
from collections.abc import AsyncIterator
import asyncio
from threading import Lock

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from openai import AsyncOpenAI
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse

from app.config import MODEL_TIMEOUT_SECONDS, Settings
from app.errors import AssistantError, error_response
from app.middleware import RequestMiddleware
from app.openai_gateway import OpenAIVisionGateway
from app.photo_cache import PhotoCache
from app.stepfun_gateway import StepFunGateway
from app.knowledge.embeddings import LocalMiniLM
from app.knowledge.index import KnowledgeIndex
from app.knowledge.book_import_service import PhoneBookImportService
from app.adb_watcher import run_adb_watcher
from app.routes import router
from app.knowledge.routes import router as knowledge_router
from app.moss_live import router as moss_live_router
from app.notebooklm.client import NotebookLMService
from app.notebooklm.routes import router as notebooklm_router

try:
    import truststore
    truststore.inject_into_ssl()
except Exception:
    pass


async def _staging_cleanup_loop(service: PhoneBookImportService) -> None:
    while True:
        await asyncio.sleep(300)
        try:
            await asyncio.to_thread(service.cleanup_expired)
        except OSError:
            continue


async def _notebooklm_keepalive_loop(service: NotebookLMService) -> None:
    while True:
        await asyncio.sleep(1200)
        try:
            await service.keepalive_tick()
        except asyncio.CancelledError:
            break
        except Exception:
            continue


def create_app(settings: Settings | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        configured = settings if settings is not None else Settings()
        application.state.settings = configured
        application.state.vision_gateway = None
        application.state.knowledge_index = KnowledgeIndex(
            configured.knowledge_data_dir / "library.sqlite3",
            LocalMiniLM(configured.knowledge_data_dir / "models" / "all-MiniLM-L6-v2"),
        )
        application.state.knowledge_import_lock = Lock()
        application.state.photo_cache = PhotoCache()
        application.state.notebooklm_service = NotebookLMService(configured.notebooklm_data_dir)
        application.state.phone_book_import_service = PhoneBookImportService(
            configured.knowledge_data_dir,
            application.state.knowledge_index,
            application.state.knowledge_import_lock,
        )
        await asyncio.to_thread(application.state.phone_book_import_service.cleanup_expired)
        staging_cleanup = asyncio.create_task(_staging_cleanup_loop(application.state.phone_book_import_service))
        adb_watcher = asyncio.create_task(run_adb_watcher())
        notebooklm_keepalive = asyncio.create_task(_notebooklm_keepalive_loop(application.state.notebooklm_service))
        client = None
        try:
            if configured.assistant_provider == "stepfun" and configured.stepfun_api_key is not None:
                client = AsyncOpenAI(
                    api_key=configured.stepfun_api_key.get_secret_value(),
                    base_url=configured.effective_stepfun_base_url,
                    timeout=MODEL_TIMEOUT_SECONDS,
                    max_retries=0,
                )
                application.state.vision_gateway = StepFunGateway(
                    client, configured.stepfun_model, effort=configured.stepfun_reasoning_effort,
                )
            elif configured.assistant_provider == "openai" and configured.openai_api_key is not None:
                client = AsyncOpenAI(
                    api_key=configured.openai_api_key.get_secret_value(),
                    base_url="https://api.openai.com/v1",
                    timeout=MODEL_TIMEOUT_SECONDS,
                    max_retries=0,
                )
                application.state.vision_gateway = OpenAIVisionGateway(client, configured.openai_model)
            yield
        finally:
            notebooklm_keepalive.cancel()
            adb_watcher.cancel()
            staging_cleanup.cancel()
            try:
                await notebooklm_keepalive
            except asyncio.CancelledError:
                pass
            try:
                await adb_watcher
            except asyncio.CancelledError:
                pass
            try:
                await staging_cleanup
            except asyncio.CancelledError:
                pass
            if client is not None:
                await client.close()
            application.state.vision_gateway = None
            application.state.knowledge_index = None
            application.state.notebooklm_service = None
            application.state.phone_book_import_service = None

    application = FastAPI(
        title="AI Visual Assistant Backend",
        description="Configured AI assistant, knowledge library, and realtime MOSS Live routes.",
        version="0.2.0",
        lifespan=lifespan,
    )
    application.state.settings = settings if settings is not None else Settings()
    application.add_middleware(RequestMiddleware)
    application.include_router(router)
    application.include_router(knowledge_router)
    application.include_router(moss_live_router)
    application.include_router(notebooklm_router)

    @application.get("/health", tags=["health"])
    async def health() -> dict[str, str]:
        """Report process health without contacting external services."""
        return {"status": "ok"}

    @application.exception_handler(AssistantError)
    async def assistant_error(request: Request, exc: AssistantError) -> JSONResponse:
        return error_response(exc, request.state.request_id)

    @application.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError) -> JSONResponse:
        # Never echo validation inputs: they may include the question or image.
        return error_response(
            AssistantError(422, "invalid_request", (
                "Provide a nonblank question of up to 1,000 characters and, if included, a JPEG or PNG image."
                if request.url.path.rstrip("/") == "/v1/assistant/turns" else
                "Provide a nonblank question of up to 1,000 characters, a JPEG or PNG image, and valid optional UUIDs and timezone timestamp."
            )),
            request.state.request_id,
        )

    @application.exception_handler(HTTPException)
    async def http_error(request: Request, exc: HTTPException) -> JSONResponse:
        message = "The request could not be read." if exc.status_code == 400 else "The requested operation is unavailable."
        return error_response(
            AssistantError(exc.status_code, "invalid_request" if exc.status_code == 400 else "http_error", message),
            request.state.request_id,
        )

    return application


app = create_app()
