package com.visualassistant.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionStatusTest {
    @Test fun firstRequestDoesNotSendUserToSettings() {
        assertEquals(
            CameraPermissionStatus.Requestable,
            cameraPermissionStatus(granted = false, requestedBefore = false, shouldShowRationale = false),
        )
    }

    @Test fun denialWithRationaleAllowsAnotherRequest() {
        assertEquals(
            CameraPermissionStatus.Requestable,
            cameraPermissionStatus(granted = false, requestedBefore = true, shouldShowRationale = true),
        )
    }

    @Test fun denialWithoutRationaleAfterRequestOffersSettings() {
        assertEquals(
            CameraPermissionStatus.SettingsRequired,
            cameraPermissionStatus(granted = false, requestedBefore = true, shouldShowRationale = false),
        )
    }

    @Test fun subsequentGrantOverridesRequestHistoryAndRationale() {
        for (requestedBefore in listOf(false, true)) {
            for (shouldShowRationale in listOf(false, true)) {
                assertEquals(
                    CameraPermissionStatus.Granted,
                    cameraPermissionStatus(true, requestedBefore, shouldShowRationale),
                )
            }
        }
    }
}
