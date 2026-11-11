package com.visualassistant.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle

internal class MicrophonePermissionState(
    private val currentStatus: State<CameraPermissionStatus>,
    private val requested: State<Boolean>,
    val request: () -> Unit,
    val refresh: () -> Unit,
    val openSettings: () -> Unit,
) {
    val status: CameraPermissionStatus get() = currentStatus.value
    val wasRequested: Boolean get() = requested.value
}

@Composable
internal fun rememberMicrophonePermissionState(activity: ComponentActivity, onGranted: () -> Unit): MicrophonePermissionState {
    val latestOnGranted = rememberUpdatedState(onGranted)
    val preferences = remember(activity) { activity.getSharedPreferences("microphone-permission", Context.MODE_PRIVATE) }
    val readStatus = remember(activity, preferences) {
        {
            cameraPermissionStatus(
                granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                requestedBefore = preferences.getBoolean("requested", false),
                shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
            )
        }
    }
    val status = remember(readStatus) { mutableStateOf(readStatus()) }
    val requested = remember(preferences) { mutableStateOf(preferences.getBoolean("requested", false)) }
    val refresh = remember(readStatus) { { status.value = readStatus() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        if (granted && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) latestOnGranted.value()
    }
    return remember(activity, launcher, refresh) {
        MicrophonePermissionState(
            currentStatus = status,
            requested = requested,
            request = {
                preferences.edit { putBoolean("requested", true) }
                requested.value = true
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            refresh = refresh,
            openSettings = {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + activity.packageName).toUri()))
            },
        )
    }
}
