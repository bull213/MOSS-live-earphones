package com.visualassistant.app.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.visualassistant.app.camera.CapturedPhoto
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PhotoEncoderTest {
    @Test fun resizesAndRotatesSyntheticJpegWithoutCopyingExif() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "demo-encoder-test-${UUID.randomUUID()}.jpg")
        try {
            val source = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.BLUE)
                file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                source.recycle()
            }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_MAKE, "synthetic-test-camera")
                saveAttributes()
            }
            val originalSize = file.length()
            val photo = CapturedPhoto(UUID.randomUUID().toString(), file.toURI().toString(), Instant.EPOCH)
            val encoded = AndroidPhotoEncoder(context.contentResolver).encode(photo)
            assertTrue(encoded.size <= 4 * 1024 * 1024)
            val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            assertNotNull(decoded)
            try {
                assertEquals(800, decoded.width)
                assertEquals(1600, decoded.height)
            } finally {
                decoded.recycle()
            }
            val outputExif = ExifInterface(ByteArrayInputStream(encoded))
            assertNull(outputExif.getAttribute(ExifInterface.TAG_MAKE))
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED,
                outputExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            assertEquals(originalSize, file.length())
            assertEquals("synthetic-test-camera", ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_MAKE))
        } finally {
            file.delete()
        }
    }
}
