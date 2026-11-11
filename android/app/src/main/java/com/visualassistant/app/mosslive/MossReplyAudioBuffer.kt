package com.visualassistant.app.mosslive

import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

/** Holds bounded PCM for replies until complete audio can be transcribed as a fallback. */
internal class MossReplyAudioBuffer(private val maxBytes: Int) {
    private class TurnBuffer {
        val pcm = ByteArrayOutputStream()
        var overflowed = false
    }

    private val turns = LinkedHashMap<String, TurnBuffer>()
    private var totalBytes = 0

    @Synchronized
    fun append(turnId: String, bytes: ByteArray) {
        val turn = turns.getOrPut(turnId) { TurnBuffer() }
        val remaining = minOf(maxBytes - turn.pcm.size(), maxBytes - totalBytes)
        if (bytes.size > remaining) {
            if (remaining > 0) {
                turn.pcm.write(bytes, 0, remaining)
                totalBytes += remaining
            }
            turn.overflowed = true
        } else {
            turn.pcm.write(bytes)
            totalBytes += bytes.size
        }
    }

    /** Returns bytes only when the entire reply stayed within the memory limit. */
    @Synchronized
    fun takeComplete(turnId: String): ByteArray? {
        val turn = turns.remove(turnId) ?: return null
        totalBytes -= turn.pcm.size()
        return if (turn.overflowed) null else turn.pcm.toByteArray()
    }

    @Synchronized
    fun discard(turnId: String) {
        turns.remove(turnId)?.let { totalBytes -= it.pcm.size() }
    }

    @Synchronized
    fun clear() {
        turns.clear()
        totalBytes = 0
    }
}
