"""Errors containing only messages that are safe to return to the caller."""

import logging
from uuid import UUID

from starlette.responses import JSONResponse

from app.schemas import ErrorDetail, ErrorResponse


logger = logging.getLogger("uvicorn.error.visual_assistant")


class AssistantError(Exception):
    def __init__(
        self, status_code: int, code: str, message: str, *, retryable: bool = False
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.retryable = retryable


def error_response(error: AssistantError, request_id: UUID) -> JSONResponse:
    # Only application-controlled categories belong here, never error text or inputs.
    logger.log(
        logging.ERROR if error.status_code >= 500 else logging.WARNING,
        "request_error request_id=%s status_code=%s code=%s retryable=%s",
        request_id, error.status_code, error.code, error.retryable,
    )
    body = ErrorResponse(
        request_id=request_id,
        error=ErrorDetail(
            code=error.code, message=error.message, retryable=error.retryable
        ),
    )
    return JSONResponse(body.model_dump(mode="json"), status_code=error.status_code)
