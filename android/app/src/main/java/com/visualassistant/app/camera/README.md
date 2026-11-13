# Camera Subsystem

## Overview
Manages phone camera integration using Android Jetpack CameraX, handling image capture, private file storage, preview lifecycle, and permission readiness checks.

## Interface
- `CameraSource` (`CameraSource.kt`): Abstraction for taking still photos and binding preview surfaces.
- `PhoneCameraSource` (`PhoneCameraSource.kt`): Concrete CameraX provider binding image capture use cases to Android lifecycle owners.
- `CameraViewModel` (`CameraViewModel.kt`): Jetpack ViewModel exposing camera readiness state flow and capture dispatchers.
- `CaptureStore` (`CaptureStore.kt`): Stores captured JPEG images in private app cache directory (`context.cacheDir/captures`) and evicts stale files.
- `CapturedPhoto` / `FilePhotoCapture` (`FilePhotoCapture.kt`): Value class encapsulating the local `File`, content URI, dimensions, and timestamps.
- `CameraAvailability` (`CameraAvailability.kt`): Evaluates hardware availability and `android.permission.CAMERA` status.

## Invariants
- **Private Storage**: Captured photos are saved strictly in application-private cache (`context.cacheDir`), never in public external media stores.
- **Lifecycle Bound**: Camera capture use cases bind to Android `LifecycleOwner` and unbind immediately when the screen is hidden or backgrounded.
- **Resolution Cap**: Photos are constrained to reasonable resolutions prior to network transmission.

## Side Effects
- Accesses phone camera hardware.
- Writes and deletes temporary JPEG files in application cache.

## Verification
- Run: `.\android\gradlew.bat -p android testDebugUnitTest`
