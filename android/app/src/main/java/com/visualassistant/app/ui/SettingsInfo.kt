package com.visualassistant.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.visualassistant.app.R
import com.visualassistant.app.network.BackendEndpoint
import com.visualassistant.app.network.ServerScanner
import kotlinx.coroutines.launch

@Composable
internal fun SettingsInfo(
    onBack: () -> Unit,
    endpoint: BackendEndpoint? = null,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_back)) } }
            item {
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (endpoint != null) {
                item { ServerConnectionSection(endpoint) }
            }
            item { InfoSection(stringResource(R.string.settings_privacy_title), stringResource(R.string.settings_privacy_body)) }
            item { InfoSection(stringResource(R.string.settings_memory_title), stringResource(R.string.settings_memory_body)) }
            item { InfoSection(stringResource(R.string.settings_about_title), stringResource(R.string.settings_about_body)) }
        }
    }
}

@Composable
private fun ServerConnectionSection(endpoint: BackendEndpoint) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hostInput by remember(endpoint.host) { mutableStateOf(endpoint.host) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    val isDefault = endpoint.host == BackendEndpoint.DEFAULT_HOST

    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_server_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.settings_server_body), style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = hostInput,
                onValueChange = {
                    hostInput = it
                    statusMessage = null
                },
                label = { Text(stringResource(R.string.settings_server_label)) },
                placeholder = { Text(BackendEndpoint.DEFAULT_HOST) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        endpoint.host = hostInput
                        hostInput = endpoint.host
                        statusMessage = if (endpoint.host == BackendEndpoint.DEFAULT_HOST) null else "Server updated"
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.settings_server_save))
                }

                FilledTonalButton(
                    onClick = {
                        isScanning = true
                        statusMessage = context.getString(R.string.settings_server_scanning)
                        scope.launch {
                            val found = ServerScanner.findOrResolveBackend(endpoint.host, 8000)
                            if (found != null) {
                                endpoint.host = if (found.contains(":")) found else "$found:8000"
                                hostInput = endpoint.host
                                statusMessage = context.getString(R.string.settings_server_found, endpoint.host)
                            } else {
                                statusMessage = context.getString(R.string.settings_server_not_found)
                            }
                            isScanning = false
                        }
                    },
                    enabled = !isScanning,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (isScanning) "Searching…" else stringResource(R.string.settings_server_autodetect))
                }
            }

            if (!isDefault || hostInput != BackendEndpoint.DEFAULT_HOST) {
                OutlinedButton(
                    onClick = {
                        endpoint.resetToDefault()
                        hostInput = BackendEndpoint.DEFAULT_HOST
                        statusMessage = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.settings_server_reset))
                }
            }

            val status = statusMessage ?: if (isDefault) {
                stringResource(R.string.settings_server_status_default)
            } else {
                stringResource(R.string.settings_server_status_custom, endpoint.host)
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoSection(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
