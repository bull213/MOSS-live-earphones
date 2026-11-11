package com.visualassistant.app.voice

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechControllerTest {
    private fun detector() = object : SpeechDetector {
        override fun hasSpeech(chunk: AudioChunk) = true
        override fun close() = Unit
    }
    private class FakeSource : AudioSource {
        var starts = 0
        var channel: Channel<AudioChunk>? = null
        var onFailure: ((SpeechProblem) -> Unit)? = null
        override suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk> {
            starts++
            this.onFailure = onFailure
            return Channel<AudioChunk>(20).also { channel = it }
        }
        override suspend fun stop() { channel?.close() }
    }

    private class FakeRecognizer(private val fail: Boolean = false) : StreamingSpeechRecognizer {
        lateinit var listener: (RecognitionEvent) -> Unit
        var closed = false
        override fun load(directory: File, listener: (RecognitionEvent) -> Unit) { this.listener = listener }
        override fun start() = Unit
        override fun accept(chunk: AudioChunk) {
            if (fail) throw IllegalStateException("simulated recognition failure")
        }
        override fun stop() {
            if (fail) throw IllegalStateException("simulated stop failure")
            listener(RecognitionEvent.Completed(1, "What is this?", 0f, 1f))
        }
        override fun close() { closed = true }
    }

    @Test fun stopsAndFlushesOneFinalTranscript() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        val recognizer = FakeRecognizer()
        val controller = SpeechController({ File(".") }, source, { recognizer }, dispatcher, dispatcher, ::detector)
        val outputs = mutableListOf<SpeechOutput>()
        backgroundScope.launch {
            controller.outputs.collect { event ->
                if (event is SpeechOutput.Barrier) event.done.complete(Unit) else outputs += event
            }
        }
        controller.start(backgroundScope, "conversation")
        runCurrent()
        assertEquals(SpeechPhase.LISTENING, controller.state.value.phase)
        source.channel!!.trySend(AudioChunk(FloatArray(1600), 16000))
        runCurrent()
        assertTrue(outputs.isEmpty())
        controller.requestStop()
        runCurrent()
        assertEquals(SpeechPhase.IDLE, controller.state.value.phase)
        assertEquals("What is this?", (outputs.single() as SpeechOutput.Completed).text.text)
        assertTrue(recognizer.closed)
    }

    @Test fun longSpeechProducesOneMessageOnlyAfterThePause() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        val recognizer = object : StreamingSpeechRecognizer {
            lateinit var listener: (RecognitionEvent) -> Unit
            override fun load(directory: File, listener: (RecognitionEvent) -> Unit) { this.listener = listener }
            override fun start() = Unit
            override fun accept(chunk: AudioChunk) = Unit
            override fun stop() {
                listener(RecognitionEvent.Completed(1, "First part.", 0f, 15f))
                listener(RecognitionEvent.Completed(2, "Second part.", 15f, 15f))
            }
            override fun close() = Unit
        }
        var loads = 0
        val controller = SpeechController({ File(".") }, source, { loads++; recognizer }, dispatcher, dispatcher, {
            object : SpeechDetector {
                override fun hasSpeech(chunk: AudioChunk) = chunk.samples[0] > 0f
                override fun close() = Unit
            }
        })
        val outputs = mutableListOf<SpeechOutput>()
        backgroundScope.launch { controller.outputs.collect { outputs += it } }
        controller.start(backgroundScope, "conversation")
        runCurrent()
        repeat(300) {
            source.channel!!.trySend(AudioChunk(FloatArray(1600) { 1f }, 16000))
            runCurrent()
        }
        assertEquals(0, loads)
        assertTrue(outputs.isEmpty())
        repeat(20) {
            source.channel!!.trySend(AudioChunk(FloatArray(1600), 16000))
            runCurrent()
        }
        assertEquals(1, loads)
        assertEquals("First part. Second part.", (outputs.single() as SpeechOutput.Completed).text.text)
        controller.requestStop()
        runCurrent()
        assertEquals(1, outputs.size)
    }

    @Test fun stoppingDuringPreparationNeverStartsTheMicrophone() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        val controller = SpeechController({ File(".") }, source, { FakeRecognizer() }, dispatcher, dispatcher, ::detector)
        controller.start(backgroundScope, "conversation")
        controller.requestStop()
        runCurrent()
        assertEquals(0, source.starts)
        assertEquals(SpeechPhase.IDLE, controller.state.value.phase)
    }

    @Test fun microphoneCanBeStartedAgainAfterFinishing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        val recognizers = mutableListOf<FakeRecognizer>()
        val controller = SpeechController({ File(".") }, source, {
            FakeRecognizer().also { recognizers += it }
        }, dispatcher, dispatcher, ::detector)
        val finished = mutableListOf<SpeechOutput.Completed>()
        backgroundScope.launch {
            controller.outputs.collect { event ->
                when (event) {
                    is SpeechOutput.Completed -> finished += event
                    is SpeechOutput.Barrier -> event.done.complete(Unit)
                }
            }
        }

        repeat(2) {
            controller.start(backgroundScope, "conversation")
            runCurrent()
            assertEquals(SpeechPhase.LISTENING, controller.state.value.phase)
            source.channel!!.trySend(AudioChunk(FloatArray(1600), 16000))
            runCurrent()
            var stopped = false
            backgroundScope.launch { controller.stopAndWait(); stopped = true }
            runCurrent()
            assertTrue(stopped)
            assertEquals(SpeechPhase.IDLE, controller.state.value.phase)
        }

        assertEquals(2, source.starts)
        assertEquals(2, recognizers.size)
        assertTrue(recognizers.all { it.closed })
        assertEquals(2, finished.size)
        assertNotEquals(finished[0].text.id, finished[1].text.id)
    }

    @Test fun recognitionFailureReportsErrorAndClosesRecognizer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        val recognizer = FakeRecognizer(fail = true)
        val controller = SpeechController({ File(".") }, source, { recognizer }, dispatcher, dispatcher, ::detector)
        val outputs = mutableListOf<SpeechOutput>()
        backgroundScope.launch { controller.outputs.collect { outputs += it } }
        controller.start(backgroundScope, "conversation")
        runCurrent()
        source.channel!!.trySend(AudioChunk(FloatArray(1600), 16000))
        runCurrent()
        controller.requestStop()
        runCurrent()
        assertEquals(SpeechProblem.RECOGNITION, controller.state.value.problem)
        assertTrue(outputs.isEmpty())
        assertTrue(recognizer.closed)
    }
}
