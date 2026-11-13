# MOSS Live Realtime Audio Subsystem

## Overview
Manages full-duplex conversational voice streaming over WebSockets via the local Python backend relay to MOSS Live (`api.mosi.cn`), handling microphone uplink (16 kHz mono PCM), low-latency audio playback (48 kHz mono PCM), acoustic echo suppression, and Recent Memory context handoff.

## Interface
- `MossLiveSessionController` (`MossLiveSessionController.kt`): Main session coordinator managing connection, start/stop lifecycle, and transcript updates.
- `MossLiveTransport` (`MossLiveTransport.kt`): OkHttp WebSocket transport connecting to `ws://127.0.0.1:8000/v1/realtime/moss-live`.
- `RealtimeAudioSource` (`RealtimeAudioSource.kt`): Continuous microphone capture streaming 16 kHz S16LE PCM chunks.
- `MossAudioPlayer` (`MossAudioPlayer.kt`): Low-latency Android `AudioTrack` stream player rendering 48 kHz S16LE PCM audio.
- `MossEchoSuppressor` (`MossEchoSuppressor.kt`): Software acoustic attenuation suppressing downlink playback from leaking into microphone input.
- `MossLiveProtocol.kt`: Protocol envelopes, events, and session state representations.

## Invariants
- **No Direct Cloud Credentials**: The Android app never stores or handles `MOSS_API_KEY`; all authentication is managed by the local loopback backend.
- **Explicit User Initiation**: Microphone audio is streamed to MOSS only after the user explicitly taps "Start MOSS Live" and stops immediately when the user taps Stop or exits the screen.
- **Context Bounding**: Reference context sent upon session start is capped at the 8 newest finalized text turns and 3 recent photos (max 512px / 512 KiB each).
- **Audio Sample Rates**: Uplink audio is strictly 16,000 Hz 16-bit mono PCM; downlink audio is strictly 48,000 Hz 16-bit mono PCM.

## Side Effects
- Opens local WebSocket connection to `ws://127.0.0.1:8000/v1/realtime/moss-live`.
- Captures continuous microphone audio.
- Plays realtime PCM audio through phone speaker/earphones.

## Verification
- Run: `.\android\gradlew.bat -p android testDebugUnitTest --tests "com.visualassistant.app.mosslive.*"`
