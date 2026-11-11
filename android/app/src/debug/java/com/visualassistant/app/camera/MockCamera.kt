package com.visualassistant.app.camera

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Simulated camera for debug/tests. It does not represent any E12 protocol. */
internal class MockCamera(
    private val store: CaptureStore,
    jpegFixture: ByteArray,
    private val transferDelayMillis: Long = 0,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CameraSource {
    private val fixture = jpegFixture.copyOf()
    private val lock = Any()
    private var connected = true
    private var connectionGeneration = 0L
    private var failNext = false
    private val mutableAvailability = MutableStateFlow<CameraAvailability>(CameraAvailability.Ready(this))
    val availability = mutableAvailability.asStateFlow()

    init {
        require(fixture.isNotEmpty())
        require(transferDelayMillis >= 0)
    }

    fun failNextCapture() = synchronized(lock) { failNext = true }

    fun disconnect() = synchronized(lock) {
        connected = false
        connectionGeneration++
        mutableAvailability.value = CameraAvailability.Unavailable
    }

    fun reconnect() = synchronized(lock) {
        connected = true
        mutableAvailability.value = CameraAvailability.Ready(this)
    }

    override suspend fun capture(): CapturedPhoto {
        val (generation, fail) = synchronized(lock) {
            if (!connected) throw IOException("Mock camera disconnected")
            val fail = failNext
            failNext = false
            connectionGeneration to fail
        }
        var ownedFile: File? = null
        try {
            delay(transferDelayMillis)
            currentCoroutineContext().ensureActive()
            ensureConnected(generation)
            if (fail) throw IOException("Simulated capture failure")
            val file = withContext(ioDispatcher) {
                store.createFile().also { ownedFile = it; it.writeBytes(fixture) }
            }
            currentCoroutineContext().ensureActive()
            return synchronized(lock) {
                // Complete the capture atomically with respect to a disconnect.
                if (!connected || connectionGeneration != generation) throw IOException("Mock camera disconnected")
                store.photo(file)
            }
        } catch (failure: Throwable) {
            ownedFile?.let { file -> runCatching { store.discard(file) } }
            throw failure
        }
    }

    private fun ensureConnected(generation: Long) = synchronized(lock) {
        if (!connected || connectionGeneration != generation) throw IOException("Mock camera disconnected")
    }
}
