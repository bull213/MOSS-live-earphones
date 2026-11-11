"""Public response types and the structured reply expected from the model."""

from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints


Action = Literal["answer", "do_step", "take_photo", "clarify", "complete", "stop"]
Question = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)
]


class AssistantReply(BaseModel):
    model_config = ConfigDict(extra="forbid")

    reply: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)
    ]
    action: Action


class Timings(BaseModel):
    model: int = Field(ge=0)
    server_total: int = Field(ge=0)


class TurnResponse(AssistantReply):
    request_id: UUID
    timings_ms: Timings


class ContextMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: Literal["user", "assistant"]
    text: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]


class SourceReference(BaseModel):
    passage_id: str
    document_id: str
    title: str
    chapter: str | None = None
    section: str | None = None
    page: int | None = None


class AppliedMethod(BaseModel):
    method_id: str
    version: int = Field(ge=1)
    name: str


class AssistantTurnResponse(TurnResponse):
    sources: list[SourceReference] = Field(default_factory=list)
    applied_methods: list[AppliedMethod] = Field(default_factory=list)
    retrieval_status: Literal["unused", "used", "incomplete", "no_evidence"] = "unused"


class ErrorDetail(BaseModel):
    code: str
    message: str
    retryable: bool


class ErrorResponse(BaseModel):
    request_id: UUID
    error: ErrorDetail
