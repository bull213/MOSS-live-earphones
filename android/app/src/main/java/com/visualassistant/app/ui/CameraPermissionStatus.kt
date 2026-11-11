package com.visualassistant.app.ui

internal enum class CameraPermissionStatus { Granted, Requestable, SettingsRequired }

internal fun cameraPermissionStatus(
    granted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionStatus = when {
    granted -> CameraPermissionStatus.Granted
    !requestedBefore || shouldShowRationale -> CameraPermissionStatus.Requestable
    else -> CameraPermissionStatus.SettingsRequired
}
