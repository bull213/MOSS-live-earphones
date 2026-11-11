package com.visualassistant.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.visualassistant.app.R
import com.visualassistant.app.camera.CapturedPhoto
import com.visualassistant.app.chat.AndroidThumbnailProvider
import com.visualassistant.app.chat.ChatAction
import com.visualassistant.app.chat.ChatKind
import com.visualassistant.app.chat.ChatMessage
import com.visualassistant.app.chat.ChatSource
import com.visualassistant.app.chat.ChatStatus
import com.visualassistant.app.voice.PendingVoiceText
import com.visualassistant.app.voice.VoiceSaveStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VoiceTranscriptPendingBubble(pending: PendingVoiceText, onSave: (String) -> Unit, onDiscard: (String) -> Unit) {
    VoiceTranscriptSurface {
        Text(stringResource(R.string.chat_voice_transcript), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(pending.text.text, style = MaterialTheme.typography.bodyLarge)
        if (pending.status == VoiceSaveStatus.SAVING) {
            StatusText(stringResource(R.string.voice_saving))
        } else {
            StatusText(stringResource(R.string.voice_unsaved), isError = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSave(pending.text.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.voice_save_text))
                }
                TextButton(onClick = { onDiscard(pending.text.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.voice_discard_text))
                }
            }
        }
    }
}

@Composable
private fun VoiceTranscriptSurface(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(.86f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
internal fun ChatBubble(message: ChatMessage, onPhoto: () -> Unit, actionsEnabled: Boolean, onUseTranscript: (String) -> Unit = {}) {
    val user = message.source == ChatSource.PHONE || message.source == ChatSource.EARPHONES
    val time = remember(message.timestamp) {
        DateTimeFormatter.ofPattern("HH:mm").format(message.timestamp.atZone(ZoneId.systemDefault()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(22.dp), tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(if (user) .86f else 1f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = when (message.kind) {
                    ChatKind.VOICE_TRANSCRIPT -> R.string.chat_voice_transcript
                    ChatKind.PHOTO -> R.string.chat_photo_label
                    ChatKind.ASSISTANT -> R.string.chat_assistant_label
                    ChatKind.SYSTEM -> R.string.chat_system_label
                    ChatKind.TEXT -> R.string.chat_you_label
                }
                Text("${stringResource(label)}  ·  $time", style = MaterialTheme.typography.labelLarge,
                    color = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold)
                when (message.status) {
                    ChatStatus.SENDING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(if (message.kind == ChatKind.PHOTO) R.string.chat_preparing_photo else R.string.chat_sending),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    ChatStatus.FAILED -> StatusText(stringResource(if (message.kind == ChatKind.PHOTO) R.string.chat_photo_failed else R.string.chat_send_failed), isError = true)
                    ChatStatus.PARTIAL -> {
                        StatusText(stringResource(R.string.chat_partial_reply), isError = true)
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    ChatStatus.READY -> {
                        if (message.kind == ChatKind.PHOTO) ChatPhoto(message.thumbnail, message.photo)
                        else Text(message.text, style = MaterialTheme.typography.bodyLarge,
                            color = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        if (message.kind == ChatKind.VOICE_TRANSCRIPT) TextButton(onClick = { onUseTranscript(message.text) }, enabled = actionsEnabled) {
                            Text(stringResource(R.string.chat_use_transcript))
                        }
                        message.actions.forEach { action ->
                            when (action) {
                                ChatAction.TAKE_PHOTO -> TextButton(onClick = onPhoto, enabled = actionsEnabled) {
                                    Text(stringResource(R.string.task_photo))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPhoto(bytes: ByteArray?, photo: CapturedPhoto?) {
    val context = LocalContext.current
    val preview by produceState<PhotoPreview>(PhotoPreview(bytes, bytes == null && photo != null), bytes, photo?.localUri) {
        if (bytes == null && photo != null) {
            value = try {
                PhotoPreview(withContext(Dispatchers.IO) { AndroidThumbnailProvider(context.contentResolver).create(photo) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PhotoPreview()
            }
        }
    }
    val data = preview.bytes
    val bitmap = remember(data) { data?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    var expanded by remember { mutableStateOf(false) }
    if (expanded && bitmap != null) {
        Dialog(onDismissRequest = { expanded = false }) {
            Surface(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Image(bitmap, contentDescription = stringResource(R.string.photo_description), modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), contentScale = ContentScale.Fit)
                    TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.chat_close)) }
                }
            }
        }
    }
    if (bitmap != null) {
        val label = stringResource(R.string.chat_open_photo)
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = label) { expanded = true }) {
            Image(bitmap, contentDescription = stringResource(R.string.photo_description), modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp), contentScale = ContentScale.Fit)
        }
    } else if (preview.loading) StatusText(stringResource(R.string.chat_preparing_photo))
    else StatusText(stringResource(R.string.chat_photo_failed), isError = true)
}

private data class PhotoPreview(val bytes: ByteArray? = null, val loading: Boolean = false)
