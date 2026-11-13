package com.visualassistant.app.voice

import com.visualassistant.app.mosslive.RealtimeAudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureRouterTest {

    private class TestableAudioCaptureRouter : AudioCaptureRouter {
        private val destination = AtomicReference(AudioDestination.MOSS)
        override val activeDestination: AudioDestination get() = destination.get()

        override fun setDestination(destination: AudioDestination) {
            this.destination.set(destination)
        }

        val activeSubscriberCount = AtomicInteger(0)
        var physicalRecordingStarted = false
        var physicalRecordingStopped = false

        var mossPcmCallback: ((ByteArray) -> Unit)? = null
        var voiceChannel: Channel<AudioChunk>? = null

        private val realtimeAudioSource = object : RealtimeAudioSource {
            override suspend fun start(scope: CoroutineScope, onPcm: (ByteArray) -> Unit, onFailure: () -> Unit) {
                mossPcmCallback = onPcm
                if (activeSubscriberCount.getAndIncrement() == 0) {
                    physicalRecordingStarted = true
                    physicalRecordingStopped = false
                }
            }

            override suspend fun stop() {
                mossPcmCallback = null
                if (activeSubscriberCount.decrementAndGet() == 0) {
                    physicalRecordingStopped = true
                }
            }
        }

        private val audioSource = object : AudioSource {
            override suspend fun start(scope: CoroutineScope, onFailure: (SpeechProblem) -> Unit): ReceiveChannel<AudioChunk> {
                val channel = Channel<AudioChunk>(20)
                voiceChannel = channel
                if (activeSubscriberCount.getAndIncrement() == 0) {
                    physicalRecordingStarted = true
                    physicalRecordingStopped = false
                }
                return channel
            }

            override suspend fun stop() {
                voiceChannel?.close()
                voiceChannel = null
                if (activeSubscriberCount.decrementAndGet() == 0) {
                    physicalRecordingStopped = true
                }
            }
        }

        override fun asRealtimeAudioSource(): RealtimeAudioSource = realtimeAudioSource
        override fun asAudioSource(): AudioSource = audioSource

        override fun release() {
            activeSubscriberCount.set(0)
            physicalRecordingStopped = true
            voiceChannel?.close()
            voiceChannel = null
            mossPcmCallback = null
        }

        fun dispatchFrame(pcmBytes: ByteArray, floatSamples: FloatArray) {
            when (activeDestination) {
                AudioDestination.MOSS -> {
                    mossPcmCallback?.invoke(pcmBytes)
                }
                AudioDestination.VOICE -> {
                    voiceChannel?.trySend(AudioChunk(floatSamples, 16000))
                }
                AudioDestination.MUTED -> {
                    // Dropped
                }
            }
        }
    }

    @Test
    fun testDestinationSwitchingDirectsFrames() = runTest {
        val router = TestableAudioCaptureRouter()
        val realtimeSource = router.asRealtimeAudioSource()
        val voiceSource = router.asAudioSource()

        var receivedMossBytes: ByteArray? = null
        realtimeSource.start(backgroundScope, { receivedMossBytes = it }, {})

        val voiceChannel = voiceSource.start(backgroundScope, {})

        val pcm = byteArrayOf(1, 2, 3, 4)
        val floats = floatArrayOf(0.1f, 0.2f)

        // 1. Destination MOSS: frames go to MOSS callback
        router.setDestination(AudioDestination.MOSS)
        router.dispatchFrame(pcm, floats)
        assertEquals(pcm, receivedMossBytes)
        assertTrue(voiceChannel.tryReceive().isFailure)

        // 2. Destination VOICE: frames go to voiceChannel
        receivedMossBytes = null
        router.setDestination(AudioDestination.VOICE)
        router.dispatchFrame(pcm, floats)
        assertNull(receivedMossBytes)
        val receivedVoice = voiceChannel.tryReceive().getOrNull()
        assertEquals(16000, receivedVoice?.sampleRate)
        assertEquals(0.1f, receivedVoice?.samples?.get(0) ?: 0f, 0.001f)

        // 3. Destination MUTED: frames are dropped
        router.setDestination(AudioDestination.MUTED)
        receivedMossBytes = null
        router.dispatchFrame(pcm, floats)
        assertNull(receivedMossBytes)
        assertTrue(voiceChannel.tryReceive().isFailure)

        realtimeSource.stop()
        voiceSource.stop()
    }

    @Test
    fun testReferenceCountedLifecycle() = runTest {
        val router = TestableAudioCaptureRouter()
        val realtimeSource = router.asRealtimeAudioSource()
        val voiceSource = router.asAudioSource()

        assertFalse(router.physicalRecordingStarted)

        // First subscriber starts -> recording starts
        realtimeSource.start(backgroundScope, {}, {})
        assertTrue(router.physicalRecordingStarted)
        assertFalse(router.physicalRecordingStopped)
        assertEquals(1, router.activeSubscriberCount.get())

        // Second subscriber starts -> recording stays active
        val channel = voiceSource.start(backgroundScope, {})
        assertEquals(2, router.activeSubscriberCount.get())
        assertFalse(router.physicalRecordingStopped)

        // First subscriber stops -> recording stays active because voiceSource is still active
        realtimeSource.stop()
        assertEquals(1, router.activeSubscriberCount.get())
        assertFalse(router.physicalRecordingStopped)

        // Second subscriber stops -> recording stops
        voiceSource.stop()
        assertEquals(0, router.activeSubscriberCount.get())
        assertTrue(router.physicalRecordingStopped)
    }

    @Test
    fun testReleaseShutsDownImmediately() = runTest {
        val router = TestableAudioCaptureRouter()
        router.asRealtimeAudioSource().start(backgroundScope, {}, {})
        router.asAudioSource().start(backgroundScope, {})
        assertEquals(2, router.activeSubscriberCount.get())

        router.release()
        assertEquals(0, router.activeSubscriberCount.get())
        assertTrue(router.physicalRecordingStopped)
        assertNull(router.mossPcmCallback)
        assertNull(router.voiceChannel)
    }
}
