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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri

/** Lives with the composition, never in the retained camera ViewModel. */
internal class CameraPermissionState(
    private val currentStatus: State<CameraPermissionStatus>,
    val request: () -> Unit,
    val refresh: () -> Unit,
    val openSettings: () -> Unit,
) {
    val status: CameraPermissionStatus get() = currentStatus.value
}

@Composable
internal fun rememberCameraPermissionState(activity: ComponentActivity): CameraPermissionState {
    val preferences = remember(activity) {
        activity.getSharedPreferences("camera-permission", Context.MODE_PRIVATE)
    }
    val readStatus = remember(activity, preferences) {
        {
            cameraPermissionStatus(
                granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
                requestedBefore = preferences.getBoolean("requested", false),
                shouldShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
            )
        }
    }
    val status = remember(readStatus) { mutableStateOf(readStatus()) }
    val refresh = remember(readStatus) { { status.value = readStatus() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    return remember(activity, launcher, refresh) {
        CameraPermissionState(
            currentStatus = status,
            request = {
                preferences.edit { putBoolean("requested", true) }
                launcher.launch(Manifest.permission.CAMERA)
            },
            refresh = refresh,
            openSettings = {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + activity.packageName).toUri()),
                )
            },
        )
    }
}
