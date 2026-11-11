package com.visualassistant.app.mosslive

import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16ResamplerTest {
    @Test fun convertsMono48kPcmTo16kFloatSamples() {
        val pcm = byteArrayOf(
            0, 0, 0x10, 0x27, 0xF0.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x7F,
        )
        val converted = Pcm16Resampler.to16kFloat(pcm)
        assertEquals(16_000, converted.sampleRate)
        assertEquals(2, converted.samples.size)
        assertEquals(0f, converted.samples[0], 0.0001f)
        assertEquals(32767f / 32768f, converted.samples[1], 0.0001f)
    }
}
