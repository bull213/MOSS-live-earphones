package com.visualassistant.app.camera

/** A source is usable only while its preview reports Ready. */
internal sealed interface CameraAvailability {
    data object Starting : CameraAvailability
    data class Ready(val source: CameraSource) : CameraAvailability
    data object Unavailable : CameraAvailability
}
