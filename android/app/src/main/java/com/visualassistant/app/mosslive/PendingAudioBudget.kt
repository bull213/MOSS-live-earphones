package com.visualassistant.app.mosslive

import java.util.concurrent.atomic.AtomicInteger

/** Bounds queued PCM by bytes so bursty WebSocket delivery can be buffered safely. */
internal class PendingAudioBudget(private val maxBytes: Int) {
    private val pending = AtomicInteger()

    fun reserve(bytes: Int): Boolean {
        if (bytes <= 0) return false
        while (true) {
            val current = pending.get()
            if (bytes > maxBytes - current) return false
            if (pending.compareAndSet(current, current + bytes)) return true
        }
    }

    fun release(bytes: Int) {
        pending.updateAndGet { (it - bytes).coerceAtLeast(0) }
    }

    fun reset() { pending.set(0) }
}
