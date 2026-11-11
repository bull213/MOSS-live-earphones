package com.visualassistant.app.camera

import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Only this app's dedicated cache directory is ever cleaned. No gallery access. */
class CaptureStore(private val directory: File) {
    fun prepare() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create capture cache")
        }
        val entries = directory.listFiles() ?: throw IOException("Cannot read capture cache")
        entries.filter { it.isFile && it.name.startsWith("capture-") && it.extension == "jpg" }
            .forEach { file ->
                if (!file.delete()) throw IOException("Cannot remove abandoned capture")
            }
    }

    fun createFile(): File = File(directory, "capture-" + UUID.randomUUID() + ".jpg").also {
        if (!it.createNewFile()) throw IOException("Cannot create capture")
    }

    fun photo(file: File): CapturedPhoto {
        requireOwned(file)
        return CapturedPhoto(file.nameWithoutExtension.removePrefix("capture-"), file.toURI().toString(), Instant.now())
    }

    fun discard(photo: CapturedPhoto) = discard(File(URI(photo.localUri)))

    fun discard(file: File) {
        requireOwned(file)
        if (file.exists() && !file.delete()) throw IOException("Cannot remove capture")
    }

    private fun requireOwned(file: File) {
        require(file.canonicalFile.parentFile == directory.canonicalFile &&
            file.name.startsWith("capture-") && file.extension == "jpg") {
            "File is outside the capture cache"
        }
    }
}
