package com.visualassistant.app.mosslive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MossLiveSessionControllerTest {
    @Test
    fun sendsRecentContextAndTaggedPhotosBeforeEnablingMicrophone() = runTest {
        val transport = RecordingTransport()
        val controller = MossLiveSessionController(
            transport, NoopRealtimeAudioSource, audioOutputFactory = { _, _, _ -> RecordingAudioOutput() },
        )
        controller.start(backgroundScope, "conversation-1", MossSessionContext("History: User asked about a photo", listOf(byteArrayOf(4, 5))))
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        val actions = transport.controls.map { JSONObject(it).optString("action") }
        assertTrue(actions.indexOf("session.instructions.append") < actions.indexOf("mic_state"))
        assertTrue(actions.indexOf("video.state") < actions.indexOf("mic_state"))
        assertEquals(listOf(1, 4, 5), transport.binary.single().map { it.toInt() and 0xff })
        assertEquals(true, JSONObject(transport.controls.last { JSONObject(it).optString("action") == "mic_state" }).optBoolean("enabled"))
        controller.appendExternalContext("User: new question", byteArrayOf(8))
        assertEquals(2, transport.controls.count { JSONObject(it).optString("action") == "session.instructions.append" })
        assertEquals(listOf(1, 8), transport.binary.last().map { it.toInt() and 0xff })
        controller.stop()
        runCurrent()
    }

    @Test
    fun suppressesAcousticEchoOnBuiltInSpeakerDuringAssistantSpeechAndResumesOnCompletion() = runTest {
        val transport = RecordingTransport()
        val audioSource = ControllableRealtimeAudioSource()
        val track = ControllerFakeTrack()
        val controller = MossLiveSessionController(
            transport = transport,
            audioSource = audioSource,
            isEarphonesActiveProvider = { false },
            audioOutputFactory = { sendControl, onFailure, onDrained ->
                MossAudioPlayer(
                    audioManager = null,
                    sendControl = sendControl,
                    onFailure = onFailure,
                    playbackTrackFactory = MossPlaybackTrackFactory { track },
                    playbackDispatcher = StandardTestDispatcher(testScheduler),
                    onPlaybackDrained = onDrained,
                    logPlayback = { _, _ -> },
                )
            },
        )

        controller.start(backgroundScope, "conversation-1")
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        // 1. Normal user speech before assistant responds: transmitted as-is
        val userSpeech = byteArrayOf(11, 22, 33, 44)
        audioSource.emitPcm?.invoke(userSpeech)
        runCurrent()
        assertEquals(listOf(0, 11, 22, 33, 44), transport.binary.last().map { it.toInt() })

        // 2. Assistant starts speaking: speaker echo suppression turns mic frames into zeroed silence
        val response = MossAudioContext("run-1", "turn-1", "chunk-1")
        listener.onText(event("response.audio.started", responseData(response)))
        runCurrent()

        audioSource.emitPcm?.invoke(byteArrayOf(99, 88, 77, 66))
        runCurrent()
        assertEquals(listOf(0, 0, 0, 0, 0), transport.binary.last().map { it.toInt() })

        // 3. Audio arrives, finishes playing and acknowledges
        listener.onText(event("response.audio.context", contextData(response)))
        listener.onBinary(byteArrayOf(1, 2, 3, 4))
        listener.onText(event("response.audio.done", responseData(response)))
        advanceTimeBy(5)
        runCurrent()
        assertEquals(4, track.acceptedBytes)

        track.playbackHeadPosition = 2
        advanceTimeBy(5)
        runCurrent()
        assertEquals(listOf("playback.chunk.completed", "playback.completed"), playbackActions(transport))

        // 4. After room reverberation grace delay, live microphone streaming resumes
        advanceTimeBy(250)
        runCurrent()

        audioSource.emitPcm?.invoke(byteArrayOf(55, 66, 77, 88))
        runCurrent()
        assertEquals(listOf(0, 55, 66, 77, 88), transport.binary.last().map { it.toInt() })

        controller.stop()
        runCurrent()
    }

    @Test
    fun bypassesEchoSuppressionWhenEarphonesAreConnected() = runTest {
        val transport = RecordingTransport()
        val audioSource = ControllableRealtimeAudioSource()
        val controller = MossLiveSessionController(
            transport = transport,
            audioSource = audioSource,
            isEarphonesActiveProvider = { true },
            audioOutputFactory = { _, _, _ -> RecordingAudioOutput() },
        )

        controller.start(backgroundScope, "conversation-1")
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        val response = MossAudioContext("run-1", "turn-1", "chunk-1")
        listener.onText(event("response.audio.started", responseData(response)))
        runCurrent()

        // When earphones are connected, mic frames are NOT suppressed
        audioSource.emitPcm?.invoke(byteArrayOf(12, 34, 56, 78))
        runCurrent()
        assertEquals(listOf(0, 12, 34, 56, 78), transport.binary.last().map { it.toInt() })

        controller.stop()
        runCurrent()
    }

    private class ControllableRealtimeAudioSource : RealtimeAudioSource {
        var emitPcm: ((ByteArray) -> Unit)? = null
        override suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit) {
            emitPcm = onPcm
        }
        override suspend fun stop() { emitPcm = null }
    }

    @Test
    fun playbackAcknowledgementsDoNotWaitForFinalTranscriptAndFinalTranscriptPersistsIndependently() = runTest {
        val transport = RecordingTransport()
        val track = ControllerFakeTrack()
        val controller = MossLiveSessionController(
            transport = transport,
            audioSource = NoopRealtimeAudioSource,
            audioOutputFactory = { sendControl, onFailure, _ ->
                MossAudioPlayer(
                    audioManager = null,
                    sendControl = sendControl,
                    onFailure = onFailure,
                    playbackTrackFactory = MossPlaybackTrackFactory { track },
                    playbackDispatcher = StandardTestDispatcher(testScheduler),
                    logPlayback = { _, _ -> },
                )
            },
        )
        val updates = mutableListOf<MossTranscriptUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.transcriptUpdates.collect(updates::add)
        }
        controller.start(backgroundScope, "conversation-1")
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        val response = MossAudioContext("run-1", "turn-1", "chunk-1")
        listener.onText(event("response.audio.started", responseData(response)))
        listener.onText(event("response.audio.context", contextData(response)))
        listener.onBinary(byteArrayOf(0, 0, 0, 0))
        listener.onText(event("response.audio.done", responseData(response)))
        advanceTimeBy(5)
        runCurrent()
        assertEquals(4, track.acceptedBytes)
        assertTrue(playbackActions(transport).isEmpty())

        // The final transcript is intentionally withheld. Playback completion is enough to ack.
        track.playbackHeadPosition = 2
        advanceTimeBy(5)
        runCurrent()
        assertEquals(listOf("playback.chunk.completed", "playback.completed"), playbackActions(transport))

        // Chat transcript delivery can arrive after all playback protocol actions.
        listener.onText(event(
            "output.transcript.done",
            """{"run_id":"run-1","turn_id":"turn-1","text":"Two is the answer."}""",
        ))
        runCurrent()
        assertTrue(updates.any { it.assistant && it.final && it.text == "Two is the answer." })
        controller.stop()
        runCurrent()
    }

    @Test
    fun pairsRapidContextBinaryFramesAndEmitsFinalTranscriptWithoutPlaybackAcknowledgement() = runTest {
        val transport = RecordingTransport()
        val audioOutput = RecordingAudioOutput()
        val controller = MossLiveSessionController(
            transport = transport,
            audioSource = NoopRealtimeAudioSource,
            audioOutputFactory = { _, _, _ -> audioOutput },
        )
        val updates = mutableListOf<MossTranscriptUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.transcriptUpdates.collect(updates::add)
        }

        controller.start(backgroundScope, "conversation-1")
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        val first = MossAudioContext("run-1", "shared-turn", "chunk-1")
        val second = MossAudioContext("run-2", "shared-turn", "chunk-1")
        listener.onText(event("response.audio.started", responseData(first)))
        listener.onText(event("response.audio.context", contextData(first)))
        listener.onBinary(byteArrayOf(1, 2, 3, 4))
        listener.onText(event("response.audio.context", contextData(second)))
        listener.onBinary(byteArrayOf(5, 6))
        listener.onText(event("output.transcript.done", """{"run_id":"run-1","turn_id":"shared-turn","text":"A complete answer."}"""))
        listener.onText(event("output.transcript.done", """{"run_id":"run-2","turn_id":"shared-turn","text":"A second answer."}"""))
        runCurrent()

        assertEquals(listOf(first, second), audioOutput.enqueued.map { it.first })
        assertEquals(
            listOf(byteArrayOf(1, 2, 3, 4).toList(), byteArrayOf(5, 6).toList()),
            audioOutput.enqueued.map { it.second.toList() },
        )
        assertEquals(2, controller.state.value.transcripts.count { it.assistant })
        assertEquals("A complete answer.", controller.state.value.transcripts.first { it.turnId == first.responseKey }.text)
        assertTrue(controller.state.value.transcripts.first { it.turnId == first.responseKey }.final)
        assertEquals("A second answer.", controller.state.value.transcripts.first { it.turnId == second.responseKey }.text)
        assertTrue(updates.any {
            it.assistant && it.final && it.turnId == first.responseKey && it.text == "A complete answer."
        })
        assertFalse(audioOutput.controls.any {
            JSONObject(it).optString("action") == "playback.chunk.completed" ||
                JSONObject(it).optString("action") == "playback.completed"
        })

        controller.stop()
        runCurrent()
    }

    @Test
    fun pausesCameraStreamingOnDownlinkPauseEventAndResumesOnResumeEvent() = runTest {
        val transport = RecordingTransport()
        val controller = MossLiveSessionController(
            transport, NoopRealtimeAudioSource, audioOutputFactory = { _, _, _ -> RecordingAudioOutput() },
        )
        controller.start(backgroundScope, "conversation-1", MossSessionContext(photos = listOf(byteArrayOf(10, 20))))
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        assertTrue(controller.state.value.cameraSharing)
        assertFalse(controller.state.value.cameraPaused)
        val initialCameraFrames = transport.binary.count { it.firstOrNull() == 0x01.toByte() }
        assertEquals(1, initialCameraFrames)

        // Advance 1500ms to allow a periodic stream frame
        advanceTimeBy(1500)
        runCurrent()
        val streamFramesBeforePause = transport.binary.count { it.firstOrNull() == 0x01.toByte() }
        assertTrue(streamFramesBeforePause > initialCameraFrames)

        // Backend signals video.stream_pause during retrieval
        listener.onText(event("video.stream_pause", "{}"))
        runCurrent()
        assertTrue(controller.state.value.cameraPaused)

        // Advance 3000ms while paused; no new camera frames should be sent
        advanceTimeBy(3000)
        runCurrent()
        val streamFramesDuringPause = transport.binary.count { it.firstOrNull() == 0x01.toByte() }
        assertEquals(streamFramesBeforePause, streamFramesDuringPause)

        // Backend signals video.stream_resume
        listener.onText(event("video.stream_resume", "{}"))
        runCurrent()
        assertFalse(controller.state.value.cameraPaused)
        val streamFramesAfterResume = transport.binary.count { it.firstOrNull() == 0x01.toByte() }
        assertTrue(streamFramesAfterResume > streamFramesDuringPause)

        controller.stop()
        runCurrent()
    }

    @Test
    fun cancelActiveTurnDispatchesUpstreamCancelAndStopsActivePlayback() = runTest {
        val transport = RecordingTransport()
        val audioOutput = RecordingAudioOutput()
        val controller = MossLiveSessionController(
            transport, NoopRealtimeAudioSource, audioOutputFactory = { _, _, _ -> audioOutput },
        )
        controller.start(backgroundScope, "conversation-1")
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        val response = MossAudioContext("run-1", "turn-1", "chunk-1")
        listener.onText(event("response.audio.started", responseData(response)))
        runCurrent()
        assertEquals(MossConnectionState.SPEAKING, controller.state.value.connection)

        controller.cancelActiveTurn()
        runCurrent()

        assertTrue(transport.controls.any { JSONObject(it).optString("action") == "response.cancel" })
        assertEquals(MossConnectionState.LISTENING, controller.state.value.connection)

        controller.stop()
        runCurrent()
    }

    @Test
    fun testSuspendAudioDispatchesMicAndVideoStateFalse() = runTest {
        val transport = RecordingTransport()
        val controller = MossLiveSessionController(
            transport, NoopRealtimeAudioSource, audioOutputFactory = { _, _, _ -> RecordingAudioOutput() },
        )
        controller.start(backgroundScope, "conv-suspend")
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        assertTrue(controller.state.value.microphoneTransmitting)
        assertFalse(controller.isSuspended)

        controller.suspendAudio()
        runCurrent()

        assertTrue(controller.isSuspended)
        assertFalse(controller.state.value.microphoneTransmitting)
        val actions = transport.controls.map { JSONObject(it).optString("action") }
        assertTrue(actions.contains("mic_state"))
        assertTrue(actions.contains("video.state"))

        controller.resumeAudio()
        runCurrent()

        assertFalse(controller.isSuspended)
        assertTrue(controller.state.value.microphoneTransmitting)
        controller.stop()
    }

    @Test
    fun testInterimTranscriptEmitsToTranscriptUpdates() = runTest {
        val transport = RecordingTransport()
        val controller = MossLiveSessionController(
            transport, NoopRealtimeAudioSource, audioOutputFactory = { _, _, _ -> RecordingAudioOutput() },
        )
        val updates = mutableListOf<MossTranscriptUpdate>()
        backgroundScope.launch {
            controller.transcriptUpdates.collect { updates += it }
        }

        controller.start(backgroundScope, "conv-interim")
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        listener.onText(event("input.transcript.updated", """{"turn":"t1","text":"use notebook Neuro"}"""))
        runCurrent()

        assertEquals(1, updates.size)
        assertEquals("use notebook Neuro", updates[0].text)
        assertFalse(updates[0].final)

        listener.onText(event("input.transcript.done", """{"turn":"t1","text":"use notebook Neuroscience"}"""))
        runCurrent()

        assertEquals(2, updates.size)
        assertEquals("use notebook Neuroscience", updates[1].text)
        assertTrue(updates[1].final)
        controller.stop()
    }

    @Test
    fun testWatchdogTimeoutClosesTransportAt60Seconds() = runTest {
        val transport = RecordingTransport()
        val controller = MossLiveSessionController(
            transport, NoopRealtimeAudioSource, audioOutputFactory = { _, _, _ -> RecordingAudioOutput() },
        )
        controller.start(backgroundScope, "conv-watchdog")
        val listener = requireNotNull(transport.listener)
        listener.onOpen()
        listener.onText(event("session_attached", """{"session_id":"s1"}"""))
        listener.onText(event("session.updated", """{"error":""}"""))
        runCurrent()

        controller.suspendAudio()
        runCurrent()
        assertTrue(controller.isSuspended)

        // Advance 59 seconds: socket remains open
        advanceTimeBy(59_000L)
        runCurrent()
        assertTrue(controller.isSuspended)
        assertEquals(null, transport.closedWithCode)

        // Advance past 60 seconds: watchdog fires and closes socket
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1000, transport.closedWithCode)
        assertEquals("quiescent_timeout", transport.closedWithReason)
        assertFalse(controller.isSuspended)
        assertEquals(MossConnectionState.IDLE, controller.state.value.connection)
    }

    private fun event(action: String, data: String) = """{"action":"$action","data":$data}"""
    private fun playbackActions(transport: RecordingTransport) = transport.controls.mapNotNull {
        JSONObject(it).optString("action").takeIf { action ->
            action == "playback.chunk.completed" || action == "playback.completed" || action == "playback.stopped"
        }
    }
    private fun contextData(context: MossAudioContext) =
        """{"run_id":"${context.runId}","turn_id":"${context.turnId}","chunk_id":"${context.chunkId}"}"""
    private fun responseData(context: MossAudioContext) =
        """{"run_id":"${context.runId}","turn_id":"${context.turnId}"}"""

    private class RecordingTransport : MossLiveTransport {
        var listener: MossLiveTransportListener? = null
        val controls = mutableListOf<String>()
        val binary = mutableListOf<ByteArray>()
        var closedWithCode: Int? = null
        var closedWithReason: String? = null
        override fun connect(listener: MossLiveTransportListener) { this.listener = listener }
        override fun sendText(text: String): Boolean { controls += text; return true }
        override fun sendBinary(bytes: ByteArray): Boolean { binary += bytes; return true }
        override fun queuedBytes() = 0L
        override fun close() { close(1000, "Session ended") }
        override fun close(code: Int, reason: String) {
            closedWithCode = code
            closedWithReason = reason
        }
    }

    private class ControllerFakeTrack : MossPlaybackTrack {
        var acceptedBytes = 0
        override var playbackHeadPosition = 0L
        override val routeType = 0
        override val bufferCapacityFrames = 9_600
        override fun setStartThresholdInFrames(frames: Int) = frames
        override fun play() = Unit
        override fun write(bytes: ByteArray, offset: Int, size: Int): Int {
            acceptedBytes += size
            return size
        }
        override fun pause() = Unit
        override fun flush() { playbackHeadPosition = 0 }
        override fun release() = Unit
    }

    private class RecordingAudioOutput : MossAudioOutput {
        val enqueued = mutableListOf<Pair<MossAudioContext, ByteArray>>()
        val controls = mutableListOf<String>()
        override fun start(scope: CoroutineScope) = Unit
        override fun enqueue(context: MossAudioContext, bytes: ByteArray) {
            enqueued += context to bytes.copyOf()
        }
        override fun audioDone(runId: String, turnId: String) = Unit
        override fun stopResponse(runId: String, turnId: String) = Unit
        override suspend fun stop() = Unit
        override fun isResponseFullyPlayed(responseKey: String) = false
    }

    private object NoopRealtimeAudioSource : RealtimeAudioSource {
        override suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit) = Unit
        override suspend fun stop() = Unit
    }
}
