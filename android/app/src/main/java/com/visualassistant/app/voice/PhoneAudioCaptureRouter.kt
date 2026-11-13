package com.visualassistant.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.visualassistant.app.mosslive.MossLiveProtocol
import com.visualassistant.app.mosslive.RealtimeAudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified audio capture router managing a single AudioRecord instance @ 16 kHz Mono PCM.
 * Multiplexes audio frames between MOSS Live uplink and Moonshine offline speech detection.
 */
internal class PhoneAudioCaptureRouter(
    context: Context,
    private val audioRecordFactory: ((sampleRate: Int, minBufferSize: Int) -> AudioRecord)? = null,
) : AudioCaptureRouter {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val lifecycleMutex = Mutex()
    private val internalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val destination = AtomicReference(AudioDestination.MOSS)
    override val activeDestination: AudioDestination get() = destination.get()

    override fun setDestination(destination: AudioDestination) {
        this.destination.set(destination)
    }

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private val stopping = AtomicBoolean(false)

    // Subscriber state
    private var mossActive = false
    private var mossPcmCallback: ((ByteArray) -> Unit)? = null
    private var mossFailureCallback: (() -> Unit)? = null

    private var voiceActive = false
    private var voiceChannel: Channel<AudioChunk>? = null
    private var voiceFailureCallback: ((SpeechProblem) -> Unit)? = null

    private val realtimeAudioSourceDelegate = object : RealtimeAudioSource {
        override suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit) =
            withContext(Dispatchers.IO) {
                lifecycleMutex.withLock {
                    mossActive = true
                    mossPcmCallback = onPcm
                    mossFailureCallback = onFailure
                    ensureRecordingStarted()
                }
            }

        override suspend fun stop() = withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                mossActive = false
                mossPcmCallback = null
                mossFailureCallback = null
                checkStopRecording()
            }
        }
    }

    private val audioSourceDelegate = object : AudioSource {
        override suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk> =
            withContext(Dispatchers.IO) {
                lifecycleMutex.withLock {
                    val channel = Channel<AudioChunk>(20)
                    voiceActive = true
                    voiceChannel = channel
                    voiceFailureCallback = onFailure
                    ensureRecordingStarted()
                    channel
                }
            }

        override suspend fun stop() = withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                voiceActive = false
                voiceChannel?.close()
                voiceChannel = null
                voiceFailureCallback = null
                checkStopRecording()
            }
        }
    }

    override fun asRealtimeAudioSource(): RealtimeAudioSource = realtimeAudioSourceDelegate

    override fun asAudioSource(): AudioSource = audioSourceDelegate

    @SuppressLint("MissingPermission")
    private fun ensureRecordingStarted() {
        if (recorder != null) return

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceFailureCallback?.invoke(SpeechProblem.PERMISSION)
            mossFailureCallback?.invoke()
            throw SecurityException("Microphone permission is unavailable")
        }

        val inputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
        val preferredMic = inputDevices.firstOrNull { it.type in ALLOWED_MIC_TYPES && it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: inputDevices.firstOrNull()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "Phone microphone format is unavailable" }

        val audioRecord = audioRecordFactory?.invoke(SAMPLE_RATE, minBuffer) ?: AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_RATE * 2 / 5))
            .build()

        try {
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            if (preferredMic != null) {
                audioRecord.setPreferredDevice(preferredMic)
            }
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord failed to start recording" }

            stopping.set(false)
            recorder = audioRecord

            captureJob = internalScope.launch {
                val samples = ShortArray(SAMPLES_PER_FRAME)
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    while (!stopping.get() && currentCoroutineContext().isActive) {
                        val count = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) {
                            if (!stopping.get()) {
                                mossFailureCallback?.invoke()
                                voiceFailureCallback?.invoke(SpeechProblem.AUDIO)
                            }
                            break
                        }

                        val routed = audioRecord.routedDevice
                        if ((routed != null && routed.type !in ALLOWED_MIC_TYPES) ||
                            (routed == null && SystemClock.elapsedRealtime() - startedAt > ROUTE_GRACE_MS)) {
                            if (!stopping.get()) {
                                mossFailureCallback?.invoke()
                                voiceFailureCallback?.invoke(SpeechProblem.ROUTE)
                            }
                            break
                        }

                        when (destination.get()) {
                            AudioDestination.MOSS -> {
                                mossPcmCallback?.invoke(MossLiveProtocol.pcm16Le(samples, count))
                            }
                            AudioDestination.VOICE -> {
                                val chunk = AudioChunk(FloatArray(count) { samples[it] / 32768f }, SAMPLE_RATE)
                                val channel = voiceChannel
                                if (channel != null) {
                                    val sendResult = channel.trySend(chunk)
                                    if (sendResult.isFailure && !sendResult.isClosed) {
                                        voiceFailureCallback?.invoke(SpeechProblem.OVERLOADED)
                                    }
                                }
                            }
                            AudioDestination.MUTED -> {
                                // Frame dropped; no buffers delivered to either engine
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    if (!stopping.get()) {
                        mossFailureCallback?.invoke()
                        voiceFailureCallback?.invoke(SpeechProblem.PERMISSION)
                    }
                } catch (_: Exception) {
                    if (!stopping.get()) {
                        mossFailureCallback?.invoke()
                        voiceFailureCallback?.invoke(SpeechProblem.AUDIO)
                    }
                }
            }
        } catch (error: Exception) {
            audioRecord.release()
            throw error
        }
    }

    private fun checkStopRecording() {
        if (!mossActive && !voiceActive) {
            stopping.set(true)
            val rec = recorder
            recorder = null
            captureJob?.cancel()
            captureJob = null
            rec?.runCatching { stop() }
            rec?.release()
        }
    }

    override fun release() {
        internalScope.cancel()
        val locked = lifecycleMutex.tryLock()
        try {
            stopping.set(true)
            captureJob?.cancel()
            captureJob = null
            voiceChannel?.close()
            voiceChannel = null
            val rec = recorder
            recorder = null
            rec?.runCatching { stop() }
            rec?.release()
        } finally {
            if (locked) {
                lifecycleMutex.unlock()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_FRAME = SAMPLE_RATE / 10 // 1600 samples = 100ms
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
