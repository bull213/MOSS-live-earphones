package com.visualassistant.app.mosslive

import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages acoustic echo suppression during MOSS Live sessions.
 *
 * When using the phone's physical built-in speaker, audio output radiates into
 * the adjacent microphone. Without suppression, MOSS cloud Voice Activity Detection (VAD)
 * detects this energy as user barge-in and prematurely aborts its response.
 *
 * When earphones or headsets are connected (Bluetooth A2DP/SCO, wired, USB, BLE),
 * physical acoustic isolation is present, so echo suppression is bypassed to preserve
 * natural full-duplex conversational interruption.
 */
internal class MossEchoSuppressor(
    private val audioManager: AudioManager?,
    private val diagnostics: MossLiveDiagnostics? = null,
    private val isEarphonesActiveProvider: (() -> Boolean)? = null,
    private val roomDecayGraceMs: Long = ROOM_DECAY_GRACE_MS,
) {
    private val assistantSpeaking = AtomicBoolean(false)
    private var decayJob: Job? = null

    val isSpeaking: Boolean get() = assistantSpeaking.get()

    fun onAssistantSpeechStarted() {
        decayJob?.cancel()
        decayJob = null
        assistantSpeaking.set(true)
    }

    fun onAssistantPlaybackDrained(scope: CoroutineScope) {
        decayJob?.cancel()
        decayJob = scope.launch {
            delay(roomDecayGraceMs)
            assistantSpeaking.set(false)
        }
    }

    fun onAssistantInterruptedOrStopped() {
        decayJob?.cancel()
        decayJob = null
        assistantSpeaking.set(false)
    }

    fun processUplinkPcm(pcm: ByteArray): ByteArray {
        val shouldSuppress = assistantSpeaking.get() && !isEarphonesOrHeadsetActive()
        return if (shouldSuppress) {
            diagnostics?.recordEchoSuppressedFrame()
            ByteArray(pcm.size)
        } else {
            pcm
        }
    }

    fun isEarphonesOrHeadsetActive(): Boolean {
        isEarphonesActiveProvider?.let { return it.invoke() }
        val am = audioManager ?: return false
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { it.type in HEADSET_OUTPUT_TYPES }
    }

    fun reset() {
        decayJob?.cancel()
        decayJob = null
        assistantSpeaking.set(false)
    }

    companion object {
        const val ROOM_DECAY_GRACE_MS = 200L

        val HEADSET_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
