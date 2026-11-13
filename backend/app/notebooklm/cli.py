"""Command-line utility for NotebookLM Google authentication bootstrap."""

from __future__ import annotations

import argparse
import asyncio
from pathlib import Path
import shutil
import sys

try:
    import truststore
    truststore.inject_into_ssl()
except Exception:
    pass

from app.config import Settings
from app.notebooklm.client import NotebookLMService


def get_data_dir() -> Path:
    settings = Settings()
    settings.notebooklm_data_dir.mkdir(parents=True, exist_ok=True)
    return settings.notebooklm_data_dir


def cmd_login(args: argparse.Namespace) -> int:
    """Launch browser to authenticate with Google and save session cookies."""
    data_dir = get_data_dir()
    storage_file = data_dir / "storage_state.json"
    auth_file = data_dir / "auth_state.json"
    profile_dir = data_dir / "browser_profile"

    if getattr(args, "fresh", False) and profile_dir.exists():
        print("Cleaning previous browser profile...")
        shutil.rmtree(profile_dir, ignore_errors=True)

    profile_dir.mkdir(parents=True, exist_ok=True)

    try:
        from notebooklm.auth import run_browser_login_capture
    except ImportError:
        print("Error: Playwright is not installed. Please run: pip install 'notebooklm-py[browser]'", file=sys.stderr)
        return 1

    browser_choice = args.browser
    if browser_choice == "auto":
        from app.notebooklm.client import get_browser_choice
        browser_choice = get_browser_choice()

    print(f"Launching {browser_choice.upper()} browser for Google sign-in...")
    print(f"Persistent browser profile: {profile_dir}")
    print(f"Session cookies will be stored securely in: {storage_file}")
    print("Please complete sign-in in the opened browser window.")

    def emit_msg(*a: object, **k: object) -> None:
        pass

    def fail_msg(code: int) -> None:
        sys.exit(code)

    try:
        run_browser_login_capture(
            browser=browser_choice,
            browser_profile=profile_dir,
            storage_path=storage_file,
            include_domains=None,
            login_timeout_s=args.timeout,
            emit=emit_msg,
            fail=fail_msg,
        )
        try:
            shutil.copy2(storage_file, auth_file)
        except Exception:
            pass
        print("Authentication successful! Google account session remembered.")
        return 0
    except Exception as exc:
        print(f"Authentication failed: {exc}", file=sys.stderr)
        return 1


def cmd_status(args: argparse.Namespace) -> int:
    """Check current authentication status and account."""
    data_dir = get_data_dir()
    service = NotebookLMService(data_dir)

    async def _check() -> int:
        status = await service.check_auth_status()
        if status.get("authenticated"):
            print(f"Status: AUTHENTICATED (Account: {status.get('email', 'unknown')})")
            return 0
        else:
            print(f"Status: UNAUTHENTICATED (Reason: {status.get('reason', 'unknown')})")
            print("Run 'python -m app.notebooklm.cli login' to authenticate.")
            return 1

    return asyncio.run(_check())


def main() -> None:
    parser = argparse.ArgumentParser(description="NotebookLM Google Authentication Helper")
    subparsers = parser.add_subparsers(dest="command", required=True)

    login_p = subparsers.add_parser("login", help="Open browser to sign in to Google")
    login_p.add_argument("--browser", choices=["auto", "chrome", "msedge", "chromium"], default="auto", help="Browser channel to use (default: auto)")
    login_p.add_argument("--timeout", type=int, default=180, help="Login timeout in seconds (default: 180)")
    login_p.add_argument("--fresh", action="store_true", help="Start with a clean browser profile")
    login_p.set_defaults(func=cmd_login)

    status_p = subparsers.add_parser("status", help="Check authentication status")
    status_p.set_defaults(func=cmd_status)

    args = parser.parse_args()
    code = args.func(args)
    sys.exit(code)


if __name__ == "__main__":
    main()
