package com.visualassistant.app.chat

import android.content.ContentResolver
import com.visualassistant.app.camera.CapturedPhoto
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Reads and removes only generated files in this app's dedicated private folder. */
internal class ConversationPhotoStore(private val directory: File, resolver: ContentResolver) {
    private val encoder = AndroidPhotoEncoder(resolver)
    private val imageKey = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$")

    fun fileFor(key: String): File {
        if (!imageKey.matches(key)) throw IOException("Invalid photo reference")
        return File(directory, key)
    }

    fun save(photo: CapturedPhoto): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Photo storage unavailable")
        val key = UUID.randomUUID().toString() + ".jpg"
        val result = fileFor(key)
        val pending = File(directory, "$key.part")
        try {
            val jpeg = encoder.encode(photo)
            FileOutputStream(pending).use { stream -> stream.write(jpeg); stream.fd.sync() }
            if (!pending.renameTo(result)) throw IOException("Could not save photo")
            return result
        } finally {
            if (pending.exists()) pending.delete()
        }
    }

    fun delete(key: String): Boolean {
        val file = fileFor(key)
        return !file.exists() || file.delete()
    }

    fun cleanupOrphans(referenced: Set<String>) {
        if (!directory.isDirectory) return
        val files = directory.listFiles() ?: throw IOException("Photo storage unavailable")
        files.forEach { file ->
            val pending = file.name.endsWith(".part") && imageKey.matches(file.name.removeSuffix(".part"))
            if (file.isFile && (pending || (imageKey.matches(file.name) && file.name !in referenced)) && !file.delete()) {
                throw IOException("Could not remove unused photo")
            }
        }
    }
}
