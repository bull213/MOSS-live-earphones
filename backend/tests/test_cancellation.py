import asyncio

import pytest

from app.cancellation import run_until_disconnect
from app.errors import AssistantError


def test_disconnect_cancels_live_operation():
    async def scenario():
        started = asyncio.Event()
        closed = asyncio.Event()
        class Request:
            async def is_disconnected(self):
                return started.is_set()
        async def operation():
            started.set()
            try:
                await asyncio.sleep(10)
            finally:
                closed.set()
        with pytest.raises(AssistantError) as caught:
            await run_until_disconnect(Request(), operation())
        assert caught.value.code == "client_disconnected"
        assert closed.is_set()
    asyncio.run(scenario())
