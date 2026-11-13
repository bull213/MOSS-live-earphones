package com.visualassistant.app.mosslive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MossLiveProtocolTest {
    @Test
    fun convertsPcmSamplesToLittleEndianWithoutHeaders() {
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x00, 0x80.toByte(), 0xff.toByte(), 0x7f),
            MossLiveProtocol.pcm16Le(shortArrayOf(0x1234, Short.MIN_VALUE, Short.MAX_VALUE), 3))
    }

    @Test
    fun enablesTaggedBinaryProtocolBeforeAudioAndPrefixesUplinkFrames() {
        assertEquals(
            """{"action":"media.mode","binary_protocol":"tagged-v1"}""",
            MossLiveProtocol.mediaMode(),
        )
        val pcm = byteArrayOf(0x34, 0x12, 0x56, 0x78)
        assertArrayEquals(byteArrayOf(0x00, 0x34, 0x12, 0x56, 0x78), MossLiveProtocol.taggedAudio(pcm))
    }

    @Test
    fun playbackStoppedUsesDocumentedResponseFieldsOnly() {
        val message = org.json.JSONObject(MossLiveProtocol.stoppedResponse("run-1", "turn-1", 24))
        assertEquals(setOf("action", "run_id", "turn_id", "played_audio_ms"),
            message.keys().asSequence().toSet())
        assertEquals("playback.stopped", message.getString("action"))
        assertEquals("run-1", message.getString("run_id"))
        assertEquals("turn-1", message.getString("turn_id"))
        assertEquals(24, message.getLong("played_audio_ms"))
    }

    @Test
    fun responseIdentityIncludesBothOpaqueProviderIds() {
        val first = MossResponseId("run-a", "turn-shared")
        val second = MossResponseId("run-b", "turn-shared")
        assertEquals("run-a", MossLiveProtocol.responseId(
            org.json.JSONObject().put("run_id", "run-a").put("turn_id", "turn-shared"),
        )?.runId)
        org.junit.Assert.assertNotEquals(first.key, second.key)
    }

    @Test
    fun cancelResponseProducesValidActionEnvelope() {
        val payload = org.json.JSONObject(MossLiveProtocol.cancelResponse())
        assertEquals("response.cancel", payload.getString("action"))
    }
}
