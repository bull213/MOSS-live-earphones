"""Tests for NotebookLM specialized RAG service, circuit breaker, and routing."""

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from notebooklm.exceptions import AuthError

from app.config import Settings
from app.main import create_app
from app.knowledge.agent import KnowledgeAssistant, explicit_notebook_target
from app.knowledge.index import Passage
from app.notebooklm.client import (
    CitationReference,
    GroundedChatResult,
    NotebookLMAuthExpiredError,
    NotebookLMService,
)
from app.schemas import AssistantReply


@pytest.fixture
def temp_data_dir(tmp_path: Path) -> Path:
    data_dir = tmp_path / "notebooklm"
    data_dir.mkdir(parents=True)
    return data_dir


@pytest.mark.anyio
async def test_notebooklm_unauthenticated(temp_data_dir: Path) -> None:
    service = NotebookLMService(temp_data_dir)
    assert not service.has_auth()
    status = await service.check_auth_status()
    assert status["authenticated"] is False
    assert status["reason"] == "auth_file_missing"
    assert await service.list_notebooks() == []
    assert await service.ask_chat("nb-1", "How do I clean the filter?") is None


@pytest.mark.anyio
async def test_notebooklm_list_notebooks_auth_expired(temp_data_dir: Path) -> None:
    auth_file = temp_data_dir / "auth_state.json"
    auth_file.write_text('{"cookies": [{"name": "SID", "value": "xyz"}]}')
    service = NotebookLMService(temp_data_dir)

    with patch("app.notebooklm.client.NotebookLMClient.from_storage") as mock_from_storage:
        mock_client = AsyncMock()
        mock_client.notebooks.list.side_effect = AuthError("Google session cookies expired")
        mock_from_storage.return_value.__aenter__.return_value = mock_client

        with pytest.raises(NotebookLMAuthExpiredError) as exc_info:
            await service.list_notebooks(force_refresh=True)
        assert "Google NotebookLM authentication expired" in str(exc_info.value)
        assert service._cached_notebooks is None


@pytest.mark.anyio
async def test_notebooklm_check_auth_status_expired(temp_data_dir: Path) -> None:
    auth_file = temp_data_dir / "auth_state.json"
    auth_file.write_text('{"cookies": [{"name": "SID", "value": "xyz"}]}')
    service = NotebookLMService(temp_data_dir)

    with patch("app.notebooklm.client.NotebookLMClient.from_storage") as mock_from_storage:
        mock_client = AsyncMock()
        mock_client.get_account_email.side_effect = AuthError("Expired cookies")
        mock_from_storage.return_value.__aenter__.return_value = mock_client

        status = await service.check_auth_status()
        assert status["authenticated"] is False
        assert status["reason"] == "AuthError"


@pytest.mark.anyio
async def test_notebooklm_circuit_breaker_timeout(temp_data_dir: Path) -> None:
    auth_file = temp_data_dir / "auth_state.json"
    auth_file.write_text('{"cookies": [{"name": "SID", "value": "xyz"}]}')
    service = NotebookLMService(temp_data_dir, default_timeout=0.05)

    with patch("app.notebooklm.client.NotebookLMClient.from_storage") as mock_from_storage:
        mock_client = AsyncMock()

        async def _slow_ask(*args, **kwargs):
            await asyncio.sleep(0.5)

        mock_client.chat.ask.side_effect = _slow_ask
        mock_from_storage.return_value.__aenter__.return_value = mock_client

        result = await service.ask_chat("nb-1", "Slow question", timeout=0.05)
        assert result is None  # Circuit breaker triggered gracefully


@pytest.mark.anyio
async def test_notebooklm_ask_chat_success(temp_data_dir: Path) -> None:
    auth_file = temp_data_dir / "auth_state.json"
    auth_file.write_text('{"cookies": [{"name": "SID", "value": "xyz"}]}')
    service = NotebookLMService(temp_data_dir)

    with patch("app.notebooklm.client.NotebookLMClient.from_storage") as mock_from_storage:
        mock_client = AsyncMock()
        mock_result = MagicMock()
        mock_result.answer = "Turn off power and unscrew the lower filter counter-clockwise."
        mock_result.conversation_id = "conv-123"
        ref1 = MagicMock()
        ref1.source_id = "src-1"
        ref1.citation_number = 1
        ref1.cited_text = "Section 4: Filter Maintenance"
        mock_result.references = [ref1]
        mock_client.chat.ask.return_value = mock_result
        mock_from_storage.return_value.__aenter__.return_value = mock_client

        result = await service.ask_chat("nb-1", "How to clean filter?")
        assert result is not None
        assert "Turn off power" in result.answer
        assert len(result.citations) == 1
        assert result.citations[0].source_id == "src-1"


@pytest.mark.anyio
async def test_knowledge_assistant_delegates_to_notebooklm() -> None:
    mock_gateway = AsyncMock()
    mock_index = MagicMock()
    mock_index.database.is_file.return_value = True

    doc = {
        "id": "doc-1",
        "title": "Washing Machine Manual",
        "notebooklm_id": "nlm-washer-1",
        "notebooklm_status": "READY",
        "privacy_tier": "TIER_1_PUBLIC",
    }
    passage = Passage(
        id="p-1", document_id="doc-1", title="Washing Machine Manual",
        author=None, chapter_id="c-1", chapter=None, section_id="s-1",
        section=None, page=12, text="Filter maintenance procedure",
        context="", ordinal=1, similarity=0.88,
    )
    mock_index.list_documents.return_value = [doc]
    mock_index.search.return_value = [passage]

    mock_service = MagicMock()
    mock_service.has_auth.return_value = True
    mock_service.ask_chat = AsyncMock(return_value=GroundedChatResult(
        answer="Rotate drain knob counter-clockwise.",
        citations=[CitationReference(source_id="src-1", citation_number=1, cited_text="Section 4")],
    ))

    settings = Settings(assistant_provider="stepfun", stepfun_api_key="sk-test")
    assistant = KnowledgeAssistant(mock_gateway, mock_index, settings, notebooklm_service=mock_service)

    with patch("app.knowledge.agent.MethodRegistry") as mock_registry_cls:
        mock_registry_cls.return_value.find.return_value = []
        result = await assistant.answer("clean washer filter", None, [], "auto")

    assert result.retrieval_status == "notebooklm"
    assert result.notebook_title == "Washing Machine Manual"
    assert result.read_aloud is True
    assert "Rotate drain knob" in result.result.answer.reply
    assert len(result.sources) == 1
    mock_service.ask_chat.assert_awaited_once_with("nlm-washer-1", "clean washer filter", timeout=30.0)


def test_status_endpoint() -> None:
    app = create_app()
    with TestClient(app) as client:
        resp = client.get("/v1/notebooklm/status")
        assert resp.status_code == 200
        data = resp.json()
        assert data["configured"] is True
        assert "authenticated" in data


def test_explicit_notebook_target_parser() -> None:
    cases = [
        ("@Reason is fun: What is the fun criterion?", ("Reason is fun", "What is the fun criterion?")),
        ('@"Reason is fun" What is the fun criterion?', ("Reason is fun", "What is the fun criterion?")),
        ("@washer how to clean filter?", ("washer", "how to clean filter?")),
        ("What is the fun criterion? @Reason is fun", ("Reason is fun", "What is the fun criterion?")),
        ("notebook: Reason is fun: What is the fun criterion?", ("Reason is fun", "What is the fun criterion?")),
        ('In the notebook "Reason is fun", what is the fun criterion?', ("Reason is fun", "what is the fun criterion?")),
        ("In the Reason is fun notebook, what is the fun criterion?", ("Reason is fun", "what is the fun criterion?")),
        ("According to Reason is fun: what is the fun criterion?", ("Reason is fun", "what is the fun criterion?")),
        ("Ask notebook Reason is fun: what is the fun criterion?", ("Reason is fun", "what is the fun criterion?")),
        ("From the Washing Machine manual: how do I clean the filter?", ("Washing Machine", "how do I clean the filter?")),
        ("Just a general question with no notebook", (None, "Just a general question with no notebook")),
    ]
    for text, expected in cases:
        assert explicit_notebook_target(text) == expected


@pytest.mark.anyio
async def test_notebooklm_find_notebook_matching(temp_data_dir: Path) -> None:
    auth_file = temp_data_dir / "auth_state.json"
    auth_file.write_text('{"cookies": [{"name": "SID", "value": "xyz"}]}')
    service = NotebookLMService(temp_data_dir)

    with patch.object(service, "list_notebooks", new_callable=AsyncMock) as mock_list:
        mock_list.return_value = [
            {"id": "nb-1", "title": "Reason is fun"},
            {"id": "nb-2", "title": "Washing Machine Manual"},
        ]

        # 1. Exact ID match
        nb_id = await service.find_notebook("nb-1")
        assert nb_id is not None
        assert nb_id["id"] == "nb-1"

        # 2. Exact Title match (case-insensitive)
        nb_title = await service.find_notebook("reason is fun")
        assert nb_title is not None
        assert nb_title["id"] == "nb-1"

        # 3. Prefix match
        nb_prefix = await service.find_notebook("washing")
        assert nb_prefix is not None
        assert nb_prefix["id"] == "nb-2"

        # 4. Substring match
        nb_sub = await service.find_notebook("Machine")
        assert nb_sub is not None
        assert nb_sub["id"] == "nb-2"

        # 5. Non-match
        nb_none = await service.find_notebook("Toaster")
        assert nb_none is None


@pytest.mark.anyio
async def test_knowledge_assistant_explicit_notebook_routing() -> None:
    mock_gateway = AsyncMock()
    mock_index = MagicMock()

    mock_service = MagicMock()
    mock_service.has_auth.return_value = True
    mock_service.find_notebook = AsyncMock(return_value={"id": "nb-10", "title": "Reason is fun"})
    mock_service.ask_chat = AsyncMock(return_value=GroundedChatResult(
        answer="The fun criterion is engaging exploration.",
        citations=[CitationReference(source_id="src-1", citation_number=1, cited_text="Section 1")],
    ))

    settings = Settings(assistant_provider="stepfun", stepfun_api_key="sk-test")
    assistant = KnowledgeAssistant(mock_gateway, mock_index, settings, notebooklm_service=mock_service)

    result = await assistant.answer("@Reason is fun: What is the fun criterion?", None, [], "auto")

    assert result.retrieval_status == "notebooklm"
    assert result.notebook_title == "Reason is fun"
    assert result.read_aloud is True
    assert "engaging exploration" in result.result.answer.reply
    mock_service.find_notebook.assert_awaited_once_with("Reason is fun")
    mock_service.ask_chat.assert_awaited_once_with("nb-10", "What is the fun criterion?", timeout=45.0)


def test_create_assistant_turn_explicit_notebook_with_openai_no_image(temp_data_dir: Path) -> None:
    settings = Settings(assistant_provider="openai", openai_api_key="sk-mock-key")
    app = create_app(settings)

    mock_nlm_service = MagicMock()
    mock_nlm_service.has_auth.return_value = True
    mock_nlm_service.find_notebook = AsyncMock(return_value={"id": "nb-10", "title": "Reason is fun"})
    mock_nlm_service.ask_chat = AsyncMock(return_value=GroundedChatResult(
        answer="The fun criterion is engaging exploration.",
        citations=[CitationReference(source_id="src-1", citation_number=1, cited_text="Section 1")],
    ))

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_nlm_service
        resp = client.post(
            "/v1/assistant/turns",
            files={"question": (None, "@Reason is fun: What is the fun criterion?")},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["reply"] == "The fun criterion is engaging exploration."
        assert data["retrieval_status"] == "notebooklm"
        assert data["notebook_title"] == "Reason is fun"
        assert data["read_aloud"] is True
        assert len(data["sources"]) == 1


def test_create_assistant_turn_explicit_notebook_without_ai_gateway(temp_data_dir: Path) -> None:
    settings = Settings(assistant_provider="openai", openai_api_key=None)
    app = create_app(settings)

    mock_nlm_service = MagicMock()
    mock_nlm_service.has_auth.return_value = True
    mock_nlm_service.find_notebook = AsyncMock(return_value={"id": "nb-10", "title": "Reason is fun"})
    mock_nlm_service.ask_chat = AsyncMock(return_value=GroundedChatResult(
        answer="The fun criterion is engaging exploration.",
        citations=[CitationReference(source_id="src-1", citation_number=1, cited_text="Section 1")],
    ))

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_nlm_service
        resp = client.post(
            "/v1/assistant/turns",
            files={"question": (None, "@Reason is fun: What is the fun criterion?")},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["reply"] == "The fun criterion is engaging exploration."
        assert data["retrieval_status"] == "notebooklm"
        assert data["notebook_title"] == "Reason is fun"


def test_create_assistant_turn_explicit_notebook_not_found(temp_data_dir: Path) -> None:
    settings = Settings(assistant_provider="openai", openai_api_key="sk-mock-key")
    app = create_app(settings)

    mock_nlm_service = MagicMock()
    mock_nlm_service.has_auth.return_value = True
    mock_nlm_service.find_notebook = AsyncMock(return_value=None)

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_nlm_service
        resp = client.post(
            "/v1/assistant/turns",
            files={"question": (None, "@UnknownNotebook: How does it work?")},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert "couldn't find a notebook named 'UnknownNotebook'" in data["reply"]
        assert data["action"] == "clarify"
        assert data["read_aloud"] is True


@pytest.mark.anyio
async def test_knowledge_tools_list_and_ask_notebook(temp_data_dir: Path) -> None:
    from app.knowledge.tools import KnowledgeTools, EvidenceLedger
    mock_index = MagicMock()
    mock_nlm_service = MagicMock()
    mock_nlm_service.has_auth.return_value = True
    mock_nlm_service.list_notebooks = AsyncMock(return_value=[
        {"id": "nb-1", "title": "Reason is fun"},
        {"id": "nb-2", "title": "Washing Machine Manual"},
    ])
    mock_nlm_service.find_notebook = AsyncMock(return_value={"id": "nb-1", "title": "Reason is fun"})
    mock_nlm_service.ask_chat = AsyncMock(return_value=GroundedChatResult(
        answer="The fun criterion is engaging exploration.",
        citations=[CitationReference(source_id="nlm-0", citation_number=1, cited_text="Fun rules")],
    ))

    ledger = EvidenceLedger(max_passages=10, max_tokens=1000)
    tools = KnowledgeTools(mock_index, ledger, [], notebooklm_service=mock_nlm_service)

    # 1. list_notebooks
    list_res = await tools.execute("list_notebooks", '{"query": "fun"}')
    assert "notebooks" in list_res
    assert len(list_res["notebooks"]) == 1
    assert list_res["notebooks"][0]["title"] == "Reason is fun"

    # 2. ask_notebook_direct
    ask_res = await tools.execute("ask_notebook_direct", '{"notebook_name_or_query": "Reason is fun", "question": "What is fun?"}')
    assert ask_res["status"] == "answered"
    assert ask_res["notebook_title"] == "Reason is fun"
    assert "engaging exploration" in ask_res["answer"]
    assert ledger.notebook_title == "Reason is fun"
    assert len(ledger.external_sources) == 1


@pytest.mark.anyio
async def test_knowledge_tools_inspect_recent_photo() -> None:
    from app.knowledge.tools import KnowledgeTools, EvidenceLedger
    from app.photo_cache import PhotoCache
    from app.openai_gateway import VisionResult
    mock_index = MagicMock()
    cache = PhotoCache()
    photo_id = "550e8400-e29b-41d4-a716-446655440000"
    cache.store_photo(photo_id, b"fake-jpeg")

    gateway = AsyncMock()
    gateway.answer.return_value = VisionResult(AssistantReply(reply="The red light is blinking.", action="answer"), 10)

    ledger = EvidenceLedger(max_passages=10, max_tokens=1000)
    tools = KnowledgeTools(mock_index, ledger, [], photo_cache=cache, gateway=gateway)

    res = await tools.execute("inspect_recent_photo", f'{{"photo_id": "{photo_id}", "question": "Is the light blinking?"}}')
    assert res["status"] == "inspected"
    assert res["description"] == "The red light is blinking."
    assert ledger.photo_inspected is True


def test_notebooklm_auth_reset(temp_data_dir: Path) -> None:
    auth_file = temp_data_dir / "auth_state.json"
    auth_file.write_text('{"cookies": []}')
    service = NotebookLMService(temp_data_dir)
    assert service.has_auth()

    assert service.reset_auth() is True
    assert not auth_file.exists()
    assert not service.has_auth()


def test_trigger_login_endpoint() -> None:
    app = create_app()
    with TestClient(app) as client:
        with patch("app.notebooklm.routes.subprocess.Popen") as mock_popen:
            mock_proc = MagicMock()
            mock_proc.pid = 12345
            mock_popen.return_value = mock_proc

            resp = client.post("/v1/notebooklm/auth/trigger-login")
            assert resp.status_code == 200
            data = resp.json()
            assert data["success"] is True
            assert data["pid"] == 12345

            login_calls = [c for c in mock_popen.call_args_list if "login" in c[0][0]]
            assert len(login_calls) == 1
            cmd_args = login_calls[0][0][0]
            assert "login" in cmd_args
            # Ensure --fresh is omitted so persistent session and browser profile are preserved
            assert "--fresh" not in cmd_args


def test_trigger_login_endpoint_failure_returns_500() -> None:
    app = create_app()
    with TestClient(app) as client:
        with patch("app.notebooklm.routes.subprocess.Popen", side_effect=OSError("Process failed")):
            resp = client.post("/v1/notebooklm/auth/trigger-login")
            assert resp.status_code == 500
            data = resp.json()
            assert data["error"]["code"] == "login_launch_failed"


def test_turn_with_notebook_id_success() -> None:
    app = create_app()
    mock_service = MagicMock()
    mock_service.has_auth.return_value = True
    mock_service.list_notebooks = AsyncMock(return_value=[{"id": "nb-washer", "title": "Washer Manual"}])
    mock_service.ask_chat = AsyncMock(return_value=GroundedChatResult(
        answer="### Filter Cleaning\n**Unplug the washer** [1]. Then unscrew the filter [2].",
        citations=[CitationReference(source_id="src-1", citation_number=1, cited_text="Unplug washer before opening pump.")],
    ))

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_service
        resp = client.post(
            "/v1/assistant/turns",
            files={
                "question": (None, "How do I clean the drain filter?"),
                "notebook_id": (None, "nb-washer"),
            },
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["retrieval_status"] == "notebooklm"
        assert data["notebook_title"] == "Washer Manual"
        assert "[1]" not in data["reply"]
        assert "**" not in data["reply"]
        assert "###" not in data["reply"]
        assert len(data["sources"]) == 1
        assert data["sources"][0]["title"] == "Washer Manual"


def test_turn_with_notebook_id_unauthenticated() -> None:
    app = create_app()
    mock_service = MagicMock()
    mock_service.has_auth.return_value = False

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_service
        resp = client.post(
            "/v1/assistant/turns",
            files={
                "question": (None, "How do I clean the drain filter?"),
                "notebook_id": (None, "nb-washer"),
            },
        )
        assert resp.status_code == 401
        data = resp.json()
        assert data["error"]["code"] == "notebooklm_unauthenticated"


def test_turn_with_notebook_id_timeout_returns_clear_status() -> None:
    app = create_app()
    mock_service = MagicMock()
    mock_service.has_auth.return_value = True
    mock_service.list_notebooks = AsyncMock(return_value=[{"id": "nb-washer", "title": "Washer Manual"}])
    mock_service.ask_chat = AsyncMock(return_value=None)

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_service
        resp = client.post(
            "/v1/assistant/turns",
            files={
                "question": (None, "How do I clean the drain filter?"),
                "notebook_id": (None, "nb-washer"),
            },
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["retrieval_status"] == "unused"
        assert "couldn't get a response" in data["reply"]


def test_get_notebook_history_success() -> None:
    app = create_app()
    mock_service = MagicMock()
    mock_service.has_auth.return_value = True
    mock_service.get_history = AsyncMock(return_value=[
        ("What is step 1?", "Step 1 is to **disconnect** the power [1]."),
        ("What is step 2?", "Step 2 is to unscrew the cap."),
    ])

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_service
        resp = client.get("/v1/notebooklm/notebooks/nb-washer/history")
        assert resp.status_code == 200
        data = resp.json()
        assert data["notebook_id"] == "nb-washer"
        assert len(data["turns"]) == 2
        assert data["turns"][0]["question"] == "What is step 1?"
        # Verify sanitization stripped asterisks and citation brackets
        assert data["turns"][0]["answer"] == "Step 1 is to disconnect the power."
        assert data["turns"][1]["question"] == "What is step 2?"


def test_get_notebook_history_unauthenticated() -> None:
    app = create_app()
    mock_service = MagicMock()
    mock_service.has_auth.return_value = False

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_service
        resp = client.get("/v1/notebooklm/notebooks/nb-washer/history")
        assert resp.status_code == 401
        data = resp.json()
        assert data["error"]["code"] == "notebooklm_unauthenticated"


def test_turn_with_notebook_id_auth_expired_returns_401() -> None:
    app = create_app()
    mock_service = MagicMock()
    mock_service.has_auth.return_value = True
    mock_service.list_notebooks = AsyncMock(return_value=[{"id": "nb-washer", "title": "Washer Manual"}])
    mock_service.ask_chat = AsyncMock(side_effect=NotebookLMAuthExpiredError("Auth expired"))

    with TestClient(app) as client:
        app.state.notebooklm_service = mock_service
        resp = client.post(
            "/v1/assistant/turns",
            files={
                "question": (None, "How do I clean the drain filter?"),
                "notebook_id": (None, "nb-washer"),
            },
        )
        assert resp.status_code == 401
        data = resp.json()
        assert data["error"]["code"] == "notebooklm_unauthenticated"


@pytest.mark.anyio
async def test_notebooklm_storage_sync(temp_data_dir: Path) -> None:
    legacy_file = temp_data_dir / "auth_state.json"
    legacy_file.write_text('{"cookies": [{"name": "SID", "value": "xyz"}]}')
    service = NotebookLMService(temp_data_dir)
    assert service.has_auth()
    assert (temp_data_dir / "storage_state.json").exists()
    assert (temp_data_dir / "storage_state.json").read_text() == legacy_file.read_text()


@pytest.mark.anyio
async def test_notebooklm_auto_heal_on_auth_error(temp_data_dir: Path) -> None:
    auth_file = temp_data_dir / "storage_state.json"
    auth_file.write_text('{"cookies": [{"name": "SID", "value": "xyz"}]}')
    profile_default = temp_data_dir / "browser_profile" / "Default"
    profile_default.mkdir(parents=True, exist_ok=True)
    service = NotebookLMService(temp_data_dir)

    with patch.object(service, "auto_heal", new_callable=AsyncMock) as mock_heal:
        mock_heal.return_value = True

        with patch("app.notebooklm.client.NotebookLMClient.from_storage") as mock_from_storage:
            call_count = 0

            def _side_effect(*args, **kwargs):
                nonlocal call_count
                call_count += 1
                mock_client = AsyncMock()
                if call_count == 1:
                    mock_client.get_account_email.side_effect = AuthError("Expired")
                else:
                    mock_client.get_account_email.return_value = "healed@google.com"
                cm = MagicMock()
                cm.__aenter__ = AsyncMock(return_value=mock_client)
                cm.__aexit__ = AsyncMock(return_value=None)
                return cm

            mock_from_storage.side_effect = _side_effect

            status = await service.check_auth_status()
            assert status["authenticated"] is True
            assert status["email"] == "healed@google.com"
            mock_heal.assert_awaited_once()




