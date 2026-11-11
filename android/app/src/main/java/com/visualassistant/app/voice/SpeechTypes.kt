package com.visualassistant.app.voice

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel

internal enum class SpeechPhase { IDLE, PREPARING, LISTENING, STOPPING }
internal enum class SpeechProblem { PERMISSION, MODEL, AUDIO, ROUTE, INTERRUPTED, OVERLOADED, TOO_LONG, RECOGNITION }

internal data class SpeechText(val id: String, val conversationId: String, val text: String)
internal enum class VoiceSaveStatus { SAVING, FAILED }
internal data class PendingVoiceText(val text: SpeechText, val status: VoiceSaveStatus)
internal data class SpeechState(
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val problem: SpeechProblem? = null,
)

internal sealed interface SpeechOutput {
    data class Completed(val text: SpeechText) : SpeechOutput
    data class Barrier(val done: CompletableDeferred<Unit>) : SpeechOutput
}

internal data class AudioChunk(val samples: FloatArray, val sampleRate: Int)

/** A future earphones source can implement this without changing recognition or chat. */
internal interface AudioSource {
    suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk>
    suspend fun stop()
}

internal sealed interface RecognitionEvent {
    data class Completed(val id: Long, val text: String, val start: Float, val duration: Float) : RecognitionEvent
}

internal interface StreamingSpeechRecognizer : AutoCloseable {
    fun load(directory: File, listener: (RecognitionEvent) -> Unit)
    fun start()
    fun accept(chunk: AudioChunk)
    fun stop()
}

internal class SpeechException(val problem: SpeechProblem, cause: Throwable? = null) : Exception(cause)
