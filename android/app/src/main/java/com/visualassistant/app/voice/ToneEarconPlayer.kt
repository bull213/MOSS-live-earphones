package com.visualassistant.app.voice

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface EarconPlayer {
    fun playWaiting(scope: CoroutineScope? = null)
    fun stopWaiting()
}

internal class ToneEarconPlayer : EarconPlayer {
    private var generator: ToneGenerator? = null
    private var loopJob: Job? = null

    private fun ensureGenerator(): ToneGenerator {
        return generator ?: ToneGenerator(AudioManager.STREAM_MUSIC, 45).also { generator = it }
    }

    override fun playWaiting(scope: CoroutineScope?) {
        stopWaiting()
        try {
            ensureGenerator().startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        } catch (_: Exception) {}

        if (scope != null) {
            loopJob = scope.launch {
                try {
                    while (isActive) {
                        delay(3500)
                        if (isActive) {
                            ensureGenerator().startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun stopWaiting() {
        loopJob?.cancel()
        loopJob = null
        try {
            generator?.stopTone()
        } catch (_: Exception) {}
    }

    fun release() {
        stopWaiting()
        try {
            generator?.release()
        } catch (_: Exception) {}
        generator = null
    }
}
