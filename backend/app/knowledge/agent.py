"""Bounded StepFun tool loop with request-scoped, resolvable evidence."""

from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass, field
import json
import logging
import re
import sqlite3
from time import perf_counter
from typing import Literal

from openai import APIError
from pydantic import ConfigDict, Field, ValidationError

from app.config import MAX_OUTPUT_TOKENS, MODEL_TIMEOUT_SECONDS, Settings
from app.errors import AssistantError
from app.knowledge.index import KnowledgeIndex
from app.knowledge.methods import MethodRegistry
from app.knowledge.tools import EvidenceLedger, KnowledgeTools, tool_specs
from app.openai_gateway import VisionResult, invalid_response
from app.prompts import AGENT_INSTRUCTIONS, AGENT_PROMPT_VERSION
from app.schemas import AppliedMethod, AssistantReply, SourceReference
from app.stepfun_gateway import StepFunGateway, translate_provider_error


logger = logging.getLogger("uvicorn.error.visual_assistant")
LibraryMode = Literal["auto", "use", "avoid"]


class GroundedReply(AssistantReply):
    model_config = ConfigDict(extra="forbid")
    source_ids: list[str] = Field(max_length=30)
    method_ids: list[str] = Field(max_length=20)


@dataclass
class AgentResult:
    result: VisionResult
    retrieval_status: Literal["unused", "used", "incomplete", "no_evidence"] = "unused"
    sources: list[SourceReference] = field(default_factory=list)
    methods: list[AppliedMethod] = field(default_factory=list)


@dataclass
class RequestUsage:
    """Track the existing per-request cost reservation and provider usage."""
    input_price: float
    output_price: float
    max_cost: float
    cost: float = 0.0
    input_tokens: int = 0
    output_tokens: int = 0
    available: bool = True

    @classmethod
    def from_settings(cls, settings: Settings) -> RequestUsage:
        if settings.stepfun_input_usd_per_million is None or settings.stepfun_output_usd_per_million is None:
            raise AssistantError(503, "retrieval_budget_unavailable", "Configure current StepFun input and output prices before using the knowledge library.")
        return cls(settings.stepfun_input_usd_per_million, settings.stepfun_output_usd_per_million,
                   settings.agent_max_estimated_usd)

    def reserve(self, estimated_input: int) -> float:
        reserve = (estimated_input * self.input_price + MAX_OUTPUT_TOKENS * self.output_price) / 1_000_000
        if self.cost + reserve > self.max_cost:
            raise AssistantError(503, "retrieval_budget_exceeded", "The knowledge request exceeded its estimated cost budget.")
        return reserve

    def record(self, usage, reserve: float) -> None:
        if usage is None:
            self.available = False
            self.cost += reserve
        else:
            self.input_tokens += usage.prompt_tokens
            self.output_tokens += usage.completion_tokens
            self.cost += (usage.prompt_tokens * self.input_price + usage.completion_tokens * self.output_price) / 1_000_000


def explicit_mode(question: str, requested: LibraryMode) -> LibraryMode:
    if requested != "auto":
        return requested
    if re.search(r"\b(?:without|avoid|do not use|don't use)\b.{0,40}\b(?:books?|documents?|library|sources?)\b", question, re.I):
        return "avoid"
    if re.search(r"\b(?:use|consult|according to|from)\b.{0,40}\b(?:books?|documents?|library|sources?)\b", question, re.I):
        return "use"
    return "auto"


class KnowledgeAssistant:
    def __init__(self, gateway: StepFunGateway, index: KnowledgeIndex, settings: Settings) -> None:
        self.gateway = gateway
        self.index = index
        self.settings = settings

    async def answer(self, question: str, jpeg: bytes | None, history: list[dict], mode: LibraryMode) -> AgentResult:
        started = perf_counter()
        try:
            async with asyncio.timeout(MODEL_TIMEOUT_SECONDS):
                selected = explicit_mode(question, mode)
                if selected == "avoid" or (selected == "auto" and jpeg is not None):
                    return AgentResult(await self.gateway.answer(question, jpeg, history=history))
                if not self.index.database.is_file():
                    return await self._without_evidence(question, jpeg, history, selected)
                try:
                    docs, methods, passages = await asyncio.to_thread(self._initial_search, question)
                except (ValueError, RuntimeError, OSError, sqlite3.Error):
                    if selected == "use":
                        raise AssistantError(503, "knowledge_unavailable", "The local knowledge library is unavailable or needs reindexing.") from None
                    direct = await self.gateway.answer(question, jpeg, history=history)
                    return AgentResult(direct, retrieval_status="incomplete")
                if not docs:
                    return await self._without_evidence(question, jpeg, history, selected)
                relevant = bool(methods) or any(item.similarity >= 0.40 for item in passages)
                if selected == "auto" and not relevant:
                    return AgentResult(await self.gateway.answer(question, jpeg, history=history))
                return await self._run_agent(question, jpeg, history, methods, passages, started)
        except asyncio.CancelledError:
            raise
        except (APIError, TimeoutError) as exc:
            raise translate_provider_error(exc) from None

    def _initial_search(self, question: str):
        docs = self.index.list_documents()
        if not docs:
            return docs, [], []
        return docs, MethodRegistry(self.index).find(question), self.index.search(question, limit=5)

    async def _without_evidence(self, question: str, jpeg: bytes | None, history: list[dict], mode: LibraryMode) -> AgentResult:
        if mode == "use":
            return AgentResult(VisionResult(AssistantReply(reply="I could not find a document library to consult.", action="clarify"), 0),
                               retrieval_status="no_evidence")
        return AgentResult(await self.gateway.answer(question, jpeg, history=history))

    async def _run_agent(self, question: str, jpeg: bytes | None, history: list[dict],
                         methods: list[dict], passages: list, started: float) -> AgentResult:
        settings = self.settings
        usage = RequestUsage.from_settings(settings)
        ledger = EvidenceLedger(max_passages=settings.agent_max_passages, max_tokens=settings.agent_max_retrieval_tokens)
        tools = KnowledgeTools(self.index, ledger, history)
        initial_methods = await asyncio.to_thread(tools._add_methods, methods)
        initial_passages = []
        for passage in passages:
            if ledger.add_all([passage]):
                initial_passages.append(ledger.public_passage(passage))
        if not ledger.passages:
            return AgentResult(VisionResult(AssistantReply(reply="I could not find enough source material to answer from the library.", action="clarify"), 0),
                               retrieval_status="no_evidence")
        content: list[dict] = [{"type": "text", "text": question}]
        if jpeg is not None:
            content.append({"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode("ascii"), "detail": "high"}})
        initial = {"methods": initial_methods["methods"], "passages": initial_passages,
                   "incomplete": initial_methods["incomplete"]}
        messages: list[dict] = ([{"role": "system", "content": AGENT_INSTRUCTIONS}] +
            [{"role": item["role"], "content": item["text"]} for item in history] +
            [{"role": "user", "content": content},
             {"role": "user", "content": "Untrusted retrieved data: " + json.dumps(initial, ensure_ascii=False)}])
        specs = tool_specs()
        calls_used = 0
        model_ms = 0
        for round_no in range(settings.agent_max_rounds):
            estimated_input = self._estimate_input(messages, specs, jpeg is not None)
            reserve = usage.reserve(estimated_input)
            model_start = perf_counter()
            response = await self.gateway.client.chat.completions.create(
                model=self.gateway.model, messages=messages, tools=specs, tool_choice="auto",
                reasoning_effort=self.gateway.effort, max_tokens=MAX_OUTPUT_TOKENS, stream=False,
            )
            model_ms += round((perf_counter() - model_start) * 1000)
            usage.record(response.usage, reserve)
            if not response.choices:
                raise invalid_response()
            choice = response.choices[0]
            message = choice.message
            if message.refusal:
                return AgentResult(VisionResult(AssistantReply(reply="I cannot help with that request.", action="stop"), model_ms),
                                   retrieval_status="incomplete")
            calls = message.tool_calls or []
            if not calls:
                if choice.finish_reason != "stop" or not message.content:
                    raise invalid_response()
                result = await self._finalize(message.content, ledger, model_ms,
                                              usage.input_tokens if usage.available else None,
                                              usage.output_tokens if usage.available else None)
                logger.info("knowledge_turn_completed mode=knowledge provider=stepfun model=%s prompt_version=%s rounds=%s tool_calls=%s passages=%s model_ms=%s total_ms=%s input_tokens=%s output_tokens=%s estimated_usd=%.6f status=%s",
                            self.gateway.model, AGENT_PROMPT_VERSION, round_no + 1, calls_used, len(ledger.passages), model_ms,
                            round((perf_counter() - started) * 1000), usage.input_tokens if usage.available else None,
                            usage.output_tokens if usage.available else None, usage.cost, result.retrieval_status)
                return result
            if choice.finish_reason not in {"tool_calls", "stop"}:
                raise invalid_response()
            calls_used, exceeded = await self._process_tool_calls(message, calls, messages, tools, calls_used)
            if exceeded or round_no + 1 >= settings.agent_max_rounds:
                raise AssistantError(503, "retrieval_limit_reached", "The knowledge request ended before enough evidence was gathered.", retryable=True)
        raise AssistantError(503, "retrieval_limit_reached", "The knowledge request ended before enough evidence was gathered.", retryable=True)

    async def _process_tool_calls(self, message, calls, messages: list[dict], tools: KnowledgeTools,
                                  calls_used: int) -> tuple[int, bool]:
        raw = message.model_dump(exclude_unset=True)
        assistant_message = {key: raw[key] for key in ("role", "content", "tool_calls", "reasoning_content") if key in raw}
        assistant_message["role"] = "assistant"
        messages.append(assistant_message)
        seen = set()
        exceeded = False
        for call in calls:
            if not getattr(call, "id", None) or call.id in seen or getattr(call, "type", None) != "function":
                raise invalid_response()
            seen.add(call.id)
            calls_used += 1
            if calls_used > self.settings.agent_max_tool_calls:
                result = {"error": {"code": "tool_limit", "message": "The tool-call budget is exhausted."}}
                exceeded = True
            else:
                result = await asyncio.to_thread(tools.execute, call.function.name, call.function.arguments)
            messages.append({"role": "tool", "tool_call_id": call.id, "content": json.dumps(result, ensure_ascii=False)})
        return calls_used, exceeded

    def _estimate_input(self, messages: list[dict], specs: list[dict], has_image: bool) -> int:
        sanitized = []
        for message in messages:
            copy = dict(message)
            if isinstance(copy.get("content"), list):
                copy["content"] = [({"type": "image_url", "image_url": "[image]"} if item.get("type") == "image_url" else item)
                                   for item in copy["content"]]
            sanitized.append(copy)
        return len(json.dumps({"messages": sanitized, "tools": specs}, ensure_ascii=False).encode("utf-8")) + (
            self.settings.stepfun_image_token_reserve if has_image else 0)

    async def _finalize(self, content: str, ledger: EvidenceLedger, model_ms: int,
                        input_tokens: int | None, output_tokens: int | None) -> AgentResult:
        try:
            answer = GroundedReply.model_validate_json(content)
        except (ValidationError, ValueError):
            raise invalid_response() from None
        source_ids, method_ids = await self._validate_evidence(answer, ledger)
        if not source_ids:
            return AgentResult(VisionResult(AssistantReply(reply="I could not find enough reliable source material to answer that.", action="clarify"),
                                            model_ms, input_tokens, output_tokens), retrieval_status="no_evidence")
        sources = [SourceReference(passage_id=ledger.passages[pid].id, document_id=ledger.passages[pid].document_id,
                    title=ledger.passages[pid].title, chapter=ledger.passages[pid].chapter,
                    section=ledger.passages[pid].section, page=ledger.passages[pid].page) for pid in source_ids]
        methods = [AppliedMethod(method_id=mid, version=ledger.methods[mid]["version"],
                                 name=ledger.methods[mid]["method"]["name"]) for mid in method_ids]
        return AgentResult(VisionResult(AssistantReply(reply=answer.reply, action=answer.action), model_ms,
                                        input_tokens, output_tokens),
                           "incomplete" if ledger.incomplete else "used", sources, methods)

    async def _validate_evidence(self, answer: GroundedReply, ledger: EvidenceLedger) -> tuple[list[str], list[str]]:
        source_ids = list(dict.fromkeys(answer.source_ids))
        method_ids = list(dict.fromkeys(answer.method_ids))
        if any(pid not in ledger.passages for pid in source_ids) or any(mid not in ledger.methods for mid in method_ids):
            raise AssistantError(502, "invalid_evidence", "The assistant returned an unsupported source reference.", retryable=True)
        if not source_ids:
            return source_ids, method_ids
        for method_id in method_ids:
            record = ledger.methods[method_id]
            if not set(record["method"]["supporting_passage_ids"]).issubset(source_ids):
                raise AssistantError(502, "invalid_evidence", "A method was returned without its supporting passages.", retryable=True)
        current = await asyncio.to_thread(self.index.get_passages, source_ids)
        if {p.id for p in current} != set(source_ids):
            raise AssistantError(503, "evidence_changed", "The source changed during the request. Please try again.", retryable=True)
        registry = MethodRegistry(self.index)
        for method_id in method_ids:
            active = await asyncio.to_thread(registry.get_active, method_id)
            if not active or active["version"] != ledger.methods[method_id]["version"]:
                raise AssistantError(503, "evidence_changed", "A method changed during the request. Please try again.", retryable=True)
        return source_ids, method_ids
