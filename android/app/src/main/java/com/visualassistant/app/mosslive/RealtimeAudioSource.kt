package com.visualassistant.app.mosslive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal interface RealtimeAudioSource {
    suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit)
    suspend fun stop()
}

/** Captures the phone microphone as uncompressed 16 kHz mono PCM S16LE. */
internal class PhoneRealtimeAudioSource(context: Context) : RealtimeAudioSource {
    private val appContext = context.applicationContext
    private val lifecycle = kotlinx.coroutines.sync.Mutex()
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var stopping: AtomicBoolean? = null

    @SuppressLint("MissingPermission")
    override suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit) =
        withContext(Dispatchers.IO) {
            lifecycle.lock()
            try {
                check(recorder == null) { "Realtime microphone is already active" }
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("Microphone permission is unavailable")
                }
                val inputDevices = appContext.getSystemService(android.media.AudioManager::class.java)
                    .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                val preferredMic = inputDevices.firstOrNull { it.type in ALLOWED_MIC_TYPES && it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?: inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?: throw IllegalStateException("Phone microphone is unavailable")
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minBuffer > 0) { "Phone microphone format is unavailable" }
                val audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_RATE * 2 / 5))
                    .build()
                try {
                    check(audioRecord.state == AudioRecord.STATE_INITIALIZED)
                    audioRecord.setPreferredDevice(preferredMic)
                    audioRecord.startRecording()
                    check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val stopFlag = AtomicBoolean(false)
                    recorder = audioRecord
                    stopping = stopFlag
                    captureJob = scope.launch(Dispatchers.IO) {
                        val samples = ShortArray(SAMPLES_PER_FRAME)
                        val startedAt = SystemClock.elapsedRealtime()
                        try {
                            while (!stopFlag.get() && currentCoroutineContext().isActive) {
                                val count = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                                if (count <= 0) {
                                    if (!stopFlag.get()) onFailure()
                                    break
                                }
                                val routed = audioRecord.routedDevice
                                if ((routed != null && routed.type !in ALLOWED_MIC_TYPES) ||
                                    (routed == null && SystemClock.elapsedRealtime() - startedAt > ROUTE_GRACE_MS)) {
                                    onFailure()
                                    break
                                }
                                onPcm(MossLiveProtocol.pcm16Le(samples, count))
                            }
                        } catch (_: SecurityException) {
                            if (!stopFlag.get()) onFailure()
                        } catch (_: Exception) {
                            if (!stopFlag.get()) onFailure()
                        }
                    }
                } catch (error: Exception) {
                    audioRecord.release()
                    throw error
                }
            } finally {
                lifecycle.unlock()
            }
        }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        lifecycle.lock()
        try {
            val active = recorder ?: return@withContext
            stopping?.set(true)
            runCatching { active.stop() }
            captureJob?.join()
            active.release()
            recorder = null
            captureJob = null
            stopping = null
        } finally {
            lifecycle.unlock()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_FRAME = SAMPLE_RATE / 10
        const val ROUTE_GRACE_MS = 2_000
        val ALLOWED_MIC_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
