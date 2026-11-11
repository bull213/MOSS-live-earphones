package com.visualassistant.app.voice

import org.junit.Assert.*
import org.junit.Test

class UtteranceBufferTest {
    private fun chunk() = AudioChunk(FloatArray(1600), 16000)

    @Test fun longSpeechAndShortPausesRemainOneUtterance() {
        val buffer = UtteranceBuffer()
        repeat(400) { assertNull(buffer.accept(chunk(), true)) }
        repeat(10) { assertNull(buffer.accept(chunk(), false)) }
        repeat(200) { assertNull(buffer.accept(chunk(), true)) }
        repeat(19) { assertNull(buffer.accept(chunk(), false)) }
        val result = buffer.accept(chunk(), false)
        assertEquals(630, result!!.size)
        assertNull(buffer.flush())
    }

    @Test fun silenceSeparatesUtterancesAndStopFlushesTheLastOne() {
        val buffer = UtteranceBuffer()
        repeat(5) { buffer.accept(chunk(), false) }
        buffer.accept(chunk(), true)
        repeat(19) { assertNull(buffer.accept(chunk(), false)) }
        assertNotNull(buffer.accept(chunk(), false))
        buffer.accept(chunk(), true)
        assertNotNull(buffer.flush())
        assertNull(buffer.flush())
    }

}
