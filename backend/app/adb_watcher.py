"""Background monitor that automatically maintains ADB reverse port forwarding when an Android device is connected."""

from __future__ import annotations

import asyncio
import os
import shutil
from pathlib import Path
import logging

logger = logging.getLogger("app.adb_watcher")

_REPO_ROOT = Path(__file__).resolve().parent.parent.parent


def find_adb_executable() -> str | None:
    """Find adb executable across bundled SDK, local AppData, or PATH."""
    candidates = [
        _REPO_ROOT / "android" / ".tools" / "sdk" / "platform-tools" / "adb.exe",
        _REPO_ROOT / "android" / ".tools" / "sdk" / "platform-tools" / "adb",
    ]
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidates.append(Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe")

    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if android_home:
        candidates.append(Path(android_home) / "platform-tools" / "adb.exe")
        candidates.append(Path(android_home) / "platform-tools" / "adb")

    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)

    return shutil.which("adb")


def _sync_ensure_reverse(adb_path: str, port: int = 8000) -> bool:
    """Check for connected authorized devices and ensure reverse port forward is applied."""
    import subprocess

    try:
        startupinfo = None
        if os.name == "nt":
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW

        result = subprocess.run(
            [adb_path, "devices"],
            capture_output=True,
            text=True,
            timeout=3,
            startupinfo=startupinfo,
        )
        if result.returncode != 0:
            return False

        lines = result.stdout.strip().splitlines()
        has_device = False
        for line in lines[1:]:
            parts = line.strip().split()
            if len(parts) >= 2 and parts[1] == "device":
                has_device = True
                break

        if not has_device:
            return False

        # Apply reverse port forwarding
        subprocess.run(
            [adb_path, "reverse", f"tcp:{port}", f"tcp:{port}"],
            capture_output=True,
            timeout=3,
            startupinfo=startupinfo,
        )
        return True
    except Exception as e:
        logger.debug("ADB check/reverse failed: %s", e)
        return False


async def run_adb_watcher(poll_interval_seconds: float = 5.0, port: int = 8000) -> None:
    """Periodically check for connected Android devices and maintain reverse port forwarding."""
    adb_path = find_adb_executable()
    if not adb_path:
        logger.debug("ADB executable not found; skipping automatic ADB reverse watcher.")
        return

    logger.info("ADB watcher started using: %s (forwarding port %d)", adb_path, port)
    while True:
        try:
            await asyncio.to_thread(_sync_ensure_reverse, adb_path, port)
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.debug("Error in ADB watcher loop: %s", e)
        await asyncio.sleep(poll_interval_seconds)
