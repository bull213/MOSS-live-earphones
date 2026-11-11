# AI Visual Assistant (Airflow)

A phone-first visual and conversational assistant designed to help older adults understand everyday objects, tools, documents, and tasks.

The system combines an **Android application** (providing Camera, Microphone, and AudioTrack speaker playback) with a local **Python FastAPI backend** on the development computer (providing provider gateways, local document library indexing, and a realtime WebSocket gateway).

---

## Key Documentation

* 📖 **[Master Project Handoff](PROJECT_HANDOFF.md)**: Architecture, interaction modes, agent operating guardrails, bug register, verification playbook, and complete codebase symbol index.
* 📱 **[Android Documentation](android/README.md)**: Android Studio setup, Kotlin/Compose architecture, Moonshine offline speech model setup, and Room database persistence.
* 🖥️ **[Backend Documentation](backend/README.md)**: Python/FastAPI endpoints, OpenAI and StepFun configuration, local document index, and knowledge CLI.
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

### The 5 Interaction Modes

1. **Photo + Question:** Android `POST /v1/assistant/turns` &rarr; FastAPI &rarr; OpenAI / StepFun &rarr; Chat reply.
2. **Live AI Chat:** Multi-turn conversation with bounded rolling Recent Memory context (up to 8 turns).
3. **Offline Voice:** On-device English speech recognition via Moonshine Small Streaming + WebRTC VAD. 100% offline; transcribes speech into chat draft.
4. **MOSS Live:** Full-duplex cloud voice streaming (16 kHz mono PCM uplink, 48 kHz mono PCM downlink) via loopback WebSocket relay.
5. **Knowledge Library:** On-device (ML Kit) or local (Tesseract/PaddleOCR) document ingestion, local SQLite FTS5 + MiniLM embedding index, and StepFun tool-use reasoning.

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
│   ├── tests/               # Pytest suite with mocked transports (150 tests)
│   └── README.md            # Detailed backend configuration and CLI instructions
└── evaluation/              # Visual assistant evaluation framework
    ├── case-catalog.csv     # 25 planned test cases
    ├── acceptance-template.csv # Evaluation result recording template
    └── README.md            # Evaluation guidelines and privacy rules
```

---

## Quick Start (Windows)

### 1. Backend Verification

```powershell
# Run offline backend tests from backend directory
cd backend
..\.venv\Scripts\python.exe -m pytest -q
cd ..
```

### 2. Android Setup & Build

```powershell
# Download and verify pinned Moonshine offline speech model assets
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\android\scripts\fetch-moonshine-model.ps1

# Run local JVM unit tests, lint, and assemble debug APK
.\android\gradlew.bat --no-daemon --max-workers=4 --gradle-user-home .\android\.gradle-user-home -p android assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

### 3. Launching MOSS Live on Device

1. Connect target Android phone via USB and enable USB Debugging.
2. Verify API keys are present in ignored `backend/.env`.
3. Run the one-click launcher:
   ```cmd
   .\Start MOSS Live.cmd
   ```
4. Tap **Start MOSS Live** on the phone to initiate a voice session.
