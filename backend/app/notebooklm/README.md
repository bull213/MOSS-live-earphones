# Google NotebookLM Integration Package

## Overview
Provides seamless integration with Google NotebookLM as an external specialized Retrieval-Augmented Generation (RAG) coprocessor and Collections knowledge provider. It manages Google authentication state, discovers user notebooks, routes explicit and semantic notebook queries, fetches past Q&A history, sanitizes answers for voice readouts, and guarantees session persistence through automated Layer-3 headless token recovery and background keepalive rotation.

## Interface
- `NotebookLMService`: Main async service client managing session lifecycle, connection pooling, caching, headless auto-healing, and grounded chat queries [client.py:NotebookLMService]
  - `has_auth() -> bool`: Verifies existence and minimum size (>10 bytes) of `storage_state.json` or `auth_state.json`.
  - `check_auth_status() -> dict[str, Any]`: Live validation of authentication token against Google endpoints with automatic headless recovery fallback.
  - `reset_auth(clear_browser_profile: bool = False) -> bool`: Purges in-memory cached notebooks, clears notebook locks, unlinks storage files, and optionally deletes `browser_profile`.
  - `list_notebooks(force_refresh: bool = False) -> list[dict[str, Any]]`: Cached listing (5-minute TTL) of available cloud notebooks.
  - `find_notebook(query_or_title: str) -> dict[str, Any] | None`: Resolves notebook IDs or titles via exact, prefix, or substring matching.
  - `create_notebook(title: str) -> str | None`: Creates a new cloud notebook.
  - `add_source_file(notebook_id: str, file_path: Path) -> str | None`: Uploads a local file source to a cloud notebook.
  - `ask_chat(notebook_id: str, question: str, timeout: float | None = None) -> GroundedChatResult | None`: Grounded chat inquiry with circuit breaker fallback and auto-healing retry.
  - `get_history(notebook_id: str, limit: int = 50) -> list[tuple[str, str]]`: Retrieves past conversation turns for a notebook using Google internal RPCs.
  - `auto_heal() -> bool`: Drives unattended headless Chrome against persistent browser profile to re-mint expired cookies.
  - `keepalive_tick()`: Proactive background keepalive poke rotating Google session cookies.
- `CitationReference`: Structured reference metadata including `source_id`, `citation_number`, and `cited_text` [client.py:CitationReference]
- `GroundedChatResult`: Container for grounded answers, citations, and optional `conversation_id` [client.py:GroundedChatResult]
- `NotebookLMAuthExpiredError`: Exception subclass of `NotebookLMError` raised when Google NotebookLM authentication expires [client.py:NotebookLMAuthExpiredError]
- `get_browser_choice`: Utility detecting installed local browser (`chrome`, `msedge`, or `chromium`) [client.py:get_browser_choice]
- `install_custom_headless_rung`: Process-level registration of persistent profile Layer-3 headless recovery rung [client.py:install_custom_headless_rung]
- `parse_explicit_notebook_target` (alias `explicit_notebook_target`): Parses `@tag`, `@"title"`, `notebook: title:`, or natural language notebook targets from user prompts [nlp.py:parse_explicit_notebook_target]
- `match_notebook_from_speech`: Fuzzy matching resolver mapping spoken user transcripts to candidate notebooks using keyword scoring, stop-word elimination, and active context [nlp.py:match_notebook_from_speech]
- `is_meta_notebook_inquiry`: Regex detector for queries asking to list or inspect accessible notebooks [nlp.py:is_meta_notebook_inquiry]
- `sanitize_grounded_voice_summary`: Text post-processor stripping markdown, citations, emojis, and links, truncating cleanly at sentence boundaries for audio TTS [nlp.py:sanitize_grounded_voice_summary]
- `build_moss_live_notebook_instructions`: Formats dynamic system prompts informing realtime AI models of connected notebooks [nlp.py:build_moss_live_notebook_instructions]
- `router`: FastAPI APIRouter mounted at `/v1/notebooklm` [routes.py:router]
  - `GET /v1/notebooklm/status`: Current authentication state, cached notebooks count, and account verification.
  - `GET /v1/notebooklm/notebooks`: List of available cloud notebooks.
  - `GET /v1/notebooklm/notebooks/{notebook_id}/history`: History endpoint returning past conversation Q&A turns formatted for voice.
  - `POST /v1/notebooklm/auth/trigger-login`: Spawns host-side interactive browser login process via `cli.py login`.
  - `POST /v1/notebooklm/auth/reset`: Purges session and token storage to allow switching accounts.
- `cli.py`: Command-line management tool for browser authentication and discovery (`login`, `status`).

## Invariants
- **Storage Validity**: `storage_state.json` and `auth_state.json` must be regular files with size strictly greater than 10 bytes to satisfy `has_auth()` [client.py:NotebookLMService.has_auth]
- **Storage Mirroring**: Bidirectional sync invariant ensures `storage_state.json` and `auth_state.json` are mirrored if one exists and the other does not [client.py:NotebookLMService._sync_storage_files]
- **Auto-Healing Execution**: Operations are bounded by `asyncio.timeout(timeout)`; on `AuthError`, `_cached_notebooks` is cleared, `auto_heal()` runs once via unattended headless browser, and operation retries before raising `NotebookLMAuthExpiredError` [client.py:NotebookLMService._execute_with_retry]
- **Per-Notebook Lock**: Each notebook ID has a dedicated `asyncio.Lock` (`_get_lock`) ensuring concurrent chat queries and source uploads to the same notebook do not corrupt Google's server-side conversation state [client.py:NotebookLMService._get_lock]
- **Access Control**: All HTTP route handlers enforce loopback or private subnet security filters via `require_loopback_or_private_subnet` [routes.py:require_loopback_or_private_subnet]
- **Profile Retention**: Browser login and headless re-auth maintain the persistent `browser_profile` directory on disk, deleting it only when `--fresh` is explicitly passed [cli.py:cmd_login]
- **SSL Trust Store**: Uses `truststore.inject_into_ssl()` to ensure Windows Enterprise and Root CAs are accepted by Python's `httpx` and Playwright [client.py:truststore.inject_into_ssl]

## Side Effects
- Outbound HTTPS network RPCs to Google NotebookLM endpoints (`client.notebooks.list`, `client.chat.ask`, `client.chat.get_history`, `client.sources.add_file`) [client.py:NotebookLMService]
- Spawns Playwright headless browser worker in background thread via `asyncio.to_thread`, mutating `storage_state.json` with refreshed session credentials [client.py:NotebookLMService.auto_heal]
- Creates directory `browser_profile_dir` on local filesystem and sets environment variable `NOTEBOOKLM_HEADLESS_REAUTH='1'` [client.py:install_custom_headless_rung]
- Unlinks `storage_state.json` and `auth_state.json` on authentication reset [client.py:NotebookLMService.reset_auth]
- Appends timestamp and command to `.data/notebooklm/login.log` and launches interactive browser sign-in process [routes.py:trigger_notebooklm_login]

## Verification
- Run: `pytest backend/tests/test_notebooklm_rag.py backend/tests/test_notebooklm_nlp.py`
