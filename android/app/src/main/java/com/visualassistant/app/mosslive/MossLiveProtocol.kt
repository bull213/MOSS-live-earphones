package com.visualassistant.app.mosslive

import org.json.JSONObject

internal data class MossResponseId(val runId: String, val turnId: String) {
    // Length-prefix both opaque provider IDs so neither can collide with the other.
    val key: String get() = "${runId.length}:$runId${turnId.length}:$turnId"
}

internal data class MossAudioContext(val runId: String, val turnId: String, val chunkId: String) {
    val responseId: MossResponseId get() = MossResponseId(runId, turnId)
    val responseKey: String get() = responseId.key
}

internal data class MossProtocolEvent(val action: String, val data: JSONObject)

internal object MossLiveProtocol {
    fun parseEvent(raw: String): MossProtocolEvent {
        val event = JSONObject(raw)
        val action = event.optString("action")
        require(action.isNotBlank()) { "Missing event action" }
        return MossProtocolEvent(action, event.optJSONObject("data") ?: JSONObject())
    }

    fun audioContext(data: JSONObject): MossAudioContext? {
        val runId = data.optString("run_id").takeIf(String::isNotBlank) ?: return null
        val turnId = data.optString("turn_id").takeIf(String::isNotBlank) ?: return null
        val chunkId = data.optString("chunk_id").takeIf(String::isNotBlank) ?: return null
        return MossAudioContext(runId, turnId, chunkId)
    }

    fun responseId(data: JSONObject): MossResponseId? {
        val runId = data.optString("run_id").takeIf(String::isNotBlank) ?: return null
        val turnId = data.optString("turn_id").takeIf(String::isNotBlank) ?: return null
        return MossResponseId(runId, turnId)
    }

    fun micState(enabled: Boolean): String =
        JSONObject().put("action", "mic_state").put("enabled", enabled).toString()

    fun mediaMode(): String = """{"action":"media.mode","binary_protocol":"tagged-v1"}"""

    fun appendInstructions(content: String): String = JSONObject()
        .put("action", "session.instructions.append").put("content", content).toString()

    fun videoState(enabled: Boolean): String = JSONObject()
        .put("action", "video.state").put("source", "camera").put("enabled", enabled).toString()

    fun cancelResponse(): String = JSONObject().put("action", "response.cancel").toString()

    fun taggedCameraFrame(jpeg: ByteArray): ByteArray = byteArrayOf(CAMERA_TAG) + jpeg

    fun taggedAudio(pcm: ByteArray): ByteArray =
        byteArrayOf(AUDIO_TAG) + pcm

    fun completedChunk(context: MossAudioContext): String =
        JSONObject().put("action", "playback.chunk.completed")
            .put("run_id", context.runId).put("turn_id", context.turnId).put("chunk_id", context.chunkId).toString()

    fun completedResponse(runId: String, turnId: String): String =
        JSONObject().put("action", "playback.completed").put("run_id", runId).put("turn_id", turnId).toString()

    fun stoppedResponse(runId: String, turnId: String, playedAudioMs: Long): String =
        JSONObject().put("action", "playback.stopped").put("run_id", runId)
            .put("turn_id", turnId).put("played_audio_ms", playedAudioMs).toString()

    fun pcm16Le(samples: ShortArray, count: Int): ByteArray =
        ByteArray(count * 2).also { bytes ->
            for (index in 0 until count) {
                val sample = samples[index].toInt()
                bytes[index * 2] = sample.toByte()
                bytes[index * 2 + 1] = (sample shr 8).toByte()
            }

        }

    private const val AUDIO_TAG: Byte = 0x00
    private const val CAMERA_TAG: Byte = 0x01
}
