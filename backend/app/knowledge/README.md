# Local Knowledge Library & Document Ingestion

## Overview
Manages local document ingestion, OCR processing, hierarchical section indexing, SQLite FTS5 full-text and MiniLM vector semantic retrieval, and StepFun multi-step reasoning tool loops. It supports on-device or server-side book scanning, structured procedural method extraction, and intelligent delegation to Google NotebookLM for complex cloud-indexed manuals.

## Interface
- `KnowledgeAssistant` (`agent.py`): Primary retrieval-augmented assistant answering questions from indexed documents and manuals.
  - `answer(question, jpeg, history, mode) -> AgentResult`: Runs explicit target check, NotebookLM delegation, or StepFun tool-use loop.
- `KnowledgeTools` (`tools.py`): Asynchronous tool executor providing local library searches, `inspect_recent_photo` (vision sub-query), `list_notebooks`, and `ask_notebook_direct` (fused RAG query).
- `explicit_notebook_target(question: str) -> tuple[str | None, str]` (`agent.py`): Parses `@title`, `In notebook "title"`, or conversational syntax to extract explicit notebook targets.
- `AgentResult` (`agent.py`): Structured outcome with `retrieval_status` (`"notebooklm"`, `"used"`, `"unused"`, `"no_evidence"`), `sources`, `methods`, `notebook_title`, and `read_aloud`.
- `KnowledgeIndex` (`index.py`): SQLite (`library.sqlite3`) storage layer combining FTS5 lexical search and vector cosine similarity.
  - `search(query: str, limit: int = 5) -> list[Passage]`: Returns top matching document passages.
  - `list_documents() -> list[dict[str, Any]]`: Lists all registered and indexed manuals/books.
  - `index_document(...)`: Indexes extracted text chunks into SQLite tables with vector embeddings.
- `LocalMiniLM` (`embeddings.py`): Generates 384-dimensional dense text embeddings locally via `all-MiniLM-L6-v2`.
- `PhoneBookImportService` (`book_import_service.py`): Staging, page ordering, deduplication, and OCR pipeline for document photos received from the Android client.
- `router` (`routes.py`): FastAPI router exposing book import, search, and status endpoints.

## Invariants
- **Thread Safety**: All SQLite database queries and ONNX embedding inferences run via `asyncio.to_thread()` to avoid blocking the async event loop.
- **Budget Control**: The agent evaluates `RequestUsage` before executing provider tool loops, enforcing `agent_max_estimated_usd` limits.
- **Privacy Tiers**: Sensitive documents (`TIER_2_SENSITIVE`) are strictly restricted to local retrieval; only public appliance manuals (`TIER_1_PUBLIC`) may be routed to cloud NotebookLM.
- **Graceful Fallback**: If SQLite is corrupted or unavailable, or if local search returns no relevant matches, `KnowledgeAssistant` gracefully degrades to the base provider or returns an informative clarification rather than crashing.

## Side Effects
- Reads and writes SQLite database `backend/.data/knowledge/library.sqlite3`.
- Creates and purges temporary image upload files in `backend/uploads/` and staging directories.
- Incurs provider API token usage on StepFun when tool loops are executed.

## Verification
- Run: `.\.venv\Scripts\pytest.exe -q backend/tests/test_knowledge_agent.py backend/tests/test_knowledge_index.py`
