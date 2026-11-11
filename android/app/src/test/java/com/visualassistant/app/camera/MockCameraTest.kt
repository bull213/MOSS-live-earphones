package com.visualassistant.app.camera

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MockCameraTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): ByteArray = ByteArrayOutputStream().use { output ->
        assertTrue(ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "jpeg", output))
        output.toByteArray()
    }

    private fun store() = CaptureStore(File(temporary.root, "captures")).also { it.prepare() }
    private fun files() = File(temporary.root, "captures").listFiles()!!.toList()

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
    }

    @Test fun successfulCaptureReturnsOwnedValidJpegAndMetadata() = runTest(main.dispatcher) {
        val store = store()
        val camera = MockCamera(store, fixture(), ioDispatcher = main.dispatcher)
        val photo = camera.capture()
        val file = File(URI(photo.localUri))
        assertEquals(UUID.fromString(photo.id).toString(), photo.id)
        assertTrue(photo.capturedAt.epochSecond > 0)
        assertEquals(files().single(), file)
        assertNotNull(ImageIO.read(file))
        store.discard(photo)
        assertTrue(files().isEmpty())
    }

    @Test fun transferDelayDoesNotDeliverBeforeTwoSeconds() = runTest(main.dispatcher) {
        val camera = MockCamera(store(), fixture(), 2_000, main.dispatcher)
        val pending = async { camera.capture() }
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(pending.isCompleted)
        assertTrue(files().isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertTrue(pending.isCompleted)
        assertTrue(File(URI(pending.await().localUri)).exists())
    }

    @Test fun failedCaptureCanBeRetried() = runTest(main.dispatcher) {
        val camera = MockCamera(store(), fixture(), ioDispatcher = main.dispatcher)
        camera.failNextCapture()
        assertTrue(runCatching { camera.capture() }.exceptionOrNull() is IOException)
        assertTrue(files().isEmpty())
        assertTrue(File(URI(camera.capture().localUri)).exists())
    }

    @Test fun cancellationDuringDelayDoesNotCreateAFileLater() = runTest(main.dispatcher) {
        val camera = MockCamera(store(), fixture(), 2_000, main.dispatcher)
        val pending = async { camera.capture() }
        runCurrent()
        advanceTimeBy(1_000)
        pending.cancel()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(files().isEmpty())
    }

    @Test fun disconnectBeforeCaptureFailsAndReconnects() = runTest(main.dispatcher) {
        val camera = MockCamera(store(), fixture(), ioDispatcher = main.dispatcher)
        camera.disconnect()
        assertEquals(CameraAvailability.Unavailable, camera.availability.value)
        assertTrue(runCatching { camera.capture() }.exceptionOrNull() is IOException)
        assertTrue(files().isEmpty())
        camera.reconnect()
        assertEquals(CameraAvailability.Ready(camera), camera.availability.value)
        assertTrue(File(URI(camera.capture().localUri)).exists())
    }

    @Test fun disconnectDuringDelayRejectsStaleResultEvenAfterReconnection() = runTest(main.dispatcher) {
        val camera = MockCamera(store(), fixture(), 2_000, main.dispatcher)
        val pending = async { runCatching { camera.capture() } }
        runCurrent()
        camera.disconnect()
        camera.reconnect()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(pending.await().exceptionOrNull() is IOException)
        assertTrue(files().isEmpty())
        val retry = async { camera.capture() }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(File(URI(retry.await().localUri)).exists())
    }

    @Test fun cancellationAfterFileAllocationRemovesOnlyThatFile() = runTest(main.dispatcher) {
        val store = store()
        val existing = store.createFile().apply { writeText("other capture") }
        val io = QueuedDispatcher()
        val camera = MockCamera(store, fixture(), ioDispatcher = io)
        val pending = async { camera.capture() }
        runCurrent()
        io.runNext()
        assertEquals(2, files().size)
        pending.cancel()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(listOf(existing), files())
        assertEquals("other capture", existing.readText())
    }

    @Test fun disconnectAfterFileAllocationDeletesStaleCapture() = runTest(main.dispatcher) {
        val io = QueuedDispatcher()
        val camera = MockCamera(store(), fixture(), ioDispatcher = io)
        val pending = async { runCatching { camera.capture() } }
        runCurrent()
        io.runNext()
        assertEquals(1, files().size)
        camera.disconnect()
        camera.reconnect()
        runCurrent()
        assertTrue(pending.await().exceptionOrNull() is IOException)
        assertTrue(files().isEmpty())
    }
}
