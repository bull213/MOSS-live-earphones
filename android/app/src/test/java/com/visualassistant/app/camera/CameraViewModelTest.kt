package com.visualassistant.app.camera

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun store() = CaptureStore(File(temporary.root, "captures"))

    @Test fun initializesBeforeCaptureAndCleansOldFiles() = runTest(main.dispatcher) {
        val directory = temporary.newFolder("captures")
        val stale = File(directory, "capture-stale.jpg").apply { writeText("old") }
        val model = CameraViewModel(CaptureStore(directory), main.dispatcher)
        var calls = 0
        model.capture { calls++; error("Must not capture before initialization") }
        assertEquals(0, calls)
        runCurrent()
        assertTrue(model.state.value.initialized)
        assertFalse(stale.exists())
    }

    @Test fun duplicateTapsProduceOnlyOneCapture() = runTest(main.dispatcher) {
        val store = store()
        val model = CameraViewModel(store, main.dispatcher)
        runCurrent()
        val gate = CompletableDeferred<CapturedPhoto>()
        var calls = 0
        val camera = CameraSource { calls++; gate.await() }
        model.capture(camera)
        model.capture(camera)
        assertTrue(model.state.value.isCapturing)
        runCurrent()
        assertEquals(1, calls)
        val photo = store.photo(store.createFile())
        gate.complete(photo)
        runCurrent()
        assertEquals(photo, model.state.value.photo)
        assertFalse(model.state.value.isCapturing)
        model.capture(camera)
        assertEquals(1, calls)
    }

    @Test fun retakeDeletesPhotoAndAllowsNextCapture() = runTest(main.dispatcher) {
        val store = store()
        val model = CameraViewModel(store, main.dispatcher)
        runCurrent()
        val first = store.createFile()
        model.capture { store.photo(first) }
        runCurrent()
        model.retake()
        model.retake()
        assertTrue(model.state.value.isDeleting)
        runCurrent()
        assertFalse(first.exists())
        assertNull(model.state.value.photo)
        model.capture { store.photo(store.createFile()) }
        runCurrent()
        assertNotNull(model.state.value.photo)
    }

    @Test fun captureFailureOffersAnotherAttempt() = runTest(main.dispatcher) {
        val store = store()
        val model = CameraViewModel(store, main.dispatcher)
        runCurrent()
        model.capture { throw IOException("private device details") }
        runCurrent()
        assertEquals(CaptureProblem.CAPTURE, model.state.value.problem)
        assertFalse(model.state.value.isCapturing)
        model.capture { store.photo(store.createFile()) }
        runCurrent()
        assertNotNull(model.state.value.photo)
        assertNull(model.state.value.problem)
    }

    @Test fun backgroundingCancelsCaptureAndAllowsRecovery() = runTest(main.dispatcher) {
        val store = store()
        val model = CameraViewModel(store, main.dispatcher)
        runCurrent()
        val gate = CompletableDeferred<CapturedPhoto>()
        model.capture { gate.await() }
        runCurrent()
        model.cameraPaused()
        runCurrent()
        assertEquals(CaptureProblem.INTERRUPTED, model.state.value.problem)
        assertFalse(model.state.value.isCapturing)
        model.capture { store.photo(store.createFile()) }
        runCurrent()
        assertNotNull(model.state.value.photo)
    }

    @Test fun pauseAndRepeatedPrepareKeepCompletedReview() = runTest(main.dispatcher) {
        val store = store()
        val model = CameraViewModel(store, main.dispatcher)
        runCurrent()
        val file = store.createFile()
        val photo = store.photo(file)
        model.capture { photo }
        runCurrent()
        model.cameraPaused()
        model.prepare()
        runCurrent()
        assertEquals(photo, model.state.value.photo)
        assertTrue(file.exists())
    }

    @Test fun failedStartupCanRecoverWithoutRestart() = runTest(main.dispatcher) {
        val directory = temporary.newFile("captures")
        val model = CameraViewModel(CaptureStore(directory), main.dispatcher)
        runCurrent()
        assertFalse(model.state.value.initialized)
        assertEquals(CaptureProblem.STORAGE, model.state.value.problem)
        assertTrue(directory.delete())
        model.prepare()
        runCurrent()
        assertTrue(model.state.value.initialized)
        assertNull(model.state.value.problem)
    }

    @Test fun failedDeletionKeepsReviewAndCanBeRetried() = runTest(main.dispatcher) {
        val store = store()
        val model = CameraViewModel(store, main.dispatcher)
        runCurrent()
        val file = store.createFile()
        val photo = store.photo(file)
        model.capture { photo }
        runCurrent()
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        val blocker = File(file, "blocker").apply { writeText("test") }
        model.retake()
        runCurrent()
        assertEquals(photo, model.state.value.photo)
        assertEquals(CaptureProblem.STORAGE, model.state.value.problem)
        assertFalse(model.state.value.isDeleting)
        assertTrue(blocker.delete())
        model.retake()
        runCurrent()
        assertNull(model.state.value.photo)
    }
}
