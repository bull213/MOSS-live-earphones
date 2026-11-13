# Backend Application Subsystem

## Overview
Core Python FastAPI backend coordinating multimodal assistant turns, provider vision adapters (OpenAI, StepFun), MOSS Live realtime full-duplex audio relays, local knowledge indexing, and Google NotebookLM RAG coprocessing.

## Interface
- `create_app(settings: Settings | None = None) -> FastAPI` (`main.py`): Application factory initializing lifespan resources, provider gateways, background tasks, and API routers.
- `routes.py`:
  - `POST /v1/turns`: Fast single-turn photo question endpoint.
  - `POST /v1/assistant/turns`: Rich conversational turn endpoint supporting optional photo, `photo_id` caching, rolling conversation context, explicit `@NotebookName` queries, and knowledge library routing.
- `PhotoCache` (`photo_cache.py`): In-memory LRU photo cache (maxsize=5, 30-min TTL) supporting on-demand photo inspection (`inspect_recent_photo`) during follow-up turns.
- `moss_live.py`:
  - `WS /v1/realtime/moss-live`: WebSocket bidirectional PCM audio relay bridging Android client to upstream MOSS cloud service (`api.mosi.cn`).
- `OpenAIVisionGateway` (`openai_gateway.py`), `StepFunGateway` (`stepfun_gateway.py`): Concrete adapters implementing the unified `VisionGateway` protocol.
- `normalize_image(data: bytes, content_type: str | None) -> bytes` (`images.py`): Normalizes, resizes, and validates camera images to clean JPEG.
- `RequestMiddleware` (`middleware.py`): Injects `x-request-id`, tracks latency, and guards against oversize requests.
- `run_adb_watcher()` (`adb_watcher.py`): Background loop ensuring `adb reverse tcp:8000 tcp:8000` remains active across USB reconnections.

### Subdirectories & Component Contracts
- [Knowledge Library](./knowledge/README.md): Document ingestion, SQLite FTS5 + MiniLM vector search, and StepFun tool reasoning.
- [NotebookLM Coprocessor](./notebooklm/README.md): Google NotebookLM authentication, notebook catalog discovery, circuit-broken grounded queries, and direct `@` routing.

## Invariants
- **Loopback Binding**: The development server must bind strictly to `127.0.0.1:8000` (accessed from Android via ADB reverse tunnel) to prevent unauthenticated network exposure.
- **Task Cancellation**: All long-running inference tasks wrap with `run_until_disconnect` (`cancellation.py`), immediately aborting provider requests if the client terminates the connection.
- **Zero Audio Logging**: Binary PCM audio streams in `moss_live.py` are forwarded in real time without persisting raw audio data to disk or logs.
- **Input Sanitization**: Images are capped at 4 MiB / 20 megapixels and normalized via PIL before passing to provider gateways.

## Side Effects
- Upstream HTTPS RPC calls to OpenAI, StepFun, and Google NotebookLM.
- Upstream secure WebSocket connection to `wss://api.mosi.cn/v1/realtime`.
- Spawns background tasks for ADB device discovery and temporary file cleanup.

## Verification
- Run: `.\.venv\Scripts\pytest.exe -q backend`
