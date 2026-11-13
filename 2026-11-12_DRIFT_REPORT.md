# EGM Drift Report (2026-11-12)

This report verifies the consistency between the codebase implementation and the newly generated Elephant-and-Goldfish Model (EGM) architectural contracts.

## backend/app/notebooklm/README.md
No drift detected.
- Verified `NotebookLMService`, `CitationReference`, `GroundedChatResult` in `backend/app/notebooklm/client.py`.
- Verified endpoints `/v1/notebooklm/status` and `/v1/notebooklm/notebooks` in `backend/app/notebooklm/routes.py`.
- Verified CLI actions (`login`, `status`, `list`, `ask`) in `backend/app/notebooklm/cli.py`.
- Invariants (circuit breaker, per-notebook lock, `truststore` SSL injection, git-ignored auth state) match implementation.

## backend/app/knowledge/README.md
No drift detected.
- Verified `KnowledgeAssistant`, `explicit_notebook_target`, `AgentResult` in `backend/app/knowledge/agent.py`.
- Verified `KnowledgeIndex`, `Passage`, SQLite schema in `backend/app/knowledge/index.py`.
- Verified `LocalMiniLM` in `backend/app/knowledge/embeddings.py`.
- Verified `PhoneBookImportService` in `backend/app/knowledge/book_import_service.py`.

## backend/app/README.md
No drift detected.
- Verified `create_app` in `backend/app/main.py`.
- Verified `routes.py`, `moss_live.py`, `OpenAIVisionGateway`, `StepFunGateway`, `normalize_image`, `RequestMiddleware`, and `adb_watcher.py`.
- Verified child contract references to `./knowledge/README.md` and `./notebooklm/README.md`.

## android/app/src/main/java/com/visualassistant/app/camera/README.md
No drift detected.
- Verified `CameraSource`, `PhoneCameraSource`, `CameraViewModel`, `CaptureStore`, `FilePhotoCapture`, and `CameraAvailability`.

## android/app/src/main/java/com/visualassistant/app/chat/README.md
No drift detected.
- Verified `ChatViewModel`, `ConversationDatabase` (Room v3, `MIGRATION_2_3`), `ConversationRepository`, `LiveAssistant`, and `RecentHistoryPolicy`.

## android/app/src/main/java/com/visualassistant/app/voice/README.md
No drift detected.
- Verified `Speaker`, `AndroidTextToSpeechSpeaker`, `TtsTextSanitizer`, `MoonshineSpeechRecognizer`, `SpeechController`, and `MoonshineModelInstaller`.

## android/app/src/main/java/com/visualassistant/app/ui/README.md
No drift detected.
- Verified `ConversationWorkspace`, `ChatScreen`, `AssistantMessageBubble`, `NotebookManualBadge`, `SpeechReadoutButton`, `CameraScreen`, `AssistantSidebar`, and `VisualAssistantTheme`.

## android/app/src/main/java/com/visualassistant/app/mosslive/README.md
No drift detected.
- Verified `MossLiveSessionController`, `MossLiveTransport`, `RealtimeAudioSource`, `MossAudioPlayer`, `MossEchoSuppressor`, and `MossLiveProtocol`.

## android/app/src/main/java/com/visualassistant/app/knowledge/README.md
No drift detected.
- Verified `PhoneBookImporter`, `AndroidPdfOcr`, and `PhoneBookImportApi`.

## android/app/src/main/java/com/visualassistant/app/network/README.md
No drift detected.
- Verified `BackendEndpoint`, `ServerScanner`, and `AppWakeTrigger`.

## scripts/README.md
No drift detected.
- Verified all PowerShell and VBScript scripts in `scripts/`.

## Summary
All 15 EGM architectural contracts are 100% grounded in code symbols, with verified relative links and passing verification commands across both backend and Android test suites.
