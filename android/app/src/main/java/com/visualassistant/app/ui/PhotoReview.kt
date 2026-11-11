package com.visualassistant.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.visualassistant.app.R
import com.visualassistant.app.camera.CapturedPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class ReviewImage(val bitmap: Bitmap? = null, val failed: Boolean = false)

@Composable
internal fun PhotoReview(photo: CapturedPhoto) {
    val context = LocalContext.current
    val image by produceState(ReviewImage(), photo.id) {
        value = try {
            ReviewImage(withContext(Dispatchers.IO) {
                // ImageDecoder applies encoded orientation; downsample only the display bitmap.
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, photo.localUri.toUri()),
                ) { decoder, info, _ ->
                    val scale = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReviewImage(failed = true)
        }
    }
    val bitmap = image.bitmap
    when {
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.photo_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 440.dp),
        )
        image.failed -> StatusText(stringResource(R.string.photo_unavailable), isError = true)
        else -> Text(stringResource(R.string.loading_photo))
    }
}
