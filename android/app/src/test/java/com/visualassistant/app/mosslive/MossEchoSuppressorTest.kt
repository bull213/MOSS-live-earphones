package com.visualassistant.app.mosslive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MossEchoSuppressorTest {
    @Test
    fun suppressesPcmWhenAssistantSpeakingOnSpeakerRoute() = runTest {
        val diagnostics = MossLiveDiagnostics()
        val suppressor = MossEchoSuppressor(
            audioManager = null,
            diagnostics = diagnostics,
            isEarphonesActiveProvider = { false },
        )

        val input = byteArrayOf(10, 20, 30, 40)

        // 1. User speaking before assistant responds: transmitted unaltered
        assertArrayEquals(input, suppressor.processUplinkPcm(input))
        assertFalse(suppressor.isSpeaking)

        // 2. Assistant starts speaking: speaker echo suppressed with zeroed PCM
        suppressor.onAssistantSpeechStarted()
        assertTrue(suppressor.isSpeaking)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), suppressor.processUplinkPcm(input))
        assertTrue(diagnostics.summary().contains("echo_suppressed_frames=1"))
    }

    @Test
    fun restoresLiveMicAfterRoomDecayGraceDelay() = runTest {
        val suppressor = MossEchoSuppressor(
            audioManager = null,
            isEarphonesActiveProvider = { false },
            roomDecayGraceMs = 200L,
        )

        val input = byteArrayOf(1, 2, 3, 4)
        suppressor.onAssistantSpeechStarted()
        assertTrue(suppressor.isSpeaking)

        // Playback finished, waiting for room decay
        suppressor.onAssistantPlaybackDrained(this)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(suppressor.isSpeaking)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), suppressor.processUplinkPcm(input))

        // After room decay period (200ms), live microphone streaming resumes
        advanceTimeBy(150)
        runCurrent()
        assertFalse(suppressor.isSpeaking)
        assertArrayEquals(input, suppressor.processUplinkPcm(input))
    }

    @Test
    fun bypassesSuppressionWhenEarphonesAreConnected() = runTest {
        val suppressor = MossEchoSuppressor(
            audioManager = null,
            isEarphonesActiveProvider = { true },
        )

        val input = byteArrayOf(11, 22, 33, 44)
        suppressor.onAssistantSpeechStarted()
        assertTrue(suppressor.isSpeaking)

        // Earphone isolation allows natural full-duplex interruption
        assertArrayEquals(input, suppressor.processUplinkPcm(input))
    }

    @Test
    fun resetsImmediatelyOnInterruptionOrStop() = runTest {
        val suppressor = MossEchoSuppressor(
            audioManager = null,
            isEarphonesActiveProvider = { false },
        )

        val input = byteArrayOf(5, 6, 7, 8)
        suppressor.onAssistantSpeechStarted()
        assertTrue(suppressor.isSpeaking)

        suppressor.onAssistantInterruptedOrStopped()
        assertFalse(suppressor.isSpeaking)
        assertArrayEquals(input, suppressor.processUplinkPcm(input))
    }
}
