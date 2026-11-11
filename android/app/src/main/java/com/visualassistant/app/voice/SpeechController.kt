package com.visualassistant.app.voice

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns one foreground listening session; recognizer calls never overlap. */
internal class SpeechController(
    private val installModel: () -> File,
    private val source: AudioSource,
    private val newRecognizer: () -> StreamingSpeechRecognizer = { MoonshineSpeechRecognizer() },
    private val worker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newDetector: () -> SpeechDetector = { VoiceActivityDetector() },
) {
    private class Run(val scope: CoroutineScope) {
        val stop = AtomicBoolean(false)
        val problem = AtomicReference<SpeechProblem?>(null)
        lateinit var job: Job
        var stopJob: Job? = null
        var finishing = false
    }

    private val lock = Any()
    @Volatile private var active: Run? = null
    private val mutableState = MutableStateFlow(SpeechState())
    val state = mutableState.asStateFlow()
    private val output = Channel<SpeechOutput>(Channel.UNLIMITED)
    val outputs = output.receiveAsFlow()
    private val transcriber = UtteranceTranscriber(newRecognizer)

    fun start(scope: CoroutineScope, conversationId: String) {
        val run = synchronized(lock) {
            if (active != null) return
            Run(scope).also { candidate ->
                active = candidate
                mutableState.value = SpeechState(phase = SpeechPhase.PREPARING)
                candidate.job = scope.launch(worker, start = CoroutineStart.LAZY) { runSession(candidate, conversationId) }
            }
        }
        run.job.start()
    }

    fun requestStop() {
        val run = active ?: return
        stop(run)
    }

    suspend fun stopAndWait() {
        val run = active
        if (run != null) {
            stop(run)
            run.job.join()
        }
        // The chat consumer processes this after every final emitted before stop returned.
        val done = CompletableDeferred<Unit>()
        output.send(SpeechOutput.Barrier(done))
        done.await()
    }

    private fun stop(run: Run) {
        val stopJob = synchronized(lock) {
            if (active !== run || !run.stop.compareAndSet(false, true)) return
            mutableState.update { if (it.phase == SpeechPhase.IDLE) it else it.copy(phase = SpeechPhase.STOPPING) }
            if (run.finishing) null else run.scope.launch(io, start = CoroutineStart.LAZY) {
                try { source.stop() } catch (_: Exception) { run.problem.compareAndSet(null, SpeechProblem.AUDIO) }
            }.also { run.stopJob = it }
        }
        stopJob?.start()
    }

    private suspend fun runSession(run: Run, conversationId: String) {
        var stage = SpeechProblem.MODEL
        try {
            val directory = withContext(io) { installModel() }
            if (run.stop.get()) return
            stage = SpeechProblem.AUDIO
            coroutineScope {
                val utterances = Channel<List<AudioChunk>>(4)
                launch(io) {
                    try {
                        collectUtterances(run, utterances)
                    } finally {
                        utterances.close()
                    }
                }
                for (audio in utterances) {
                    stage = SpeechProblem.RECOGNITION
                    val text = transcriber.transcribe(directory, audio)
                    if (text.isNotEmpty()) {
                        output.send(SpeechOutput.Completed(SpeechText(UUID.randomUUID().toString(), conversationId, text)))
                    }
                    stage = SpeechProblem.AUDIO
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            run.problem.compareAndSet(null, (error as? SpeechException)?.problem ?: stage)
        } finally {
            withContext(NonCancellable) {
                val stopJob = synchronized(lock) { run.finishing = true; run.stopJob }
                stopJob?.join()
                try { source.stop() } catch (_: Exception) { run.problem.compareAndSet(null, SpeechProblem.AUDIO) }
                synchronized(lock) { if (active === run) active = null }
                mutableState.value = SpeechState(problem = run.problem.get())
            }
        }
    }

    private suspend fun collectUtterances(run: Run, utterances: Channel<List<AudioChunk>>) {
        val buffer = UtteranceBuffer()
        newDetector().use { detector ->
            val chunks = source.start(run.scope) { problem ->
                if (active === run && run.problem.compareAndSet(null, problem)) stop(run)
            }
            if (run.stop.get()) source.stop()
            else mutableState.update { it.copy(phase = SpeechPhase.LISTENING) }
            for (chunk in chunks) {
                buffer.accept(chunk, detector.hasSpeech(chunk))?.let { utterances.send(it) }
            }
            buffer.flush()?.let { utterances.send(it) }
        }
    }
}
