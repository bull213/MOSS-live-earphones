package com.visualassistant.app.voice

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun Speaker.speakAwait(text: String) = suspendCancellableCoroutine<Unit> { continuation ->
    speak(
        text = text,
        onStart = {},
        onDone = {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    )
    continuation.invokeOnCancellation {
        stop()
    }
}
