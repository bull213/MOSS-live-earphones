package com.visualassistant.app.knowledge

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.visualassistant.app.network.BackendEndpoint
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Coordinates temporary-file handling, page inspection, on-device OCR, and RAG import. */
internal class PhoneBookImporter(
    private val resolver: ContentResolver,
    private val cacheDirectory: File,
    private val endpoint: BackendEndpoint? = null,
    private val importApi: PhoneBookImportApi = PhoneBookImportApi(endpoint),
    private val pageOcr: AndroidPdfOcr = AndroidPdfOcr(),
) {
    suspend fun import(uri: Uri, onProgress: (String) -> Unit): BookImportResult = withContext(Dispatchers.IO) {
        val localPdf = File.createTempFile("book-import-", ".pdf", cacheDirectory)
        try {
            runInterruptible { copyPdf(uri, localPdf) }
            currentCoroutineContext().ensureActive()

            onProgress("Checking PDF pages…")
            val inspection = runInterruptible { importApi.inspect(localPdf, displayName(uri)) }
            val pageTexts = if (inspection.pagesRequiringOcr.isEmpty()) emptyMap() else {
                pageOcr.recognize(localPdf, inspection.pagesRequiringOcr, inspection.pageCount, onProgress)
            }

            currentCoroutineContext().ensureActive()
            onProgress("Adding book to your library…")
            runInterruptible { importApi.commit(inspection.jobId, pageTexts) }
        } finally {
            localPdf.delete()
        }
    }

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) return cursor.getString(nameColumn)?.take(MAX_NAME_LENGTH) ?: DEFAULT_NAME
            }
        }
        return DEFAULT_NAME
    }

    private fun copyPdf(uri: Uri, target: File) {
        val input = resolver.openInputStream(uri) ?: throw IOException("The selected PDF could not be opened.")
        input.use { source ->
            target.outputStream().buffered().use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesCopied = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    bytesCopied += count
                    if (bytesCopied > MAX_PDF_BYTES) throw IOException("The PDF exceeds the 64 MiB limit.")
                    destination.write(buffer, 0, count)
                }
                if (bytesCopied < PDF_HEADER.size) throw IOException("The selected file is empty or is not a PDF.")
            }
        }
        target.inputStream().buffered().use { input ->
            val header = ByteArray(PDF_HEADER.size)
            if (input.read(header) != header.size || !header.contentEquals(PDF_HEADER)) {
                throw IOException("Choose a PDF file.")
            }
        }
    }

    private companion object {
        const val MAX_PDF_BYTES = 64L * 1024 * 1024
        const val MAX_NAME_LENGTH = 180
        const val DEFAULT_NAME = "book.pdf"
        val PDF_HEADER = "%PDF-".toByteArray()
    }
}
