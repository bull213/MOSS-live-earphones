import asyncio
from unittest.mock import patch, MagicMock
from app.adb_watcher import find_adb_executable, _sync_ensure_reverse, run_adb_watcher


def test_find_adb_executable():
    path = find_adb_executable()
    # Path might be None or a string, but shouldn't raise exception
    assert path is None or isinstance(path, str)


@patch("subprocess.run")
def test_sync_ensure_reverse_success(mock_run):
    mock_run.side_effect = [
        MagicMock(returncode=0, stdout="List of devices attached\nRZCY124M16W\tdevice\n"),
        MagicMock(returncode=0, stdout=""),
    ]
    result = _sync_ensure_reverse("adb", 8000)
    assert result is True
    assert mock_run.call_count == 2


@patch("subprocess.run")
def test_sync_ensure_reverse_no_devices(mock_run):
    mock_run.return_value = MagicMock(returncode=0, stdout="List of devices attached\n\n")
    result = _sync_ensure_reverse("adb", 8000)
    assert result is False
    assert mock_run.call_count == 1


import pytest

@pytest.mark.anyio
async def test_run_adb_watcher_cancellation():
    with patch("app.adb_watcher.find_adb_executable", return_value="adb"), \
         patch("app.adb_watcher._sync_ensure_reverse", return_value=True):
        task = asyncio.create_task(run_adb_watcher(poll_interval_seconds=0.05, port=8000))
        await asyncio.sleep(0.1)
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
