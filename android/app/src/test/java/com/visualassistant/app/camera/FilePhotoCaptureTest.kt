package com.visualassistant.app.camera

import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FilePhotoCaptureTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun store() = CaptureStore(File(temporary.root, "captures")).also { it.prepare() }
    private fun capture(store: CaptureStore) = FilePhotoCapture(store, main.dispatcher, main.dispatcher)

    private class PendingCamera {
        lateinit var file: File
        lateinit var saved: () -> Unit
        lateinit var failed: (Exception) -> Unit

        fun start(file: File, saved: () -> Unit, failed: (Exception) -> Unit) {
            this.file = file
            this.saved = saved
            this.failed = failed
        }
    }

    /** Lets a test stop between IO file creation and delivery back to Main. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
    }

    @Test fun cancellationAfterAllocationDeletesFileBeforeCameraStarts() = runTest(main.dispatcher) {
        val store = store()
        val io = QueuedDispatcher()
        var cameraStarted = false
        val pending = async {
            FilePhotoCapture(store, io, main.dispatcher).capture { _, _, _ -> cameraStarted = true }
        }
        runCurrent()
        io.runNext()
        val allocated = File(temporary.root, "captures").listFiles()!!.single()
        assertTrue(allocated.exists())
        pending.cancel()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertFalse(cameraStarted)
        assertFalse(allocated.exists())
    }

    @Test fun successfulCaptureRetainsFileForReview() = runTest(main.dispatcher) {
        val store = store()
        val camera = PendingCamera()
        val pending = async { capture(store).capture(camera::start) }
        runCurrent()
        camera.file.writeText("captured image")
        camera.saved()
        runCurrent()
        assertEquals(camera.file.toURI().toString(), pending.await().localUri)
        assertEquals("captured image", camera.file.readText())
    }

    @Test fun callbackFailureDeletesFileAndPropagatesError() = runTest(main.dispatcher) {
        val store = store()
        val camera = PendingCamera()
        val pending = async { runCatching { capture(store).capture(camera::start) } }
        runCurrent()
        val failure = IOException("capture failed")
        camera.failed(failure)
        runCurrent()
        // Coroutine stack-trace recovery may copy an exception across dispatcher boundaries.
        val actual = pending.await().exceptionOrNull()
        assertTrue(actual is IOException)
        assertEquals(failure.message, actual?.message)
        assertFalse(camera.file.exists())
    }

    @Test fun synchronousCameraFailureDeletesAllocatedFile() = runTest(main.dispatcher) {
        val store = store()
        val failure = IOException("camera unavailable")
        val result = runCatching { capture(store).capture { _, _, _ -> throw failure } }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertTrue(File(temporary.root, "captures").listFiles()!!.isEmpty())
    }

    @Test fun cancellationAfterSaveBeforeDeliveryDeletesFile() = runTest(main.dispatcher) {
        val store = store()
        val camera = PendingCamera()
        val pending = async { capture(store).capture(camera::start) }
        runCurrent()
        camera.saved()
        pending.cancel()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertFalse(camera.file.exists())
    }

    @Test fun lateSaveAfterCancellationDeletesOldFileAndPreservesNewCapture() = runTest(main.dispatcher) {
        val store = store()
        val oldCamera = PendingCamera()
        val first = async { capture(store).capture(oldCamera::start) }
        runCurrent()
        first.cancel()
        runCurrent()
        assertFalse(oldCamera.file.exists())

        val newCamera = PendingCamera()
        val second = async { capture(store).capture(newCamera::start) }
        runCurrent()
        newCamera.file.writeText("new photograph")
        newCamera.saved()
        runCurrent()
        assertEquals(newCamera.file.toURI().toString(), second.await().localUri)

        // CameraX can finish writing an old request even after the coroutine is cancelled.
        oldCamera.file.writeText("late photograph")
        oldCamera.saved()
        assertFalse(oldCamera.file.exists())
        assertEquals("new photograph", newCamera.file.readText())
    }

    @Test fun lateFailureAfterCancellationRemovesPartialFile() = runTest(main.dispatcher) {
        val store = store()
        val camera = PendingCamera()
        val pending = async { capture(store).capture(camera::start) }
        runCurrent()
        pending.cancel()
        runCurrent()
        camera.file.writeText("partial image")
        camera.failed(IOException("late failure"))
        assertTrue(pending.isCancelled)
        assertFalse(camera.file.exists())
    }
}
