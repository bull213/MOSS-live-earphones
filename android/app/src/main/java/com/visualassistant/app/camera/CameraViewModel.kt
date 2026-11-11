package com.visualassistant.app.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CaptureProblem { CAPTURE, INTERRUPTED, STORAGE }

data class CameraUiState(
    val initialized: Boolean = false,
    val isCapturing: Boolean = false,
    val isDeleting: Boolean = false,
    val photo: CapturedPhoto? = null,
    val problem: CaptureProblem? = null,
)

class CameraViewModel(
    private val store: CaptureStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CameraUiState())
    val state = mutableState.asStateFlow()
    private var captureJob: Job? = null
    private var preparing = false

    init { prepare() }

    fun prepare() {
        if (preparing || state.value.initialized) return
        preparing = true
        mutableState.value = CameraUiState()
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { store.prepare() }
                mutableState.value = CameraUiState(initialized = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = CameraUiState(problem = CaptureProblem.STORAGE)
            } finally {
                preparing = false
            }
        }
    }

    fun capture(source: CameraSource) {
        val current = state.value
        if (!current.initialized || current.isCapturing || current.isDeleting || current.photo != null) return
        mutableState.value = current.copy(isCapturing = true, problem = null)
        captureJob = viewModelScope.launch {
            try {
                val photo = source.capture()
                if (!isActive) {
                    withContext(NonCancellable + ioDispatcher) { store.discard(photo) }
                    return@launch
                }
                mutableState.value = CameraUiState(initialized = true, photo = photo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = CameraUiState(initialized = true, problem = CaptureProblem.CAPTURE)
            }
        }
    }

    fun cameraPaused() {
        if (!state.value.isCapturing) return
        captureJob?.cancel()
        mutableState.value = CameraUiState(initialized = true, problem = CaptureProblem.INTERRUPTED)
    }

    fun retake() {
        val current = state.value
        val photo = current.photo ?: return
        if (current.isDeleting) return
        mutableState.value = current.copy(isDeleting = true, problem = null)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { store.discard(photo) }
                mutableState.value = CameraUiState(initialized = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = current.copy(problem = CaptureProblem.STORAGE)
            }
        }
    }
}
