package com.visualassistant.app.mosslive

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MossLiveProtocolTest {
    @Test
    fun parsesMossServerEventAndAudioCorrelation() {
        val event = MossLiveProtocol.parseEvent(
            """{"action":"response.audio.context","data":{"run_id":"run","turn_id":"turn","chunk_id":"chunk"}}""",
        )
        assertEquals("response.audio.context", event.action)
        val context = MossLiveProtocol.audioContext(event.data)
        assertNotNull(context)
        assertEquals("3:run4:turn", context?.responseKey)
        assertEquals("chunk", context?.chunkId)
    }

    @Test
    fun rejectsMalformedAudioCorrelationAndEventNames() {
        assertNull(MossLiveProtocol.audioContext(JSONObject("""{"run_id":"run","turn_id":"turn"}""")))
        assertThrows(IllegalArgumentException::class.java) {
            MossLiveProtocol.parseEvent("""{"data":{}}""")
        }
    }

    @Test
    fun emitsPlaybackAcknowledgementsWithProtocolCorrelation() {
        val context = MossAudioContext("run", "turn", "chunk")
        val chunk = JSONObject(MossLiveProtocol.completedChunk(context))
        assertEquals("playback.chunk.completed", chunk.getString("action"))
        assertEquals("chunk", chunk.getString("chunk_id"))

        val response = JSONObject(MossLiveProtocol.completedResponse("run", "turn"))
        assertEquals("playback.completed", response.getString("action"))
        assertEquals("turn", response.getString("turn_id"))

        val stopped = JSONObject(MossLiveProtocol.stoppedResponse("run", "turn", 12))
        assertEquals("playback.stopped", stopped.getString("action"))
        assertEquals(12, stopped.getLong("played_audio_ms"))
        assertEquals("run", stopped.getString("run_id"))
        assertEquals("turn", stopped.getString("turn_id"))
        assertNull(stopped.optString("chunk_id").takeIf(String::isNotBlank))
    }
}
