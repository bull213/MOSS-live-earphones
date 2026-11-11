package com.visualassistant.app.voice

import java.io.File

/** Transcribes a finished utterance and joins the model's internal segments. */
internal class UtteranceTranscriber(
    private val newRecognizer: () -> StreamingSpeechRecognizer,
) {
    fun transcribe(directory: File, audio: List<AudioChunk>): String {
        val lines = linkedMapOf<Long, RecognitionEvent.Completed>()
        newRecognizer().use { recognizer ->
            recognizer.load(directory) { event ->
                if (event is RecognitionEvent.Completed) lines[event.id] = event
            }
            recognizer.start()
            audio.forEach(recognizer::accept)
            recognizer.stop()
        }
        return combineTranscriptLines(lines.values.toList())
    }
}
