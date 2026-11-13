# Visual Assistant Android Application Package

## Overview
Root Android Kotlin package for the AI Visual Assistant application. Hosts the single-activity entry point `MainActivity` and coordinates the presentation, camera, offline speech, conversation storage, MOSS Live, and networking subsystems.

## Local Interface & Invariants
- `MainActivity` (`MainActivity.kt`): Main single activity entry point. Configures Jetpack Compose content view, edge-to-edge window insets, camera permission handling, and links `ChatViewModel` with `AndroidTextToSpeechSpeaker`.

## Subdirectories & Component Boundaries
- [Camera Subsystem](./camera/README.md): CameraX photo capture, preview binding, and private cache storage.
- [Chat & Discussion Subsystem](./chat/README.md): Multi-turn conversation state, Room database (v3), rolling Recent Memory, and LiveAssistant HTTP client.
- [Voice & Offline Speech Subsystem](./voice/README.md): On-device Moonshine streaming STT, WebRTC VAD, Android Text-to-Speech engine, and markdown text sanitization.
- [User Interface Subsystem](./ui/README.md): Jetpack Compose screens, accessible high-contrast components, `[📚 Manual: <title>]` badges, and speech readout controls.
- [MOSS Live Subsystem](./mosslive/README.md): Bidirectional PCM audio streaming over loopback WebSockets to MOSS Live cloud voice service.
- [Knowledge Ingestion Subsystem](./knowledge/README.md): ML Kit on-device OCR and multi-page manual upload pipeline.
- [Network & Endpoints Subsystem](./network/README.md): Backend base URL resolution (`127.0.0.1:8000`), server scanner, and wake triggers.

## Global Invariants
- **Zero API Key Leakage**: No third-party API credentials (OpenAI, StepFun, MOSS Live, Google) reside in the Android client; all provider authentication is delegated to the local loopback backend.
- **Accessible Design**: High-contrast UI elements, min 48dp touch targets, and large typography designed specifically for older adults.
- **Audio Coordination**: Speech recognition and speech playback actively coordinate to prevent acoustic feedback loops.

## Verification
- Run: `.\android\gradlew.bat -p android testDebugUnitTest`
