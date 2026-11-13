"""FastAPI endpoints for NotebookLM status and cloud mirroring."""

from __future__ import annotations

import logging
from pathlib import Path
import subprocess
import sys
import time

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.errors import AssistantError
from app.notebooklm.client import NotebookLMAuthExpiredError
from app.notebooklm.nlp import sanitize_grounded_voice_summary
from app.security import require_loopback_or_private_subnet

logger = logging.getLogger("uvicorn.error.visual_assistant")
router = APIRouter(prefix="/v1/notebooklm", tags=["notebooklm"])


@router.get("/status", summary="Check NotebookLM authentication and connection status")
async def get_notebooklm_status(request: Request) -> dict:
    require_loopback_or_private_subnet(request)
    service = getattr(request.app.state, "notebooklm_service", None)
    if service is None:
        return {"configured": False, "authenticated": False, "status": "service_not_initialized"}
    status = await service.check_auth_status()
    return {"configured": True, **status}


@router.get("/notebooks", summary="List cloud notebooks available in NotebookLM")
async def list_cloud_notebooks(request: Request) -> list[dict]:
    require_loopback_or_private_subnet(request)
    service = getattr(request.app.state, "notebooklm_service", None)
    if service is None or not service.has_auth():
        return []
    return await service.list_notebooks()


class NotebookTurn(BaseModel):
    turn_id: str
    question: str
    answer: str
    relative_order: int


class NotebookHistoryResponse(BaseModel):
    notebook_id: str
    turns: list[NotebookTurn]


@router.get("/notebooks/{notebook_id}/history", response_model=NotebookHistoryResponse, summary="Get past Q&A turns for a notebook")
async def get_notebook_history(notebook_id: str, request: Request) -> NotebookHistoryResponse:
    require_loopback_or_private_subnet(request)
    service = getattr(request.app.state, "notebooklm_service", None)
    if service is None or not service.has_auth():
        raise AssistantError(401, "notebooklm_unauthenticated", "NotebookLM authentication is missing or expired. Connect in Settings.")
    try:
        raw_turns = await service.get_history(notebook_id, limit=50)
    except NotebookLMAuthExpiredError:
        raise AssistantError(401, "notebooklm_unauthenticated", "Google NotebookLM authentication expired. Reconnect in Settings.")
    except Exception as exc:
        logger.warning("notebooklm_history_route_failed notebook_id=%s error=%s", notebook_id, exc)
        raw_turns = []

    turns = [
        NotebookTurn(
            turn_id=f"hist-{notebook_id}-{idx}",
            question=q,
            answer=sanitize_grounded_voice_summary(a, max_chars=800),
            relative_order=idx,
        )
        for idx, (q, a) in enumerate(raw_turns)
    ]
    return NotebookHistoryResponse(notebook_id=notebook_id, turns=turns)


@router.post("/auth/reset", summary="Reset NotebookLM authentication credentials and cache")
async def reset_notebooklm_auth(request: Request, clear_profile: bool = False, clear_master_token: bool = False) -> dict:
    require_loopback_or_private_subnet(request)
    service = getattr(request.app.state, "notebooklm_service", None)
    if service is None:
        return {"success": False, "error": "service_not_initialized"}
    success = service.reset_auth(clear_browser_profile=clear_profile, clear_master_token=clear_master_token)
    return {"success": success}


@router.post("/auth/trigger-login", summary="Launch PC browser for Google login asynchronously")
async def trigger_notebooklm_login(request: Request) -> dict:
    require_loopback_or_private_subnet(request)
    service = getattr(request.app.state, "notebooklm_service", None)
    if service is not None and service.has_master_token():
        # Unattended server mode: master token is already present, trigger headless auto-heal / remint
        logger.info("notebooklm_login_triggered_reminting_master_token")
        healed = await service.auto_heal()
        if healed:
            return {"success": True, "message": "Master token refreshed successfully", "mode": "master_token"}
        return {
            "success": False,
            "status": "master_token_remint_failed",
            "message": "Master token could not be refreshed. Re-bootstrap required.",
            "requires_rebootstrap": True,
        }

    # If master token is not configured:
    # Check if request is coming from local loopback (desk developer) vs remote/tailnet
    client_host = request.client.host if request.client else "unknown"
    is_loopback = client_host in ("127.0.0.1", "::1", "testclient")
    if not is_loopback:
        # Remote request cannot launch interactive browser on unattended PC
        logger.warning("notebooklm_login_rejected_remote host=%s", client_host)
        return {
            "success": False,
            "status": "manual_bootstrap_required",
            "message": "Unattended server requires one-time bootstrap on the host PC. Run 'notebooklm login --master-token --account <email>' on the host.",
            "requires_rebootstrap": True,
        }

    backend_dir = Path(__file__).resolve().parent.parent.parent
    data_dir = backend_dir / ".data" / "notebooklm"
    data_dir.mkdir(parents=True, exist_ok=True)
    log_file = data_dir / "login.log"

    cmd = [sys.executable, "-m", "app.notebooklm.cli", "login"]
    creation_flags = subprocess.CREATE_NEW_PROCESS_GROUP if sys.platform == "win32" else 0
    try:
        with open(log_file, "a", encoding="utf-8") as log_fp:
            log_fp.write(f"\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] Launching login process: {' '.join(cmd)}\n")
            proc = subprocess.Popen(
                cmd,
                cwd=backend_dir,
                stdout=log_fp,
                stderr=subprocess.STDOUT,
                creationflags=creation_flags,
            )
        return {"success": True, "message": "Browser login process launched", "pid": proc.pid}
    except Exception as exc:
        logger.error("notebooklm_login_launch_failed error=%s", exc, exc_info=True)
        raise AssistantError(500, "login_launch_failed", f"Failed to launch login process: {exc}") from None
