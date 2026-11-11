package com.visualassistant.app.mosslive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MossAudioPlayerTest {
    @Test
    fun startsStreamingAsSoonAsAnyFrameIsBuffered() = runTest {
        val track = FakeTrack()
        val player = player(track, mutableListOf())
        player.start(backgroundScope)

        assertEquals(1, track.startThresholdFrames)
        assertTrue(track.startThresholdFrames < track.bufferCapacityFrames)
        player.stop()
    }

    @Test
    fun responseDoneRacingChunkAcknowledgementCannotOvertakeChunkAcknowledgement() = runTest {
        val track = FakeTrack()
        val controls = mutableListOf<String>()
        val context = context("run-race", "turn-race", "chunk-race")
        lateinit var player: MossAudioPlayer
        player = MossAudioPlayer(
            audioManager = null,
            sendControl = { control ->
                if (action(control) == "playback.chunk.completed") {
                    // Simulate response.audio.done arriving while the chunk control is in flight,
                    // before the transport reports that control accepted.
                    player.audioDone(context.runId, context.turnId)
                }
                controls += control
                true
            },
            onFailure = { error("unexpected playback failure") },
            playbackTrackFactory = MossPlaybackTrackFactory { track },
            playbackDispatcher = StandardTestDispatcher(testScheduler),
            logPlayback = { _, _ -> },
        )
        player.start(backgroundScope)
        player.enqueue(context, pcmFrames(1))
        advancePlayback()
        track.playbackHeadPosition = 1
        advancePlayback()

        assertEquals(
            listOf("playback.chunk.completed", "playback.completed"),
            controls.map(::action),
        )
        player.stop()
    }

    @Test
    fun acknowledgesActualChunkBeforeDrainedResponseWithoutWaitingForTranscript() = runTest {
        val track = FakeTrack()
        val controls = mutableListOf<String>()
        val player = player(track, controls)
        player.start(backgroundScope)
        val context = context("run-1", "turn-1", "chunk-1")
        player.enqueue(context, pcmFrames(2))

        runCurrent()
        assertTrue(track.acceptedBytes == 4)
        assertTrue(controls.isEmpty())

        // The provider's audio-done can race ahead of local playback completion.
        player.audioDone(context.runId, context.turnId)
        runCurrent()
        assertTrue("whole-response ack must wait for playback", controls.isEmpty())

        // No transcript event has been delivered. Actual playback progress is the gate.
        track.playbackHeadPosition = 2
        advancePlayback()
        assertEquals(
            listOf("playback.chunk.completed", "playback.completed"),
            controls.map(::action),
        )
        assertEquals(context.runId, field(controls[0], "run_id"))
        assertEquals(context.turnId, field(controls[0], "turn_id"))
        assertEquals(context.chunkId, field(controls[0], "chunk_id"))
        player.stop()
    }

    @Test
    fun waitsForTheWholeChunkAcrossPartialAudioTrackWrites() = runTest {
        val track = FakeTrack(maxWriteBytes = 2)
        val controls = mutableListOf<String>()
        val player = player(track, controls)
        player.start(backgroundScope)
        val context = context("run-1", "turn-1", "chunk-1")
        player.enqueue(context, pcmFrames(3))

        runCurrent()
        assertEquals(6, track.acceptedBytes)
        assertTrue(controls.isEmpty())

        track.playbackHeadPosition = 2
        advancePlayback()
        assertTrue("one frame short must not be acknowledged", controls.isEmpty())

        track.playbackHeadPosition = 3
        advancePlayback()
        assertEquals(listOf("playback.chunk.completed"), controls.map(::action))
        player.stop()
    }

    @Test
    fun interruptionDropsOnlyThatResponseAndKeepsTrackUsable() = runTest {
        val track = FakeTrack()
        val controls = mutableListOf<String>()
        val player = player(track, controls)
        player.start(backgroundScope)
        val interrupted = context("run-old", "turn-shared", "chunk-old")
        val next = context("run-new", "turn-shared", "chunk-new")
        player.enqueue(interrupted, pcmFrames(2))
        player.enqueue(next, pcmFrames(2))

        runCurrent()
        player.stopResponse(interrupted.runId, interrupted.turnId)
        assertEquals("playback.stopped", action(controls.single()))
        assertEquals("run-old", field(controls.single(), "run_id"))
        assertEquals("turn-shared", field(controls.single(), "turn_id"))
        assertEquals("0", field(controls.single(), "played_audio_ms"))
        assertFalse("undocumented chunk_id must not be sent", hasField(controls.single(), "chunk_id"))

        // Let the next response begin on the same track before simulating its playback.
        repeat(4) {
            advancePlayback()
            if (track.acceptedBytes >= 8) return@repeat
        }
        assertEquals(8, track.acceptedBytes)
        track.playbackHeadPosition = 2
        advancePlayback()
        assertEquals(
            listOf("playback.stopped", "playback.chunk.completed"),
            controls.map(::action),
        )
        player.audioDone(next.runId, next.turnId)
        assertEquals("playback.completed", action(controls.last()))
        player.stop()
    }

    @Test
    fun rejectedPlaybackControlFailsTheSessionAndNeverClaimsCompletion() = runTest {
        val track = FakeTrack()
        val controls = mutableListOf<String>()
        var failures = 0
        val player = MossAudioPlayer(
            audioManager = null,
            sendControl = { controls += it; false },
            onFailure = { failures++ },
            playbackTrackFactory = MossPlaybackTrackFactory { track },
            playbackDispatcher = StandardTestDispatcher(testScheduler),
            logPlayback = { _, _ -> },
        )
        player.start(backgroundScope)
        val context = context("run-1", "turn-1", "chunk-1")
        player.enqueue(context, pcmFrames(1))
        runCurrent()
        track.playbackHeadPosition = 1
        advancePlayback()

        assertEquals(1, failures)
        player.audioDone(context.runId, context.turnId)
        assertFalse(controls.any { action(it) == "playback.completed" })
        player.stop()
    }

    @Test
    fun rejectedMalformedAudioNeverProducesPlaybackAcknowledgement() = runTest {
        var failures = 0
        val controls = mutableListOf<String>()
        val player = MossAudioPlayer(
            audioManager = null,
            sendControl = { controls += it; true },
            onFailure = { failures++ },
            playbackTrackFactory = MossPlaybackTrackFactory { FakeTrack() },
            playbackDispatcher = StandardTestDispatcher(testScheduler),
            logPlayback = { _, _ -> },
        )
        player.start(backgroundScope)
        player.enqueue(context("run-1", "turn-1", "chunk-1"), byteArrayOf(1))
        player.audioDone("run-1", "turn-1")

        assertEquals(1, failures)
        assertTrue(controls.isEmpty())
        player.stop()
    }

    private suspend fun TestScope.advancePlayback() {
        advanceTimeBy(5)
        runCurrent()
    }

    private fun TestScope.player(track: FakeTrack, controls: MutableList<String>) = MossAudioPlayer(
        audioManager = null,
        sendControl = { controls += it; true },
        onFailure = { error("unexpected playback failure") },
        playbackTrackFactory = MossPlaybackTrackFactory { track },
        playbackDispatcher = StandardTestDispatcher(testScheduler),
        logPlayback = { _, _ -> },
    )

    private fun context(runId: String, turnId: String, chunkId: String) =
        MossAudioContext(runId, turnId, chunkId)

    private fun pcmFrames(count: Int) = ByteArray(count * 2)

    private class FakeTrack(private val maxWriteBytes: Int = Int.MAX_VALUE) : MossPlaybackTrack {
        var acceptedBytes = 0
            private set
        override var playbackHeadPosition = 0L
        override val routeType = 0
        override val bufferCapacityFrames = 9_600
        var startThresholdFrames = bufferCapacityFrames
            private set
        override fun setStartThresholdInFrames(frames: Int): Int {
            startThresholdFrames = frames.coerceAtMost(bufferCapacityFrames)
            return startThresholdFrames
        }
        override fun play() = Unit
        override fun write(bytes: ByteArray, offset: Int, size: Int): Int {
            val written = minOf(size, maxWriteBytes)
            acceptedBytes += written
            return written
        }
        override fun pause() = Unit
        override fun flush() { playbackHeadPosition = 0 }
        override fun release() = Unit
    }

    private fun action(json: String) = org.json.JSONObject(json).optString("action")
    private fun field(json: String, name: String) = org.json.JSONObject(json).optString(name)
    private fun hasField(json: String, name: String) = org.json.JSONObject(json).has(name)
}
