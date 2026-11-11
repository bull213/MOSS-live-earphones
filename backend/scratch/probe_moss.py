import asyncio
import json
import urllib.parse
from websockets.asyncio.client import connect
from app.config import Settings

async def probe():
    settings = Settings()
    url = f"wss://api.mosi.cn/v1/realtime?model={urllib.parse.quote(settings.moss_model, safe='')}"
    headers = {"Authorization": f"Bearer {settings.moss_api_key.get_secret_value()}"}
    async with connect(url, additional_headers=headers) as ws:
        await ws.send(json.dumps({"action": "attach_session", "session_id": None}))
        print("attach_session ->", await ws.recv())
        
        await ws.send(json.dumps({"action": "session.update", "session": {"instructions": "You are a helpful assistant."}}))
        print("session.update ->", await ws.recv())
        
        await ws.send(json.dumps({"action": "media.mode", "binary_protocol": "tagged-v1"}))
        await ws.send(json.dumps({"action": "video.state", "source": "camera", "enabled": True}))
        
        # Send tagged dummy JPEG
        dummy_jpeg = b"\x01" + (b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9")
        await ws.send(dummy_jpeg)
        print("Sent tagged camera frame with video.state=true")
        
        # Wait 3 seconds to see if MOSS sends any error or event
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=3)
            print("Received event:", msg)
        except asyncio.TimeoutError:
            print("No events (normal, waiting for audio input)")

if __name__ == "__main__":
    asyncio.run(probe())
