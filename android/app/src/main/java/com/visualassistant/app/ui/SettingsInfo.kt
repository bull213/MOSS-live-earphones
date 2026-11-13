package com.visualassistant.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.visualassistant.app.network.NotebookAuthApi
import com.visualassistant.app.network.NotebookAuthStatus
import com.visualassistant.app.network.ServerScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SettingsInfo(
    onBack: () -> Unit,
    endpoint: BackendEndpoint? = null,
    onClearRecentMemory: () -> Unit = {},
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
                item { NotebookLMSection(endpoint) }
            }
            item { InfoSection(stringResource(R.string.settings_privacy_title), stringResource(R.string.settings_privacy_body)) }
            item { RecentMemorySection(onClearMemory = onClearRecentMemory) }
            item { InfoSection(stringResource(R.string.settings_about_title), stringResource(R.string.settings_about_body)) }
        }
    }
}

@Composable
private fun ServerConnectionSection(endpoint: BackendEndpoint) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hostInput by remember(endpoint.host) { mutableStateOf(endpoint.host) }
    var tokenInput by remember(endpoint.bearerToken) { mutableStateOf(endpoint.bearerToken ?: "") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    val isDefault = endpoint.host == BackendEndpoint.DEFAULT_HOST && endpoint.bearerToken.isNullOrBlank()

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

            OutlinedTextField(
                value = tokenInput,
                onValueChange = {
                    tokenInput = it
                    statusMessage = null
                },
                label = { Text(stringResource(R.string.settings_server_token_label)) },
                placeholder = { Text(stringResource(R.string.settings_server_token_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        endpoint.host = hostInput
                        endpoint.bearerToken = tokenInput.trim().ifBlank { null }
                        hostInput = endpoint.host
                        tokenInput = endpoint.bearerToken ?: ""
                        statusMessage = if (endpoint.host == BackendEndpoint.DEFAULT_HOST && endpoint.bearerToken.isNullOrBlank()) null else "Settings saved"
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.settings_server_save))
                }

                FilledTonalButton(
                    onClick = {
                        isTesting = true
                        statusMessage = context.getString(R.string.settings_server_testing)
                        scope.launch {
                            val targetHost = hostInput.trim().ifBlank { BackendEndpoint.DEFAULT_HOST }
                            val isAlive = ServerScanner.verifyHost(targetHost)
                            if (!isAlive) {
                                statusMessage = context.getString(R.string.settings_server_test_failed)
                            } else {
                                val tempEndpoint = BackendEndpoint(
                                    getPreference = { targetHost },
                                    setPreference = {},
                                    getTokenPreference = { tokenInput.trim().ifBlank { null } },
                                    setTokenPreference = {},
                                )
                                val authApi = NotebookAuthApi(tempEndpoint)
                                val authStatus = authApi.getStatus()
                                if (authStatus.reason == "http_401") {
                                    statusMessage = context.getString(R.string.settings_server_test_unauthorized)
                                } else {
                                    statusMessage = context.getString(R.string.settings_server_test_success, targetHost)
                                }
                            }
                            isTesting = false
                        }
                    },
                    enabled = !isTesting && !isScanning,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (isTesting) "…" else stringResource(R.string.settings_server_test))
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
                    enabled = !isScanning && !isTesting,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (isScanning) "Searching…" else stringResource(R.string.settings_server_autodetect))
                }
            }

            if (!isDefault || hostInput != BackendEndpoint.DEFAULT_HOST || tokenInput.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        endpoint.resetToDefault()
                        hostInput = BackendEndpoint.DEFAULT_HOST
                        tokenInput = ""
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

@Composable
private fun RecentMemorySection(
    onClearMemory: () -> Unit,
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showClearedMessage by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.settings_memory_clear_dialog_title)) },
            text = { Text(stringResource(R.string.settings_memory_clear_dialog_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        onClearMemory()
                        showClearedMessage = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.settings_memory_clear_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.settings_memory_clear_dialog_cancel))
                }
            },
        )
    }

    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_memory_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.settings_memory_body), style = MaterialTheme.typography.bodyLarge)

            OutlinedButton(
                onClick = {
                    showClearedMessage = false
                    showConfirmDialog = true
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.settings_memory_clear_button))
            }

            Text(
                stringResource(R.string.settings_memory_safe_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (showClearedMessage) {
                Text(
                    stringResource(R.string.settings_memory_cleared_notification),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun NotebookLMSection(endpoint: BackendEndpoint) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(endpoint) { NotebookAuthApi(endpoint) }
    var authStatus by remember { mutableStateOf<NotebookAuthStatus?>(null) }
    var isSwitching by remember { mutableStateOf(false) }
    var showWaitingDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        authStatus = api.getStatus()
    }

    LaunchedEffect(isSwitching) {
        if (!isSwitching) return@LaunchedEffect
        var attempts = 0
        while (attempts < 60 && isSwitching) {
            delay(2000L)
            val current = api.getStatus()
            authStatus = current
            if (current.authenticated) {
                isSwitching = false
                showWaitingDialog = false
                break
            }
            attempts++
        }
        isSwitching = false
        showWaitingDialog = false
    }

    if (showWaitingDialog) {
        AlertDialog(
            onDismissRequest = {
                isSwitching = false
                showWaitingDialog = false
            },
            title = { Text(stringResource(R.string.settings_notebooklm_waiting_title)) },
            text = { Text(stringResource(R.string.settings_notebooklm_waiting_browser)) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    isSwitching = false
                    showWaitingDialog = false
                }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            },
        )
    }

    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_notebooklm_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.settings_notebooklm_body), style = MaterialTheme.typography.bodyMedium)

            val isExpired = authStatus?.authenticated == false && (authStatus?.reason == "AuthError" || authStatus?.reason?.contains("expired", ignoreCase = true) == true)
            val isConnected = authStatus?.authenticated == true
            val needsRebootstrap = authStatus?.requiresRebootstrap == true
            val statusText = if (isConnected) {
                stringResource(R.string.settings_notebooklm_connected, authStatus?.email ?: "Google Account")
            } else if (needsRebootstrap) {
                stringResource(R.string.settings_notebooklm_rebootstrap_needed)
            } else if (isExpired) {
                stringResource(R.string.settings_notebooklm_expired)
            } else {
                stringResource(R.string.settings_notebooklm_not_connected)
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isExpired || needsRebootstrap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val buttonLabel = when {
                isExpired -> stringResource(R.string.settings_notebooklm_reconnect)
                isConnected -> stringResource(R.string.settings_notebooklm_switch)
                else -> stringResource(R.string.settings_notebooklm_connect)
            }

            Button(
                onClick = {
                    errorMessage = null
                    scope.launch {
                        isSwitching = true
                        showWaitingDialog = true
                        api.resetAuth()
                        val launched = api.triggerLogin()
                        if (!launched) {
                            isSwitching = false
                            showWaitingDialog = false
                            errorMessage = context.getString(R.string.settings_notebooklm_launch_failed)
                        }
                    }
                },
                enabled = !isSwitching,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(buttonLabel)
            }
        }
    }
}
