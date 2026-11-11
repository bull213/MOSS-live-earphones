package com.visualassistant.app.voice

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

/** Installs the bundled, pinned model into app-private storage for memory-mapped loading. */
internal class MoonshineModelInstaller(context: Context) {
    private val appContext = context.applicationContext

    fun ensureInstalled(): File = synchronized(installLock) {
        val manifest = appContext.assets.open("moonshine/manifest.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        require(manifest.getString("language") == "en")
        require(manifest.getString("architecture") == "SMALL_STREAMING")
        val revision = manifest.getString("revision")
        require(revision.matches(Regex("[A-Za-z0-9_-]+")))
        val files = manifest.getJSONArray("files")
        val root = File(appContext.filesDir, "moonshine/small-streaming-en")
        val destination = File(root, revision)
        if (isVerified(destination, files)) return@synchronized destination

        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create model directory")
        val needed = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("bytes") }
        if (StatFs(root.absolutePath).availableBytes < needed + 16L * 1024 * 1024) {
            throw IOException("Not enough free storage for the speech model")
        }
        val staging = File(root, "$revision.staging")
        if (staging.exists() && !staging.deleteRecursively()) throw IOException("Cannot clear incomplete model")
        if (!staging.mkdirs()) throw IOException("Cannot prepare speech model")
        try {
            for (index in 0 until files.length()) {
                val entry = files.getJSONObject(index)
                val name = safeName(entry.getString("name"))
                appContext.assets.open("moonshine/small-streaming-en/$name").use { input ->
                    File(staging, name).outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            if (!isVerified(staging, files)) throw IOException("Bundled speech model failed verification")
            if (destination.exists() && !destination.deleteRecursively()) throw IOException("Cannot replace speech model")
            if (!staging.renameTo(destination)) throw IOException("Cannot finish installing speech model")
            destination
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun isVerified(directory: File, files: org.json.JSONArray): Boolean {
        if (!directory.isDirectory) return false
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index)
            val file = File(directory, safeName(entry.getString("name")))
            if (!file.isFile || file.length() != entry.getLong("bytes")) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (actual != entry.getString("sha256")) return false
        }
        return true
    }

    private fun safeName(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
        return name
    }

    private companion object { val installLock = Any() }
}
