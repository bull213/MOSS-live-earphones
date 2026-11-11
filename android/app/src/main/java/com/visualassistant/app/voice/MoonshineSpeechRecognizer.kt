package com.visualassistant.app.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import java.io.File

/** All methods are called serially by SpeechController's inference worker. */
internal class MoonshineSpeechRecognizer : StreamingSpeechRecognizer {
    private var engine: Transcriber? = null

    override fun load(directory: File, listener: (RecognitionEvent) -> Unit) {
        val transcriber = Transcriber(listOf(TranscriberOption("return_audio_data", "false")))
        engine = transcriber
        transcriber.addListener { event ->
            when (event) {
                is TranscriptEvent.LineCompleted -> listener(RecognitionEvent.Completed(
                    event.line.id, event.line.text.orEmpty(), event.line.startTime, event.line.duration))
                is TranscriptEvent.Error -> throw SpeechException(SpeechProblem.RECOGNITION, event.cause)
                else -> Unit
            }
        }
        transcriber.setUpdateInterval(0.5)
        transcriber.loadFromFiles(directory.absolutePath, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
    }

    override fun start() { requireNotNull(engine).start() }
    override fun accept(chunk: AudioChunk) { requireNotNull(engine).addAudio(chunk.samples, chunk.sampleRate) }
    override fun stop() { engine?.stop() }
    override fun close() { engine?.close(); engine = null }
}
