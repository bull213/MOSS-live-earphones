# AI Visual Assistant (Airflow)

A phone-first visual and conversational assistant designed to help older adults understand everyday objects, tools, documents, and tasks.

The system combines an **Android application** (providing Camera, Microphone, and AudioTrack speaker playback) with a local **Python FastAPI backend** on the development computer (providing provider gateways, local document library indexing, and a realtime WebSocket gateway).

---

## Key Documentation & Subsystem Blueprints

* 📖 **[Master Project Handoff](PROJECT_HANDOFF.md)**: Architecture, interaction modes, agent operating guardrails, bug register, verification playbook, and complete codebase symbol index.
* 📱 **[Android Blueprint](android/README.md)**: Android Studio setup, Kotlin/Compose architecture, Moonshine offline speech model setup, and Room database persistence.
  * [Application Package Blueprint](android/app/src/main/java/com/visualassistant/app/README.md): Single-activity Kotlin / Jetpack Compose application architecture.
    * [Camera Subsystem](android/app/src/main/java/com/visualassistant/app/camera/README.md): CameraX photo capture, preview binding, and private cache storage.
    * [Chat & Discussion Subsystem](android/app/src/main/java/com/visualassistant/app/chat/README.md): Room DB (v3), Recent Memory, and LiveAssistant turns.
    * [Voice & Offline Speech Subsystem](android/app/src/main/java/com/visualassistant/app/voice/README.md): Moonshine offline STT, WebRTC VAD, and Android TTS playback.
    * [User Interface Subsystem](android/app/src/main/java/com/visualassistant/app/ui/README.md): High-contrast accessible Compose screens, badges, and readouts.
    * [MOSS Live Subsystem](android/app/src/main/java/com/visualassistant/app/mosslive/README.md): Bidirectional PCM voice streaming over loopback WebSockets.
    * [Knowledge Ingestion Subsystem](android/app/src/main/java/com/visualassistant/app/knowledge/README.md): Document scanning and multi-page upload.
    * [Network & Endpoints Subsystem](android/app/src/main/java/com/visualassistant/app/network/README.md): Base URL configuration and server scanner.
* 🖥️ **[Backend Blueprint](backend/README.md)**: Python/FastAPI endpoints, OpenAI and StepFun configuration, local document index, and knowledge CLI.
  * [Backend Application](backend/app/README.md): Core FastAPI router, vision gateways (OpenAI, StepFun), MOSS Live WebSocket relay, and ADB watcher.
    * [Knowledge Library](backend/app/knowledge/README.md): SQLite FTS5 lexical search, MiniLM vector retrieval, OCR, and StepFun multi-step reasoning.
    * [NotebookLM Coprocessor](backend/app/notebooklm/README.md): Google NotebookLM authentication, notebook catalog discovery, and circuit-broken grounded chat.
* ⚙️ **[Windows Scripts](scripts/README.md)**: Background service installation, firewall configuration, and silent process management.
* 🐛 **[Bugs and Fixes Log](android/BUGS_AND_FIXES.md)**: Incident history, AudioTrack startup stall resolution, evidence labels, and MOSS Live isolation matrix.
* 🧪 **[Evaluation Suite](evaluation/README.md)**: Privacy-preserving acceptance cases, evaluation catalogs, and benchmarks.

---

## System Overview

```text
Android app (Kotlin + Jetpack Compose)
  camera / saved chats / normal assistant / offline speech / MOSS Live
        |
        | HTTP or WebSocket over USB adb reverse (tcp:8000 -> localhost:8000)
        v
Python FastAPI backend (bound strictly to 127.0.0.1:8000)
  assistant routes / MOSS WebSocket relay / local document library
        |                                      |
        | HTTPS provider request               | WebSocket
        v                                      v
OpenAI / StepFun (normal assistant)        MOSS Live service (api.mosi.cn)
```

### The 6 Interaction Modes

1. **Photo + Question:** Android `POST /v1/assistant/turns` &rarr; FastAPI &rarr; OpenAI / StepFun &rarr; Chat reply.
2. **Live AI Chat:** Multi-turn conversation with bounded rolling Recent Memory context (up to 8 turns).
3. **Offline Voice:** On-device English speech recognition via Moonshine Small Streaming + WebRTC VAD. 100% offline; transcribes speech into chat draft.
4. **MOSS Live:** Full-duplex cloud voice streaming (16 kHz mono PCM uplink, 48 kHz mono PCM downlink) via loopback WebSocket relay.
5. **Knowledge Library:** On-device (ML Kit) or local (Tesseract/PaddleOCR) document ingestion, local SQLite FTS5 + MiniLM embedding index, and StepFun tool-use reasoning.
6. **Collections (NotebookLM):** Direct notebook conversation with on-device speech transcription, auditory waiting feedback, server-side memory, and TTS audio readouts.

---

## Repository Structure

```text
├── PROJECT_HANDOFF.md       # Master project handoff and agent operating guide
├── README.md                # Top-level repository overview (this file)
├── Start MOSS Live.cmd      # One-click Windows USB launcher (batch wrapper)
├── Start MOSS Live.ps1      # PowerShell launcher (ADB reverse, health probe, backend auto-start)
├── android/                 # Android application (Kotlin, Compose, Room, CameraX, JNI)
│   ├── app/                 # Application sources, tests, and assets
│   ├── scripts/             # Moonshine model fetch script (fetch-moonshine-model.ps1)
│   ├── third_party/         # JNI patches and vendored Moonshine dependencies
│   ├── BUGS_AND_FIXES.md    # Incident log and troubleshooting evidence
│   └── README.md            # Detailed Android build and setup instructions
├── backend/                 # FastAPI service and local document engine
│   ├── app/                 # Backend routers, gateways, knowledge index, and MOSS relay
│   ├── tests/               # Pytest suite with mocked transports (198 tests)
│   └── README.md            # Detailed backend configuration and CLI instructions
└── evaluation/              # Visual assistant evaluation framework
    ├── case-catalog.csv     # 25 planned test cases
    ├── acceptance-template.csv # Evaluation result recording template
    └── README.md            # Evaluation guidelines and privacy rules
```

---

---

## Global Invariants

- **Host Loopback Isolation**: The Python development backend binds strictly to `127.0.0.1:8000`. Communication with the Android phone is routed exclusively over local USB ADB reverse tunneling (`tcp:8000 -> localhost:8000`) without public exposure.
- **Zero Client Credential Leakage**: Android client code and assets never contain API keys, service tokens, or private secrets. All provider credentials (OpenAI, StepFun, MOSS Live, Google) reside in local backend configuration.
- **Strict Privacy Tiers**: Offline speech recognition (Moonshine) and sensitive personal documents (`TIER_2_SENSITIVE`) are processed locally on device or development machine without external transmission.
- **Audio Conflict Prevention**: TTS audio playback and speech-to-text recognition coordinate through transient audio focus ducking and VAD listening pauses to eliminate self-transcription and echo loops.

## Global Side Effects

- **External Provider RPCs**: Outbound HTTPS requests to OpenAI, StepFun, and Google NotebookLM endpoints, and bidirectional secure WebSocket streaming to `api.mosi.cn`.
- **Local Persistence**: SQLite database transactions on both Android (`conversations.db` via Room) and backend (`library.sqlite3` via FTS5).
- **USB & Process Automation**: Background port monitoring via ADB (`adb reverse`), temporary upload caches, and optional Windows Task Scheduler daemons.

## System Verification

- **Backend Offline Suite**: `.\.venv\Scripts\pytest.exe -q backend`
- **Android Unit Tests**: `.\android\gradlew.bat -p android testDebugUnitTest`
- **Debug APK Assembly**: `.\android\gradlew.bat -p android assembleDebug`
