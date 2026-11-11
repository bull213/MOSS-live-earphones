package com.visualassistant.app.knowledge

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Renders only flagged pages and recognizes their Latin text on the device. */
internal class AndroidPdfOcr {
    suspend fun recognize(
        pdf: File,
        pageNumbers: List<Int>,
        pageCount: Int,
        onProgress: (String) -> Unit,
    ): Map<String, String> {
        if (pageCount !in 1..MAX_PAGES || pageNumbers.size > pageCount || pageNumbers.any { it !in 1..pageCount }) {
            throw IOException("The PDF page count exceeds the 3,000 page limit.")
        }

        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount != pageCount) throw IOException("The PDF page count changed during import.")
                    val results = LinkedHashMap<String, String>()
                    for (pageNumber in pageNumbers) {
                        currentCoroutineContext().ensureActive()
                        onProgress("Reading scanned page $pageNumber of ${renderer.pageCount} on this phone…")
                        results[pageNumber.toString()] = runInterruptible {
                            recognizePage(renderer, pageNumber - 1, recognizer)
                        }
                    }
                    return results
                }
            } finally {
                recognizer.close()
            }
        } finally {
            descriptor.close()
        }
    }

    private fun recognizePage(renderer: PdfRenderer, pageIndex: Int, recognizer: TextRecognizer): String {
        renderer.openPage(pageIndex).use { page ->
            val width = page.width.toDouble()
            val height = page.height.toDouble()
            if (width <= 0.0 || height <= 0.0) throw IOException("The PDF contains a page with invalid dimensions.")
            val scale = minOf(MAX_RENDER_EDGE / maxOf(width, height), kotlin.math.sqrt(MAX_RENDER_PIXELS / (width * height)))
            if (!scale.isFinite() || scale <= 0.0) throw IOException("The PDF page dimensions exceed the phone's OCR limits.")
            val bitmap = Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            try {
                page.render(bitmap, null, Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return Tasks.await(
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    PAGE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                ).text
            } finally {
                bitmap.recycle()
            }
        }
    }

    private companion object {
        const val MAX_PAGES = 3000
        const val MAX_RENDER_EDGE = 2200.0
        const val MAX_RENDER_PIXELS = 5_000_000.0
        const val PAGE_TIMEOUT_SECONDS = 90L
    }
}
