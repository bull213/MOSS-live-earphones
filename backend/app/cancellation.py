"""Stop a live request promptly when its client disconnects."""

import asyncio
from collections.abc import Awaitable
from typing import TypeVar

from fastapi import Request

from app.errors import AssistantError


T = TypeVar("T")


async def run_until_disconnect(request: Request, operation: Awaitable[T]) -> T:
    async def watch() -> None:
        while not await request.is_disconnected():
            await asyncio.sleep(0.1)

    task = asyncio.ensure_future(operation)
    watcher = asyncio.create_task(watch())
    try:
        done, _ = await asyncio.wait({task, watcher}, return_when=asyncio.FIRST_COMPLETED)
        if task in done:
            return await task
        await watcher
        raise AssistantError(499, "client_disconnected", "The client disconnected before the request finished.")
    finally:
        task.cancel()
        watcher.cancel()
        await asyncio.gather(task, watcher, return_exceptions=True)
