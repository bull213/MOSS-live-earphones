# Project handoff: AI Visual Assistant

> [!NOTE]
> **Master Location:** The comprehensive, refactored project handoff and agent operations guide is now maintained at the repository root: **[`../PROJECT_HANDOFF.md`](../PROJECT_HANDOFF.md)**.
> This file is preserved for reference within the `android/` subproject.

**Updated: 2026-11-11.** This guide describes the whole repository and the current implementation. Setup details and longer procedures live in the Android and backend READMEs linked below.

## What this project is

This is a phone-first assistant for helping people understand everyday objects and tasks. The current hardware is an Android phone: it supplies the camera, microphone and speaker. A local Python service on the development computer provides AI and document-library features. The intended E12 earphones are not integrated because their SDK and protocol are not available.

The product has several separate interaction paths. They should not be confused:

| Feature | What it does | Where processing happens |
| --- | --- | --- |
| Photo and typed question | Sends a reviewed photo and question to the configured assistant; shows the answer in chat. | Android app, local backend, then configured provider |
| Live AI chat | Same assistant path, with a bounded amount of recent typed conversation context. | Android app, local backend, then configured provider |
| Voice | Transcribes speech into a saved chat message. It does not ask the assistant a question automatically. | On-device Moonshine model |
| MOSS Live | Streams microphone audio to MOSS in a live voice session and plays returned audio. Final transcripts are saved in the ordinary chat timeline. | Android app, local backend relay, MOSS cloud service |
| Book / knowledge library | Imports documents, indexes passages locally and can provide relevant passages and reviewed methods to supported assistant requests. | Android OCR where applicable; local backend index and embedding model; StepFun for knowledge-assisted answering |

The old simulated demo route and demo UI have been removed. A transcription appearing in chat confirms speech recognition, not that an assistant response was generated.

## Architecture

```text
Android app (Kotlin + Compose)
  camera / saved chats / normal assistant / offline speech / MOSS Live
        |
        | HTTP or WebSocket over USB adb reverse, localhost port 8000
        v
Python FastAPI backend (bound to 127.0.0.1)
  assistant routes / MOSS WebSocket relay / local document library
        |                                      |
        | HTTPS provider request               | WebSocket
        v                                      v
OpenAI or StepFun (normal assistant)        MOSS Live service
```

The phone connects to `127.0.0.1:8000`; ADB reverse forwards that phone loopback port to the computer. The MOSS API key stays in the backend's ignored `backend/.env`. The backend connects upstream to MOSS and relays protocol messages; it does not put the key in the APK.

## Android application

The Android project is in `android/`, with application ID `com.visualassistant.app`, minimum Android API 31 (Android 12), Java/Kotlin target 17, and Jetpack Compose UI.

- **Camera and photos:** CameraX captures a photo for review. Saved chat photos are corrected for orientation, re-encoded without EXIF metadata, and kept in app-private storage rather than the gallery.
- **Chat and persistence:** Room stores conversations and messages. Recent Memory is a rolling timeline; saved conversations can be reopened. Retention and storage behavior are described in [README.md](README.md).
- **Normal assistant:** Text and optional reviewed photos go through `POST /v1/assistant/turns`. Live AI uses bounded Recent Memory context. Voice transcripts are excluded from that context unless the user explicitly submits their text.
- **Offline Voice:** Moonshine Small Streaming transcribes English locally. WebRTC VAD detects pauses; after about two seconds of silence, the current utterance is finalized and saved. Raw audio is not uploaded or stored by this feature. It does not identify speakers.
- **MOSS Live:** A separate, explicit voice-and-vision session. At startup, the app reads bounded rolling Recent Memory, sends up to eight finalized role-labeled text turns (including `[Shared a photo]` events) as reference instructions, selects the latest photo from rolling memory if no staged photo is active, and sends newest-first tagged JPEG camera frames (`0x01` + JPEG bytes). It runs a continuous 1,500ms background heartbeat coroutine (`cameraStreamJob`) to maintain persistent visual awareness without buffer timeouts. Users can take in-call photos ("Take Picture") to update the live stream, or snap photos pre-call ("Add to Discussion") directly into rolling memory. Successful normal HTTP chat turns added while MOSS is active are appended as reference context, with an attached photo sent as a frame. MOSS's own live transcripts are not replayed into its context. The app sends continuous 16 kHz mono PCM audio (including silence) through the backend. Returned 48 kHz mono PCM chunks are played in order and acknowledged after playback. Only finalized MOSS transcripts are persisted to Room; partial event text may appear temporarily in session state. Complete response audio can be transcribed locally as a fallback when provider transcript text is missing. Incomplete audio is discarded. TTS outside MOSS, wake words and speaker identification are not implemented.
- **PDF import:** The user can choose a PDF. The app stages a private temporary copy, asks the backend which pages need OCR, uses bundled ML Kit Latin OCR on those pages, and sends the PDF plus OCR results to the local backend for indexing. The temporary phone copy is removed afterward.
- **Lifecycle and permissions:** Voice and MOSS stop when the user stops them, backgrounds the app, or leaves the active chat route. Camera and microphone permissions are requested only for the relevant feature.

### Android source map

| Path | Responsibility |
| --- | --- |
| `android/app/src/main/java/.../camera/` | CameraX source, capture files and camera state |
| `.../chat/` | Chat models, Room repository/database, normal assistant HTTP client, Recent Memory policy |
| `.../mosslive/` | Realtime protocol, WebSocket client, session controller, audio capture/playback, transcript/audio helpers |
| `.../voice/` | On-device microphone capture, VAD, utterance buffering and Moonshine transcription |
| `.../knowledge/` | Android PDF import, OCR and backend import API |
| `.../ui/` | Compose screens, chat timeline, navigation and visual components |
| `android/app/src/test/` | Local JVM tests |
| `android/app/src/androidTest/` | Tests that require an emulator or phone |
| `android/scripts/` | Pinned Moonshine model and JNI helper scripts |
| `android/third_party/moonshine-jni/` | JNI compatibility patch and rebuild notes |

## Backend and AI services

The backend is Python/FastAPI in `backend/app/`. It is a development service intended to listen on loopback, not a production multi-user server.

- `GET /health` checks that the process is healthy. It does not prove provider credentials or service access.
- `POST /v1/turns` is the photo-required assistant route.
- `POST /v1/assistant/turns` is the text/photo assistant route used by the app. It can accept bounded conversation context and library mode.
- `/v1/knowledge/*` handles document inspection and Android book import.
- `/v1/realtime/moss-live` is the separate WebSocket gateway. It connects Android to the official MOSS realtime endpoint, attaches/configures a session, relays text and binary events, and closes both sides when the session ends.

OpenAI is the default provider for normal assistant calls. StepFun can be selected for text/context and knowledge-assisted workflows; current provider support and configuration are in [backend README](../backend/README.md). Knowledge-assisted requests use StepFun and require regional price settings so the backend can enforce an estimated per-request budget. Cost estimates are not guarantees of provider billing.

The MOSS model default is `moss-live-1.0`. A successful handshake/session configuration only proves the backend can connect and configure; it does not prove that audio was relayed, an answer was generated, or playback completed. MOSS sessions are real and may incur charges.

### Backend source map

| Path | Responsibility |
| --- | --- |
| `backend/app/main.py` | FastAPI app creation, shared resources and lifespan |
| `backend/app/routes.py`, `schemas.py` | Assistant HTTP endpoints and request/response contracts |
| `backend/app/openai_gateway.py`, `stepfun_gateway.py` | Provider adapters |
| `backend/app/assistant.py`, `prompts.py` | Assistant orchestration and prompts |
| `backend/app/moss_live.py` | MOSS realtime WebSocket gateway |
| `backend/app/images.py` | Image validation, normalization and limits |
| `backend/app/middleware.py`, `errors.py`, `cancellation.py` | Request tracking, safe errors and disconnect handling |
| `backend/app/knowledge/` | Local index, OCR, embeddings, retrieval, methods, import and evaluation |
| `backend/tests/` | Offline tests; provider calls are mocked and no real MOSS session is required |

## Local document library

The library can index PDF, EPUB, UTF-8 text and supported page images. It stores source metadata and passages in a local SQLite database, uses SQLite FTS5 plus a locally downloaded MiniLM embedding model, and can use OCR for pages with poor or absent embedded text. The Android PDF path performs OCR on the phone with ML Kit; command-line imports can use configured local OCR providers.

Knowledge routes retrieve bounded passages for the assistant. Method extraction is a separate explicitly invoked operation that sends selected passages to StepFun and may incur charges. Extracted methods are candidates until a person reviews and approves them. Only approved, non-stale versions are eligible for ordinary use. Library content and derived index data are private local data under ignored `backend/.data/knowledge/`.

See [backend README](../backend/README.md) for model download, indexing, OCR, method review and retrieval commands.

## Data, privacy and safety boundaries

- Keep provider credentials in ignored local `backend/.env`. Never put them in Android, source control, logs, screenshots or chat.
- MOSS audio goes to the cloud only while MOSS Live is explicitly active. Regular Voice stays on device. MOSS raw audio is not saved by the app.
- Photos and questions for normal assistant requests go through the local backend to the configured provider. Review before sending.
- Routine logs are designed to omit prompts, transcripts, images, audio bytes and credentials. Preserve that rule when debugging; prefer event names, IDs and counts.
- The backend has no production authentication, TLS termination, rate limiting or user isolation. Keep it bound to loopback; do not expose it publicly.
- Do not uninstall the app, clear its data, reset the phone, alter unrelated phone settings, or delete private source/index data as a troubleshooting shortcut. Use in-place `adb install -r` only when an install is actually needed.
- Do not initiate a MOSS/provider session merely to build or run offline tests. Real sessions and paid assistant calls need a useful purpose and user authorization.

## Starting the development workflow

### MOSS Live using the USB launcher

1. Connect and unlock the target phone with USB debugging enabled.
2. Configure `MOSS_API_KEY` in the ignored `backend/.env`; never copy the key into the app.
3. Double-click [Start MOSS Live.cmd](../Start%20MOSS%20Live.cmd). Its PowerShell script verifies exactly one authorized ADB device, restores `adb reverse tcp:8000 tcp:8000`, starts the backend if health is unavailable, then opens the app.
4. On the phone, press **Start MOSS Live** only when ready to transmit microphone audio to MOSS. Press Stop to end the session.

The launcher does not start a MOSS session. USB reconnection may remove ADB reverse, so rerun the launcher after reconnecting. It intentionally refuses to choose a device if multiple ADB devices are connected.

For manual backend setup and all environment variables, follow [backend README](../backend/README.md). For Android SDK, Moonshine asset setup, build and installation instructions, follow [android/README.md](README.md). That README is the source of truth for long commands and machine-specific setup.

### Build and tests

Builds and offline tests do not need to start MOSS. The Moonshine model must be fetched and checksum-verified on a fresh checkout before Android packaging. Android instrumentation tests require a device/emulator; compiling their APK does not execute them. Backend tests use mocked provider transports.

The current Android source has been build-verified, its 83 local JVM tests pass, lint passes, and the instrumentation APK compiles. The backend suite passes 150 tests with 1 skipped. Instrumentation tests were not executed on a device. The app was updated in place and live MOSS sessions were run with the user's authorization; no phone data/settings were cleared.

## Current MOSS Live troubleshooting state

See [BUGS_AND_FIXES.md](BUGS_AND_FIXES.md) for the incident history, fixes attempted, evidence labels, and a symptom-to-diagnostic guide.

The observed user-facing issue was that the phone posted accurate user transcription, then showed two separate assistant messages with red **“Partial reply, connection ended early”** markers and truncated text: “I heard you clearly” and “The capital of France is”. This demonstrates that input transcription worked, but it does not establish that MOSS completed a valid answer. The incomplete fragments are not a reliable answer.

Live diagnosis reproduced an AudioTrack startup stall: 192,000 bytes of provider audio arrived, AudioTrack accepted 14,400 bytes, but its playback head stayed at zero and no chunk acknowledgements were sent. AudioFlinger logged an underrun. Android documents that a streaming AudioTrack starts only after its configured threshold is buffered; default threshold is buffer capacity. On this phone capacity was 9,600 frames, while the player waits for each chunk before writing the next. The player now sets a 1-frame start threshold and logs the actual threshold/capacity. On the updated APK, two live sessions completed playback for 166 and 101 chunks and the backend forwarded matching chunk acknowledgements plus 2 and 3 response-completed acknowledgements, respectively.

The exact historical partial-reply session was not retained, so it cannot be tied conclusively to this reproduced stall. The user could not confirm audibility because the phone's sound setting was low; the logged route was the built-in speaker. The captured screen did not show newly generated assistant bubbles in the visible area, so confirm the saved chat row if needed. The separate acknowledgement-order race remains fixed and covered by a deterministic regression. Bounded diagnostics omit transcript/audio content. See [BUGS_AND_FIXES.md](BUGS_AND_FIXES.md) for detailed evidence and commands.

If continuing this issue, use the bounded diagnostics from one explicitly authorized live session to establish whether response-start, transcript, audio, playback-head completion, and acknowledgements reach each boundary. Do not log transcript text or raw audio. Avoid repeated sessions that produce no new diagnostic information. A transcription alone is not proof the assistant answered. No live test is authorized by the offline implementation work documented here.

## Evaluations and plans

- [android/UI_AND_APP_PLAN.md](UI_AND_APP_PLAN.md) describes the intended phone experience and UI development principles.
- [android/EARPHONES_UI_PLAN.md](EARPHONES_UI_PLAN.md) records the future earphones direction; actual E12 integration depends on vendor documentation/SDK.
- [evaluation/README.md](../evaluation/README.md) describes privacy-preserving assistant acceptance cases. The case catalog is a plan, not a set of completed results.
- [evaluation/knowledge/README.md](../evaluation/knowledge/README.md) covers retrieval and method evaluation.
- [evaluation/speech/README.md](../evaluation/speech/README.md) covers on-phone transcription accuracy, latency and resource measurements.

## Repository map

```text
PROJECT_HANDOFF.md           Master repository handoff and agent operations guide
Start MOSS Live.cmd / .ps1   Windows USB/backend setup launcher
android/                     Android Studio app and project docs
backend/                     FastAPI service, tests, local knowledge tools
evaluation/                  Planned assistant, knowledge and speech evaluation
```

Ignored/private local material includes `backend/.env`, `backend/.data/`, `.venv/`, downloaded Moonshine assets, local SDK/Gradle state, and evaluation fixtures/results. Do not assume these private files exist on a new machine; follow the READMEs to restore required tooling and model assets.

## Working principles for the next developer

1. Find the first failing boundary before changing downstream UI or persistence.
2. Keep the three voice/chat paths distinct: offline transcription, normal HTTP Live AI, and realtime MOSS Live.
3. Preserve Room data and phone state. Do not reset or clear the user's phone.
4. Make the smallest targeted change, keep logs content-free, and avoid unnecessary paid/provider sessions.
5. Record what was actually verified, where it was verified, and what remains unknown.
