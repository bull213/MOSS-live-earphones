package com.visualassistant.app.voice

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/** WebRTC expects 20 ms at 16 kHz. This is detection only, not transcription. */
internal interface SpeechDetector : AutoCloseable {
    fun hasSpeech(chunk: AudioChunk): Boolean
}

internal class VoiceActivityDetector : SpeechDetector {
    private val vad = VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.NORMAL)
    private var pending = FloatArray(0)
    private var rate = 0

    override fun hasSpeech(chunk: AudioChunk): Boolean {
        if (rate != 0 && rate != chunk.sampleRate) throw SpeechException(SpeechProblem.AUDIO)
        rate = chunk.sampleRate
        pending += chunk.samples
        val frameSize = rate / 50
        var offset = 0
        var speech = false
        while (pending.size - offset >= frameSize) {
            val frame = FloatArray(320) { i -> pending[offset + i * frameSize / 320] }
            if (vad.isSpeech(frame)) speech = true
            offset += frameSize
        }
        if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
        return speech
    }

    override fun close() = vad.close()
}
