"""NotebookLM specialized RAG coprocessor package."""

from app.notebooklm.client import (
    CitationReference,
    GroundedChatResult,
    NotebookLMAuthExpiredError,
    NotebookLMService,
)
from app.notebooklm.nlp import (
    build_moss_live_notebook_instructions,
    explicit_notebook_target,
    match_notebook_from_speech,
    parse_explicit_notebook_target,
    sanitize_grounded_voice_summary,
)

__all__ = [
    "CitationReference",
    "GroundedChatResult",
    "NotebookLMAuthExpiredError",
    "NotebookLMService",
    "build_moss_live_notebook_instructions",
    "explicit_notebook_target",
    "match_notebook_from_speech",
    "parse_explicit_notebook_target",
    "sanitize_grounded_voice_summary",
]
