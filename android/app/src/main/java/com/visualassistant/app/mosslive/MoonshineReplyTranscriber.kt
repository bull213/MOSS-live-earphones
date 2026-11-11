package com.visualassistant.app.mosslive

import com.visualassistant.app.voice.AudioChunk
import com.visualassistant.app.voice.MoonshineSpeechRecognizer
import com.visualassistant.app.voice.UtteranceTranscriber
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface ReplyAudioTranscriber {
    suspend fun transcribe(pcm48k: ByteArray): String
}

internal class MoonshineReplyTranscriber(private val installModel: () -> File) : ReplyAudioTranscriber {
    override suspend fun transcribe(pcm48k: ByteArray): String = withContext(Dispatchers.IO) {
        val chunk = Pcm16Resampler.to16kFloat(pcm48k)
        if (chunk.samples.isEmpty()) return@withContext ""
        UtteranceTranscriber { MoonshineSpeechRecognizer() }
            .transcribe(installModel(), listOf(chunk))
    }
}

internal object Pcm16Resampler {
    /** Converts mono 48 kHz signed little-endian PCM to 16 kHz float samples. */
    fun to16kFloat(pcm: ByteArray): AudioChunk {
        val outputCount = pcm.size / 6
        val samples = FloatArray(outputCount)
        for (out in 0 until outputCount) {
            val first = out * 6
            val a = sample(pcm, first)
            val b = sample(pcm, first + 2)
            val c = sample(pcm, first + 4)
            samples[out] = (a + b + c) / (3f * 32768f)
        }
        return AudioChunk(samples, 16_000)
    }

    private fun sample(bytes: ByteArray, at: Int): Int =
        ((bytes[at + 1].toInt() shl 8) or (bytes[at].toInt() and 0xff)).toShort().toInt()
}
