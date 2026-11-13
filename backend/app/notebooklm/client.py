"""Async service wrapper and circuit breaker for Google NotebookLM."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import logging
import os
from pathlib import Path
import shutil
import time
from typing import Any

from notebooklm import NotebookLMClient
from notebooklm.exceptions import AuthError, NotebookLMError, RateLimitError, RPCError, RPCTimeoutError

try:
    from notebooklm.options import ClientConfig, RuntimeOptions, WebBackendConfig, WebSessionOptions
except ImportError:
    ClientConfig = None  # type: ignore[assignment, misc]
    RuntimeOptions = None  # type: ignore[assignment, misc]
    WebBackendConfig = None  # type: ignore[assignment, misc]
    WebSessionOptions = None  # type: ignore[assignment, misc]

try:
    import truststore
    truststore.inject_into_ssl()
except Exception:
    pass

logger = logging.getLogger("uvicorn.error.notebooklm")


def get_browser_choice() -> str:
    """Detect available local browser for Playwright automation."""
    chrome_paths = [
        Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
        Path(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"),
    ]
    if any(p.exists() for p in chrome_paths):
        return "chrome"
    if Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe").exists():
        return "msedge"
    return "chromium"


def install_custom_headless_rung(browser_profile: Path) -> None:
    """Install custom Layer-3 headless recovery rung using the persistent browser profile."""
    try:
        from notebooklm._auth.recovery_rungs import (
            HeadlessRungOutcome,
            HeadlessRungStatus,
            install_headless_rung,
        )
        from notebooklm._browser.headless_reauth import (
            HeadlessReauthStatus,
            attempt_headless_reauth,
        )

        browser_choice = get_browser_choice()

        def _rung(*, storage_path: Path, allow_headless: bool) -> HeadlessRungOutcome:
            logger.info("notebooklm_headless_reauth_triggered storage=%s profile=%s", storage_path, browser_profile)
            res = attempt_headless_reauth(
                storage_path=storage_path,
                allow_headless=allow_headless,
                browser_profile=browser_profile,
                browser=browser_choice,
            )
            status_map = {
                HeadlessReauthStatus.SUCCESS: HeadlessRungStatus.SUCCEEDED,
                HeadlessReauthStatus.UNAVAILABLE: HeadlessRungStatus.UNAVAILABLE,
                HeadlessReauthStatus.FAILED: HeadlessRungStatus.FAILED,
            }
            logger.info("notebooklm_headless_reauth_result status=%s reason=%s", res.status, res.reason)
            return HeadlessRungOutcome(status_map.get(res.status, HeadlessRungStatus.FAILED), res.reason)

        install_headless_rung(_rung)
        os.environ["NOTEBOOKLM_HEADLESS_REAUTH"] = "1"
    except Exception as exc:
        logger.warning("notebooklm_headless_rung_install_failed error=%s", exc)


class NotebookLMAuthExpiredError(NotebookLMError):
    """Raised when Google NotebookLM authentication has expired or is invalid."""


@dataclass
class CitationReference:
    source_id: str
    citation_number: int | None
    cited_text: str | None


@dataclass
class GroundedChatResult:
    answer: str
    citations: list[CitationReference] = field(default_factory=list)
    conversation_id: str | None = None


class NotebookLMService:
    def __init__(self, data_dir: Path, default_timeout: float = 45.0) -> None:
        self.data_dir = data_dir
        self.browser_profile_dir = data_dir / "browser_profile"
        self.browser_profile_dir.mkdir(parents=True, exist_ok=True)
        self.storage_file = data_dir / "storage_state.json"
        self.legacy_auth_file = data_dir / "auth_state.json"
        self.master_token_file = data_dir / "master_token.json"
        self.auth_file = self.storage_file if self.storage_file.exists() else self.legacy_auth_file
        self.default_timeout = default_timeout
        self._notebook_locks: dict[str, asyncio.Lock] = {}
        self._global_lock = asyncio.Lock()
        self._cached_notebooks: list[dict[str, Any]] | None = None
        self._cached_at: float = 0.0
        self._cache_ttl: float = 300.0

        self._sync_storage_files()
        install_custom_headless_rung(self.browser_profile_dir)

    def _sync_storage_files(self) -> Path:
        """Keep storage_state.json and legacy auth_state.json in sync, adopting default profile tokens if present."""
        # 1. Sync from default CLI profile location if data_dir is empty
        default_cli_dir = Path.home() / ".notebooklm" / "profiles" / "default"
        if not self.master_token_file.exists() and (default_cli_dir / "master_token.json").exists():
            try:
                shutil.copy2(default_cli_dir / "master_token.json", self.master_token_file)
                logger.info("notebooklm_synced_master_token_from_default_profile")
            except Exception as exc:
                logger.warning("notebooklm_sync_master_token_failed error=%s", exc)

        if not self.storage_file.exists() and (default_cli_dir / "storage_state.json").exists():
            try:
                shutil.copy2(default_cli_dir / "storage_state.json", self.storage_file)
                logger.info("notebooklm_synced_storage_state_from_default_profile")
            except Exception as exc:
                logger.warning("notebooklm_sync_storage_state_failed error=%s", exc)

        # 2. Keep storage_state.json and legacy auth_state.json in sync
        if self.storage_file.exists() and not self.legacy_auth_file.exists():
            try:
                shutil.copy2(self.storage_file, self.legacy_auth_file)
            except Exception:
                pass
        elif self.legacy_auth_file.exists() and not self.storage_file.exists():
            try:
                shutil.copy2(self.legacy_auth_file, self.storage_file)
            except Exception:
                pass
        self.auth_file = self.storage_file if self.storage_file.exists() else self.legacy_auth_file
        return self.auth_file

    def _get_lock(self, notebook_id: str) -> asyncio.Lock:
        if notebook_id not in self._notebook_locks:
            self._notebook_locks[notebook_id] = asyncio.Lock()
        return self._notebook_locks[notebook_id]

    def _client(self, timeout: float):
        """Construct a NotebookLMClient context manager with configured timeout and headless reauth enabled."""
        active_file = self._sync_storage_files()
        if (
            ClientConfig is not None
            and RuntimeOptions is not None
            and WebBackendConfig is not None
            and WebSessionOptions is not None
        ):
            cfg = ClientConfig(
                runtime=RuntimeOptions(operation_timeout=timeout),
                backend=WebBackendConfig(session=WebSessionOptions(keepalive_interval=300.0)),
            )
            return NotebookLMClient.from_storage(
                path=str(active_file),
                config=cfg,
                allow_headless=True,
            )
        return NotebookLMClient.from_storage(
            path=str(active_file),
            timeout=timeout,
            allow_headless=True,
            keepalive=300.0,
        )

    @staticmethod
    def _parse_citations(references: Any) -> list[CitationReference]:
        """Extract structured CitationReference items from chat references."""
        citations: list[CitationReference] = []
        if references:
            for ref in references:
                citations.append(CitationReference(
                    source_id=getattr(ref, "source_id", ""),
                    citation_number=getattr(ref, "citation_number", None),
                    cited_text=getattr(ref, "cited_text", None),
                ))
        return citations

    def has_master_token(self) -> bool:
        """Check if durable Google master token exists for unattended authentication."""
        return self.master_token_file.is_file() and self.master_token_file.stat().st_size > 10

    def has_auth(self) -> bool:
        """Check if auth storage file exists and has content, or master token exists."""
        self._sync_storage_files()
        if self.has_master_token():
            return True
        for f in (self.storage_file, self.legacy_auth_file):
            if f.is_file() and f.stat().st_size > 10:
                return True
        return False

    async def auto_heal(self) -> bool:
        """Silently re-mint cookies using master token or persistent browser profile."""
        # 1. Master token headless minting (preferred for unattended server)
        if self.has_master_token():
            try:
                from notebooklm._auth.master_token import remint_from_stored_token, bootstrap_storage_from_master_token
                logger.info("notebooklm_auto_heal_via_master_token path=%s", self.master_token_file)
                if not self.storage_file.exists() or self.storage_file.stat().st_size <= 10:
                    await bootstrap_storage_from_master_token(self.storage_file)
                else:
                    await remint_from_stored_token(self.storage_file)
                self._sync_storage_files()
                logger.info("notebooklm_auto_heal_master_token_succeeded")
                return True
            except Exception as exc:
                logger.warning("notebooklm_auto_heal_master_token_failed error=%s", exc)

        # 2. Browser profile headless reauth (fallback if master token not configured)
        target_storage = self.storage_file
        browser_choice = get_browser_choice()
        try:
            from notebooklm._browser.headless_reauth import (
                HeadlessReauthStatus,
                attempt_headless_reauth,
            )
            res = await asyncio.to_thread(
                attempt_headless_reauth,
                storage_path=target_storage,
                allow_headless=True,
                browser_profile=self.browser_profile_dir,
                browser=browser_choice,
            )
            if res.status == HeadlessReauthStatus.SUCCESS:
                logger.info("notebooklm_auto_heal_succeeded")
                self._sync_storage_files()
                return True
            else:
                logger.warning("notebooklm_auto_heal_failed status=%s reason=%s", res.status, res.reason)
                return False
        except Exception as exc:
            logger.error("notebooklm_auto_heal_exception error=%s", exc)
            return False

    async def _execute_with_retry(
        self,
        op_name: str,
        timeout: float,
        action: Any,
    ) -> Any:
        """Execute an operation against NotebookLMClient with automated headless re-auth retry on AuthError."""
        try:
            async with asyncio.timeout(timeout):
                async with self._client(timeout=timeout) as client:
                    res = action(client)
                    return await res if asyncio.iscoroutine(res) else res
        except AuthError as exc:
            logger.warning("notebooklm_auth_expired in %s error=%s, attempting auto-heal...", op_name, exc)
            self._cached_notebooks = None
            if await self.auto_heal():
                try:
                    async with asyncio.timeout(timeout):
                        async with self._client(timeout=timeout) as client:
                            res = action(client)
                            return await res if asyncio.iscoroutine(res) else res
                except Exception as retry_exc:
                    logger.warning("notebooklm_%s_retry_failed error=%s", op_name, retry_exc)
            raise NotebookLMAuthExpiredError("Google NotebookLM authentication expired") from exc

    async def check_auth_status(self) -> dict[str, Any]:
        """Validate auth state and account status with automatic headless recovery."""
        self._sync_storage_files()
        if not self.has_auth():
            if self.browser_profile_dir.exists() and (self.browser_profile_dir / "Default").exists():
                logger.info("notebooklm_auth_missing_attempting_auto_heal")
                healed = await self.auto_heal()
                if not healed or not self.has_auth():
                    return {
                        "authenticated": False,
                        "reason": "auth_file_missing",
                        "has_master_token": False,
                        "requires_rebootstrap": True,
                    }
            else:
                return {
                    "authenticated": False,
                    "reason": "auth_file_missing",
                    "has_master_token": False,
                    "requires_rebootstrap": True,
                }

        # If master token exists but storage file does not, attempt bootstrap
        if self.has_master_token() and (not self.storage_file.exists() or self.storage_file.stat().st_size <= 10):
            logger.info("notebooklm_storage_missing_bootstrapping_from_master_token")
            await self.auto_heal()

        try:
            async def _get_email(client: Any) -> str | None:
                acc_email = getattr(client, "get_account_email", None)
                if callable(acc_email):
                    res = acc_email()
                    return await res if asyncio.iscoroutine(res) else res
                return None

            email = await self._execute_with_retry("check_auth_status", timeout=12.0, action=_get_email)
            return {
                "authenticated": True,
                "email": email or "unknown",
                "has_master_token": self.has_master_token(),
                "auth_method": "master_token" if self.has_master_token() else "cookies",
                "requires_rebootstrap": False,
            }
        except (AuthError, NotebookLMAuthExpiredError, NotebookLMError, TimeoutError, Exception) as exc:
            reason = "AuthError" if isinstance(exc, (AuthError, NotebookLMAuthExpiredError)) else type(exc).__name__
            logger.warning("notebooklm_auth_validation_failed error=%s", type(exc).__name__)
            return {
                "authenticated": False,
                "reason": reason,
                "has_master_token": self.has_master_token(),
                "requires_rebootstrap": not self.has_master_token() or reason == "AuthError",
            }

    async def keepalive_tick(self) -> None:
        """Periodic background keepalive to rotate and refresh SIDTS cookies."""
        if not self.has_auth():
            return
        try:
            async with asyncio.timeout(15.0):
                async with self._client(timeout=15.0) as client:
                    if hasattr(client, "refresh_auth"):
                        await client.refresh_auth(allow_headless=True)
                        self._sync_storage_files()
                        logger.debug("notebooklm_keepalive_tick_succeeded")
        except Exception as exc:
            logger.debug("notebooklm_keepalive_tick_failed error=%s", exc)

    async def list_notebooks(self, force_refresh: bool = False) -> list[dict[str, Any]]:
        """List cloud notebooks available in user's account with 5-minute memory cache."""
        if not self.has_auth():
            return []
        now = time.time()
        if not force_refresh and self._cached_notebooks is not None and (now - self._cached_at) < self._cache_ttl:
            return self._cached_notebooks
        try:
            async def _fetch(client: Any) -> list[dict[str, Any]]:
                notebooks = await client.notebooks.list()
                return [{"id": nb.id, "title": nb.title} for nb in notebooks]

            res = await self._execute_with_retry("list_notebooks", timeout=10.0, action=_fetch)
            self._cached_notebooks = res
            self._cached_at = now
            return res
        except NotebookLMAuthExpiredError:
            self._cached_notebooks = None
            raise
        except Exception as exc:
            logger.warning("notebooklm_list_failed error=%s", type(exc).__name__)
            return self._cached_notebooks if self._cached_notebooks is not None else []

    async def find_notebook(self, query_or_title: str) -> dict[str, Any] | None:
        """
        Find a notebook matching query_or_title by exact id, exact title, prefix, or substring.
        Uses in-memory cached notebooks.
        """
        cleaned = query_or_title.strip()
        if not cleaned:
            return None
        notebooks = await self.list_notebooks()
        if not notebooks:
            return None

        # 1. Exact ID match
        for nb in notebooks:
            if nb.get("id") == cleaned:
                return nb

        target_lower = cleaned.lower()

        # 2. Exact Title match (case-insensitive)
        for nb in notebooks:
            if nb.get("title", "").strip().lower() == target_lower:
                return nb

        # 3. Title starts with target_lower (e.g. 'Reason' matches 'Reason is fun')
        prefix_matches = [
            nb for nb in notebooks
            if nb.get("title", "").strip().lower().startswith(target_lower)
        ]
        if prefix_matches:
            prefix_matches.sort(key=lambda x: len(x.get("title", "")))
            return prefix_matches[0]

        # 4. Substring match in title
        sub_matches = [
            nb for nb in notebooks
            if target_lower in nb.get("title", "").strip().lower()
        ]
        if sub_matches:
            sub_matches.sort(key=lambda x: len(x.get("title", "")))
            return sub_matches[0]

        return None

    async def create_notebook(self, title: str) -> str | None:
        """Create a new cloud notebook."""
        if not self.has_auth():
            return None
        try:
            async with asyncio.timeout(10.0):
                async with self._client(timeout=10.0) as client:
                    nb = await client.notebooks.create(title)
                    self._cached_notebooks = None
                    return nb.id
        except Exception as exc:
            logger.error("notebooklm_create_failed title=%s error=%s", title, type(exc).__name__)
            return None

    async def add_source_file(self, notebook_id: str, file_path: Path) -> str | None:
        """Upload a local document source to the cloud notebook."""
        if not self.has_auth() or not file_path.is_file():
            return None
        lock = self._get_lock(notebook_id)
        async with lock:
            try:
                async with asyncio.timeout(30.0):
                    async with self._client(timeout=30.0) as client:
                        source = await client.sources.add_file(notebook_id, str(file_path))
                        return getattr(source, "id", str(source))
            except Exception as exc:
                logger.error("notebooklm_upload_source_failed notebook_id=%s error=%s", notebook_id, type(exc).__name__)
                return None

    async def ask_chat(self, notebook_id: str, question: str, timeout: float | None = None) -> GroundedChatResult | None:
        """
        Execute a grounded query against a notebook with a strict circuit breaker timeout.
        Returns GroundedChatResult on success, or None on failure/timeout to trigger graceful fallback.
        """
        if not self.has_auth():
            logger.info("notebooklm_ask_skipped reason=unauthenticated")
            return None

        effective_timeout = timeout or self.default_timeout
        lock = self._get_lock(notebook_id)
        async with lock:
            try:
                async def _ask(client: Any) -> GroundedChatResult:
                    result = await client.chat.ask(notebook_id, question)
                    return GroundedChatResult(
                        answer=result.answer,
                        citations=self._parse_citations(getattr(result, "references", None)),
                        conversation_id=getattr(result, "conversation_id", None),
                    )

                return await self._execute_with_retry("ask_chat", timeout=effective_timeout, action=_ask)
            except TimeoutError:
                logger.warning("notebooklm_circuit_breaker_triggered reason=timeout timeout_s=%.1f", effective_timeout)
                return None
            except NotebookLMAuthExpiredError:
                logger.warning("notebooklm_circuit_breaker_triggered reason=auth_expired")
                raise
            except (RateLimitError, RPCError, RPCTimeoutError, NotebookLMError, Exception) as exc:
                logger.warning("notebooklm_circuit_breaker_triggered reason=rpc_failure error=%s", type(exc).__name__)
                return None

    async def get_history(self, notebook_id: str, limit: int = 50) -> list[tuple[str, str]]:
        """Fetch past conversation turns for a notebook using Google internal RPCs."""
        if not self.has_auth():
            return []
        try:
            return await self._execute_with_retry(
                "get_history",
                timeout=15.0,
                action=lambda client: client.chat.get_history(notebook_id, limit=limit),
            )
        except NotebookLMAuthExpiredError:
            raise
        except (RateLimitError, RPCError, RPCTimeoutError, NotebookLMError, Exception) as exc:
            logger.warning("notebooklm_get_history_failed notebook=%s error=%s", notebook_id, type(exc).__name__)
            return []

    def reset_auth(self, clear_browser_profile: bool = False, clear_master_token: bool = False) -> bool:
        """Deletes storage files and clears in-memory caches."""
        self._cached_notebooks = None
        self._cached_at = 0.0
        self._notebook_locks.clear()
        success = True
        for f in (self.storage_file, self.legacy_auth_file):
            if f.exists():
                try:
                    f.unlink()
                except OSError as exc:
                    logger.warning("notebooklm_auth_reset_failed file=%s error=%s", f, exc)
                    success = False
        if clear_master_token and self.master_token_file.exists():
            try:
                self.master_token_file.unlink()
            except OSError as exc:
                logger.warning("notebooklm_master_token_reset_failed file=%s error=%s", self.master_token_file, exc)
                success = False
        if clear_browser_profile and self.browser_profile_dir.exists():
            shutil.rmtree(self.browser_profile_dir, ignore_errors=True)
        return success
