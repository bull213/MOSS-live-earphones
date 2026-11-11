package com.visualassistant.app.voice

import java.util.ArrayDeque

/** Groups audio until a sustained pause. Never emits partial utterances. */
internal class UtteranceBuffer(
    private val pauseSeconds: Double = 2.0,
    private val preRollSeconds: Double = 0.5,
    private val maximumSeconds: Double = 300.0,
) {
    private val preRoll = ArrayDeque<AudioChunk>()
    private val current = mutableListOf<AudioChunk>()
    private var preRollDuration = 0.0
    private var duration = 0.0
    private var quiet = 0.0

    fun accept(chunk: AudioChunk, speech: Boolean): List<AudioChunk>? {
        val seconds = chunk.durationSeconds()
        if (current.isEmpty()) {
            preRoll.addLast(chunk)
            preRollDuration += seconds
            while (preRoll.size > 1 && preRollDuration - preRoll.first.durationSeconds() >= preRollSeconds) {
                val removed = preRoll.removeFirst()
                preRollDuration -= removed.durationSeconds()
            }
            if (!speech) return null
            current.addAll(preRoll)
            duration = preRollDuration
            preRoll.clear()
            preRollDuration = 0.0
        } else {
            current.add(chunk)
            duration += seconds
        }
        if (duration > maximumSeconds) throw SpeechException(SpeechProblem.TOO_LONG)
        quiet = if (speech) 0.0 else quiet + seconds
        return if (quiet >= pauseSeconds) flush() else null
    }

    fun flush(): List<AudioChunk>? {
        if (current.isEmpty()) return null
        val result = current.toList()
        current.clear()
        duration = 0.0
        quiet = 0.0
        return result
    }
}

private fun AudioChunk.durationSeconds() = samples.size.toDouble() / sampleRate
