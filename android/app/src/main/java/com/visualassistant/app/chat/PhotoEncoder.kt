package com.visualassistant.app.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import com.visualassistant.app.camera.CapturedPhoto
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt

internal fun interface PhotoEncoder {
    fun encode(photo: CapturedPhoto): ByteArray
}

/** Re-encoding applies image orientation and removes source EXIF metadata. */
internal class AndroidPhotoEncoder(private val resolver: ContentResolver) : PhotoEncoder {
    override fun encode(photo: CapturedPhoto): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, photo.localUri.toUri())) { decoder, info, _ ->
            val scale = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            for (quality in listOf(85, 70, 55, 40)) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IOException("Image encoding failed")
                }
                val encoded = output.toByteArray()
                if (encoded.size <= 4 * 1024 * 1024) return encoded
            }
            throw IOException("Image too large")
        } finally {
            bitmap.recycle()
        }
    }
}
