package com.visualassistant.app.voice

import com.visualassistant.app.mosslive.RealtimeAudioSource

enum class AudioDestination { MOSS, VOICE, MUTED }

internal interface AudioCaptureRouter {
    val activeDestination: AudioDestination
    fun setDestination(destination: AudioDestination)
    fun asRealtimeAudioSource(): RealtimeAudioSource
    fun asAudioSource(): AudioSource
    fun release()
}
