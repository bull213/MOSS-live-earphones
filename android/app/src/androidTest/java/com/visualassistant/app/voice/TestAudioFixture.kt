package com.visualassistant.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun readMono16kFixture(bytes: ByteArray): FloatArray {
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(input.getInt(0) == 0x46464952 && input.getInt(8) == 0x45564157)
    var offset = 12
    var sampleRate = 0
    var channels = 0
    var bits = 0
    var format = 0
    while (offset + 8 <= bytes.size) {
        val tag = input.getInt(offset)
        val size = input.getInt(offset + 4)
        val body = offset + 8
        require(size >= 0 && body + size <= bytes.size)
        when (tag) {
            0x20746d66 -> {
                format = input.getShort(body).toInt()
                channels = input.getShort(body + 2).toInt()
                sampleRate = input.getInt(body + 4)
                bits = input.getShort(body + 14).toInt()
            }
            0x61746164 -> {
                require(format == 1 && channels == 1 && sampleRate == 16000 && bits == 16)
                return FloatArray(size / 2) { index -> input.getShort(body + index * 2) / 32768f }
            }
        }
        offset = body + size + (size and 1)
    }
    error("Fixture has no PCM data")
}
