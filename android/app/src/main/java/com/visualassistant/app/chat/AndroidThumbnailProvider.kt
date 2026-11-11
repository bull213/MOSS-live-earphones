package com.visualassistant.app.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import com.visualassistant.app.camera.CapturedPhoto
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt

/** Small in-memory preview; re-encoding strips source metadata. */
internal class AndroidThumbnailProvider(
    private val resolver: ContentResolver,
    private val maxLongSide: Int = 640,
    private val maxBytes: Int = 256 * 1024,
) : ThumbnailProvider {
    override fun create(photo: CapturedPhoto): ByteArray {
        val uri = photo.localUri.toUri()
        val isFile = uri.scheme == null || uri.scheme == "file"
        val source = if (isFile) {
            val path = uri.path ?: photo.localUri.removePrefix("file://").removePrefix("file:")
            val file = java.io.File(path)
            if (file.isFile) ImageDecoder.createSource(file) else ImageDecoder.createSource(resolver, uri)
        } else {
            ImageDecoder.createSource(resolver, uri)
        }
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1.0, maxLongSide.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            for (quality in listOf(70, 50, 30)) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IOException("Thumbnail encoding failed")
                val bytes = output.toByteArray()
                if (bytes.size <= maxBytes) return bytes
            }
            throw IOException("Thumbnail too large")
        } finally {
            bitmap.recycle()
        }
    }
}
