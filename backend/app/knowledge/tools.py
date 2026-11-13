"""Bounded, read-only knowledge tools and request-scoped evidence ledger."""

from __future__ import annotations

import asyncio
from dataclasses import asdict
import json
import sqlite3
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

from app.knowledge.index import KnowledgeIndex, Passage
from app.knowledge.methods import MethodRegistry


Query = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]
Id = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]


class Arguments(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SearchArguments(Arguments):
    query: Query
    document_id: Id | None = None
    chapter_id: Id | None = None
    limit: int = Field(default=5, ge=1, le=6)


class SectionArguments(Arguments):
    section_id: Id
    offset: int = Field(default=0, ge=0, le=100000)
    limit: int = Field(default=5, ge=1, le=6)


class MethodArguments(Arguments):
    query: Query
    limit: int = Field(default=5, ge=1, le=6)


class RelatedArguments(Arguments):
    method_id: Id


class ContextArguments(Arguments):
    pass


class InspectPhotoArguments(Arguments):
    photo_id: Id
    question: Query


class ListNotebooksArguments(Arguments):
    query: str | None = Field(default=None, max_length=100)
    limit: int = Field(default=15, ge=1, le=50)


class AskNotebookArguments(Arguments):
    notebook_name_or_query: Query
    question: Query


TOOL_DEFINITIONS = {
    "find_applicable_methods": (MethodArguments, "Find reviewed methods relevant to a task and their supporting passages."),
    "search_documents": (SearchArguments, "Search local documents when source evidence is needed."),
    "open_document_section": (SectionArguments, "Read nearby passages in a section already returned by a tool."),
    "get_related_methods": (RelatedArguments, "Find reviewed related methods for an already returned method."),
    "get_relevant_conversation_context": (ContextArguments, "Read the bounded conversation context supplied for this request."),
    "inspect_recent_photo": (InspectPhotoArguments, "Inspect a photograph previously captured in the conversation to examine visual details, read text, or identify objects."),
    "list_notebooks": (ListNotebooksArguments, "List available Google NotebookLM notebooks by name and ID."),
    "ask_notebook_direct": (AskNotebookArguments, "Ask a grounded question directly to a specific Google NotebookLM notebook or user guide by name or topic."),
}


def tool_specs() -> list[dict]:
    return [{"type": "function", "function": {"name": name, "description": description,
             "parameters": model.model_json_schema()}} for name, (model, description) in TOOL_DEFINITIONS.items()]


class EvidenceLedger:
    def __init__(self, *, max_passages: int, max_tokens: int) -> None:
        self.max_passages = max_passages
        self.max_tokens = max_tokens
        self.passages: dict[str, Passage] = {}
        self.methods: dict[str, dict] = {}
        self.external_sources: list[dict] = []
        self.notebook_title: str | None = None
        self.photo_inspected: bool = False
        self.used_tokens = 0
        self.incomplete = False

    def add_all(self, passages: list[Passage]) -> bool:
        new = {item.id: item for item in passages if item.id not in self.passages}
        # UTF-8 bytes are a deliberately conservative text-token estimate.
        size = sum(len(item.text.encode("utf-8")) for item in new.values())
        if len(self.passages) + len(new) > self.max_passages or self.used_tokens + size > self.max_tokens:
            self.incomplete = True
            return False
        self.passages.update(new)
        self.used_tokens += size
        return True

    def add_method(self, record: dict, passages: list[Passage]) -> bool:
        metadata_size = 0 if record["method_id"] in self.methods else len(json.dumps(record["method"], ensure_ascii=False).encode("utf-8"))
        new = {p.id: p for p in passages if p.id not in self.passages}
        passage_size = sum(len(item.text.encode("utf-8")) for item in new.values())
        if (len(self.passages) + len(new) > self.max_passages or
            self.used_tokens + metadata_size + passage_size > self.max_tokens):
            self.incomplete = True
            return False
        self.add_all(passages)
        self.used_tokens += metadata_size
        self.methods[record["method_id"]] = record
        return True

    @staticmethod
    def public_passage(passage: Passage) -> dict:
        item = asdict(passage)
        item.pop("score", None)
        item.pop("similarity", None)
        return item


class KnowledgeTools:
    def __init__(
        self,
        index: KnowledgeIndex,
        ledger: EvidenceLedger,
        history: list[dict],
        photo_cache: Any = None,
        notebooklm_service: Any = None,
        gateway: Any = None,
    ) -> None:
        self.index = index
        self.registry = MethodRegistry(index)
        self.ledger = ledger
        self.history = history
        self.photo_cache = photo_cache
        self.notebooklm_service = notebooklm_service
        self.gateway = gateway

    def _add_methods(self, records: list[dict]) -> dict:
        added = []
        incomplete = False
        for record in records:
            expected = record["method"]["supporting_passage_ids"]
            passages = self.index.get_passages(expected)
            if len(passages) != len(set(expected)) or not self.ledger.add_method(record, passages):
                self.ledger.incomplete = True
                incomplete = True
                continue
            added.append({"method_id": record["method_id"], "version": record["version"],
                          "method": record["method"], "evidence": [self.ledger.public_passage(p) for p in passages]})
        return {"methods": added, "incomplete": incomplete}

    async def _handle_inspect_photo(self, args: InspectPhotoArguments) -> dict:
        if self.photo_cache is None:
            return {"error": {"code": "photo_cache_unavailable", "message": "Photo cache is not configured."}}
        jpeg = self.photo_cache.get_photo(args.photo_id)
        if jpeg is None:
            return {"error": {"code": "photo_not_found", "message": f"Photo '{args.photo_id}' is not in memory or has expired."}}
        self.ledger.photo_inspected = True
        if self.gateway is not None:
            try:
                vr = await self.gateway.answer(args.question, jpeg)
                return {"status": "inspected", "photo_id": args.photo_id, "description": vr.answer.reply}
            except Exception as exc:
                return {"error": {"code": "inspection_failed", "message": f"Vision inspection failed: {exc}"}}
        return {"status": "photo_loaded", "photo_id": args.photo_id}

    async def _handle_list_notebooks(self, args: ListNotebooksArguments) -> dict:
        if self.notebooklm_service is None or not getattr(self.notebooklm_service, "has_auth", lambda: False)():
            return {"error": {"code": "notebooklm_not_configured", "message": "Google NotebookLM is not authenticated."}}
        notebooks = await self.notebooklm_service.list_notebooks()
        if args.query:
            q_lower = args.query.lower()
            notebooks = [nb for nb in notebooks if q_lower in nb["title"].lower()]
        notebooks = notebooks[:args.limit]
        return {"notebooks": [{"id": nb["id"], "title": nb["title"]} for nb in notebooks]}

    async def _handle_ask_notebook(self, args: AskNotebookArguments) -> dict:
        if self.notebooklm_service is None or not getattr(self.notebooklm_service, "has_auth", lambda: False)():
            return {"error": {"code": "notebooklm_not_configured", "message": "Google NotebookLM is not authenticated."}}
        matched_nb = await self.notebooklm_service.find_notebook(args.notebook_name_or_query)
        if not matched_nb:
            return {"error": {"code": "notebook_not_found", "message": f"Could not find a notebook named '{args.notebook_name_or_query}'."}}
        nlm_result = await self.notebooklm_service.ask_chat(matched_nb["id"], args.question, timeout=45.0)
        if nlm_result is None:
            return {"error": {"code": "query_failed", "message": "The notebook inquiry failed or timed out."}}

        citations = [
            {
                "source_id": f"nlm-{i}",
                "document_id": matched_nb["id"],
                "title": matched_nb["title"],
                "cited_text": getattr(cit, "cited_text", "")[:100] if getattr(cit, "cited_text", None) else "",
            }
            for i, cit in enumerate(getattr(nlm_result, "citations", [])[:5])
        ]
        self.ledger.external_sources.extend(citations)
        self.ledger.notebook_title = matched_nb["title"]
        return {
            "status": "answered",
            "notebook_title": matched_nb["title"],
            "answer": nlm_result.answer,
            "citations": citations,
        }

    async def _handle_search(self, args: SearchArguments) -> dict:
        matches = await asyncio.to_thread(
            self.index.search, args.query, limit=args.limit, document_id=args.document_id, chapter_id=args.chapter_id
        )
        accepted = []
        incomplete = False
        for passage in matches:
            if self.ledger.add_all([passage]):
                accepted.append(self.ledger.public_passage(passage))
            else:
                incomplete = True
        return {"passages": accepted, "incomplete": incomplete}

    async def _handle_section(self, args: SectionArguments) -> dict:
        if args.section_id not in {p.section_id for p in self.ledger.passages.values()}:
            return {"error": {"code": "section_not_discovered", "message": "Search for the section first."}}
        passages = await asyncio.to_thread(
            self.index.open_section, args.section_id, limit=args.limit + 1, offset=args.offset
        )
        has_more = len(passages) > args.limit
        passages = passages[:args.limit]
        accepted = []
        for passage in passages:
            if self.ledger.add_all([passage]):
                accepted.append(self.ledger.public_passage(passage))
        return {"passages": accepted, "incomplete": len(accepted) < len(passages),
                "next_offset": args.offset + args.limit if has_more else None}

    async def _handle_related(self, args: RelatedArguments) -> dict:
        method = self.ledger.methods.get(args.method_id)
        if method is None:
            return {"error": {"code": "method_not_discovered", "message": "Find the method first."}}
        related = []
        for method_id in dict.fromkeys(method["method"]["related_method_ids"] + method["method"]["conflicting_method_ids"]):
            record = await asyncio.to_thread(self.registry.get_active, method_id)
            if record:
                related.append(record)
        return self._add_methods(related)

    async def execute(self, name: str, arguments: str) -> dict:
        if name not in TOOL_DEFINITIONS:
            return {"error": {"code": "unknown_tool", "message": "This tool is not available."}}
        if not isinstance(arguments, str) or len(arguments.encode("utf-8")) > 16_384:
            return {"error": {"code": "invalid_arguments", "message": "Tool arguments are invalid or too large."}}
        model = TOOL_DEFINITIONS[name][0]
        try:
            args = model.model_validate_json(arguments)
        except (ValidationError, ValueError):
            return {"error": {"code": "invalid_arguments", "message": "Tool arguments do not match the schema."}}
        try:
            if isinstance(args, InspectPhotoArguments):
                return await self._handle_inspect_photo(args)
            if isinstance(args, ListNotebooksArguments):
                return await self._handle_list_notebooks(args)
            if isinstance(args, AskNotebookArguments):
                return await self._handle_ask_notebook(args)
            if isinstance(args, MethodArguments):
                return await asyncio.to_thread(self._add_methods, self.registry.find(args.query, limit=args.limit))
            if isinstance(args, SearchArguments):
                return await self._handle_search(args)
            if isinstance(args, SectionArguments):
                return await self._handle_section(args)
            if isinstance(args, RelatedArguments):
                return await self._handle_related(args)
            return {"messages": self.history}
        except (ValueError, RuntimeError, OSError, sqlite3.Error):
            return {"error": {"code": "knowledge_unavailable", "message": "The local knowledge operation failed."}}
        except (ValueError, RuntimeError, OSError, sqlite3.Error):
            return {"error": {"code": "knowledge_unavailable", "message": "The local knowledge operation failed."}}
