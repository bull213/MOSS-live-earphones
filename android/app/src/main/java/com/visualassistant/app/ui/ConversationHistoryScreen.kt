package com.visualassistant.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visualassistant.app.R
import com.visualassistant.app.chat.ConversationSummary
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ConversationHistoryScreen(
    conversations: List<ConversationSummary>, busy: Boolean, storageError: Boolean,
    onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit, onDelete: (String) -> Unit,
    onRetryStorage: () -> Unit = {},
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    if (pendingDelete != null) {
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_description)) },
            confirmButton = { TextButton(onClick = { pendingDelete?.let(onDelete); pendingDelete = null }) { Text(stringResource(R.string.history_confirm_delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.chat_cancel)) } })
    }
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
            }
            item { SecondaryButton(stringResource(R.string.history_back), enabled = !busy, onClick = onBack) }
            item { LargeButton(stringResource(R.string.history_new), enabled = !busy, onClick = onNew) }
            if (busy) item { StatusText(stringResource(R.string.history_loading)) }
            if (storageError) item {
                Column {
                    StatusText(stringResource(R.string.history_storage_failed), isError = true)
                    TextButton(onClick = onRetryStorage, enabled = !busy) { Text(stringResource(R.string.retry_storage)) }
                }
            }
            item { Text(stringResource(R.string.history_recent), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            item { Text(stringResource(R.string.recent_notice), style = MaterialTheme.typography.bodyMedium) }
            if (!busy && conversations.none { !it.saved }) item { Text(stringResource(R.string.history_recent_empty)) }
            items(conversations.filter { !it.saved }, key = { it.id }) { conversation ->
                HistoryEntry(conversation, busy, onOpen) { pendingDelete = conversation.id }
            }
            item { Text(stringResource(R.string.history_saved), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            if (!busy && conversations.none { it.saved }) item { Text(stringResource(R.string.history_saved_empty)) }
            items(conversations.filter { it.saved }, key = { it.id }) { conversation ->
                HistoryEntry(conversation, busy, onOpen) { pendingDelete = conversation.id }
            }
        }
    }
}

@Composable
private fun HistoryEntry(conversation: ConversationSummary, busy: Boolean, onOpen: (String) -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { onOpen(conversation.id) }
                .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(conversation.title.ifBlank { stringResource(R.string.history_untitled) },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(conversation.preview.ifBlank { stringResource(R.string.history_no_messages) },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .format(conversation.lastActivity.atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.labelLarge)
            }
            Row { TextButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.history_delete)) } }
        }
    }
}
