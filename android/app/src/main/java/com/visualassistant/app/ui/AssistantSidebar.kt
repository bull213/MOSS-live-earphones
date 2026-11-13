package com.visualassistant.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visualassistant.app.R
import com.visualassistant.app.chat.ChatUiState
import com.visualassistant.app.network.NotebookItem

@Composable
internal fun AssistantSidebar(
    state: ChatUiState,
    busy: Boolean,
    showingSettings: Boolean,
    onRecent: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
    onImportBook: () -> Unit,
    onSelectNotebook: (NotebookItem) -> Unit = {},
    onLoadNotebooks: () -> Unit = {},
) {
    var showingCollections by rememberSaveable { mutableStateOf(false) }

    if (showingCollections) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showingCollections = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("← Back")
                    }
                    Text(
                        "Collections",
                        modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (state.loadingNotebooks) {
                item {
                    Text(
                        "Loading notebooks…",
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.notebooks.isEmpty()) {
                item {
                    Text(
                        "No connected notebooks found. Connect NotebookLM in Settings.",
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    SidebarAction("Refresh Notebooks", enabled = !busy, onClick = onLoadNotebooks)
                }
            } else {
                items(state.notebooks, key = { it.id }) { notebook ->
                    SidebarAction(
                        label = notebook.title,
                        enabled = !busy,
                        selected = state.activeNotebook?.id == notebook.id,
                        onClick = {
                            onSelectNotebook(notebook)
                        },
                    )
                }
                item {
                    SidebarAction("↻ Refresh list", enabled = !busy, onClick = onLoadNotebooks)
                }
            }
        }
        return
    }

    val saved = state.history.filter { it.saved }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(stringResource(R.string.chat_title), modifier = Modifier.padding(12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item { SidebarAction(stringResource(R.string.recent_memory_title), enabled = !busy,
            selected = !state.viewingSaved && !showingSettings && state.activeNotebook == null, onClick = onRecent) }
        item { SidebarAction(stringResource(R.string.history_new), enabled = !busy, onClick = onNew) }
        item {
            val label = if (state.activeNotebook != null) "Collections: ${state.activeNotebook.title}" else "Collections"
            SidebarAction(
                label = label,
                enabled = !busy,
                selected = state.activeNotebook != null,
                onClick = {
                    showingCollections = true
                    onLoadNotebooks()
                },
            )
        }
        item { SidebarAction(stringResource(R.string.book_import), enabled = !busy, onClick = onImportBook) }
        item {
            Text(stringResource(R.string.history_saved), Modifier.padding(start = 12.dp, top = 18.dp, bottom = 4.dp)
                .semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        }
        if (saved.isEmpty()) item {
            Text(stringResource(R.string.sidebar_empty), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(saved, key = { it.id }) { conversation ->
            val title = conversation.title.ifBlank { stringResource(R.string.history_untitled) }
            val deleteLabel = stringResource(R.string.history_delete_named, title)
            Surface(color = if (state.viewingSaved && state.sessionId == conversation.id && !showingSettings)
                MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).testTag("saved-${conversation.id}").clickable(enabled = !busy) { onOpen(conversation.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(title,
                            style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onDelete(conversation.id) }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = deleteLabel }) { Text(stringResource(R.string.history_delete)) }
                }
            }
        }
        item { SidebarAction(stringResource(R.string.settings_title), enabled = !busy, selected = showingSettings, onClick = onSettings) }
    }
}

@Composable
private fun SidebarAction(label: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(16.dp)) {
        TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

