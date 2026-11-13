# Voice & Speech Subsystem

## Overview
Provides 100% offline on-device speech recognition via Moonshine Small Streaming ONNX models and WebRTC VAD, alongside Android Text-to-Speech (TTS) audio playback with markdown sanitization and audio focus management.

## Interface
- `Speaker` (`Speaker.kt`): Clean interface defining text-to-speech output methods (`speak`, `stop`, `isSpeaking`).
- `AndroidTextToSpeechSpeaker` (`AndroidTextToSpeechSpeaker.kt`): Concrete Android `TextToSpeech` implementation handling engine initialization, utterance IDs, audio ducking, and completion callbacks.
- `ToneEarconPlayer` (`ToneEarconPlayer.kt`): Auditory feedback component implementing `EarconPlayer` via Android `ToneGenerator` (beeping at 45% volume on `STREAM_MUSIC`) to signal ongoing background model inference.
- `TtsTextSanitizer` (`TtsTextSanitizer.kt`): Strips markdown formatting, URLs, code fences, inline citations (`[1]`), and emojis to produce natural speech readouts.
- `MoonshineSpeechRecognizer` (`MoonshineSpeechRecognizer.kt`): On-device streaming speech-to-text runner executing offline ONNX models.
- `SpeechController` (`SpeechController.kt`): Manages the microphone recording, utterance framing, VAD pause detection, and draft transcription flow.
- `MoonshineModelInstaller` (`MoonshineModelInstaller.kt`): Verifies and extracts offline model assets to app storage.
- `PhoneAudioSource` (`PhoneAudioSource.kt`): AudioRecord provider capturing 16 kHz 16-bit mono PCM audio.

## Invariants
- **Offline Guarantee**: Speech-to-text recognition executes entirely on-device; microphone audio is never uploaded to external servers during standard voice transcription.
- **Audio Focus & VAD Signaling**: When TTS playback is active, the speaker requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` and notifies the speech recognizer to pause VAD listening to prevent self-transcription loops.
- **ToneGenerator Earcons**: `ToneEarconPlayer` runs non-blocking audio beeps on `AudioManager.STREAM_MUSIC` and safely cleans up coroutine jobs upon cancellation.
- **Regex Sanitization Precompilation**: All regex patterns in `TtsTextSanitizer` are precompiled constants to ensure zero-allocation per speech turn.

## Side Effects
- Records phone microphone audio via `AudioRecord`.
- Emits audio playback through phone speaker or connected earphones via Android TTS and `ToneGenerator`.
- Reads/unpacks ONNX model files from assets into local app storage.

## Verification
- Run: `.\android\gradlew.bat -p android testDebugUnitTest --tests "com.visualassistant.app.voice.*"`
