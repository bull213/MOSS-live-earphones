package com.visualassistant.app.camera

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

/** Owns a capture file until delivery, including callbacks arriving after cancellation. */
internal class FilePhotoCapture(
    private val store: CaptureStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cameraDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    suspend fun capture(
        startCapture: (file: File, onSaved: () -> Unit, onError: (Exception) -> Unit) -> Unit,
    ): CapturedPhoto {
        var ownedFile: File? = null
        try {
            val file = withContext(ioDispatcher) {
                // Record ownership before withContext can discard its result on cancellation.
                store.createFile().also { ownedFile = it }
            }
            return withContext(cameraDispatcher) {
                suspendCancellableCoroutine<CapturedPhoto> { continuation ->
                    val discard = { runCatching { store.discard(file) }; Unit }
                    continuation.invokeOnCancellation { discard() }
                    startCapture(file, onSaved@{
                        if (!continuation.isActive) {
                            discard()
                            return@onSaved
                        }
                        val photo = try {
                            store.photo(file)
                        } catch (failure: Exception) {
                            discard()
                            continuation.resumeWithException(failure)
                            return@onSaved
                        }
                        continuation.resume(photo) { _, _, _ -> discard() }
                    }, { failure ->
                        discard()
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    })
                }
            }
        } catch (failure: Exception) {
            ownedFile?.let { file -> runCatching { store.discard(file) } }
            throw failure
        }
    }
}
