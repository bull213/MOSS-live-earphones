# Chat & Conversation Subsystem

## Overview
Coordinates multi-turn conversational state, Room database persistence, rolling Recent Memory bounding, offline voice transcription drafting, and assistant speech playback management.

## Interface
- `ChatViewModel` (`ChatViewModel.kt`): Main presentation controller managing UI state flow, speech synthesis playback state (`speakingMessageId`), user turn dispatching, discussion switching, and NotebookLM Collections turn coordination.
- `ConversationDatabase` (`ConversationDatabase.kt`): Room database (schema v3) managing `MessageEntity` and `DiscussionEntity` tables.
  - `MIGRATION_2_3`: Adds `notebookTitle TEXT DEFAULT NULL` for NotebookLM manual badges.
- `ConversationRepository` (`ConversationRepository.kt`): Data access repository exposing Kotlin Flows for recent timeline messages, saved discussions, and message deletion/mutation.
- `LiveAssistant` (`LiveAssistant.kt`): Interface defining `answer(question, photo, history, notebookId) -> AssistantTurnResult`.
- `LiveHistoryItem` (`LiveAssistant.kt`): Data model (`role`, `text`, `photoId`) capturing rolling conversational context with correlated photo references.
- `LiveProtocol` (`LiveProtocol.kt`): Multipart request packaging including `question`, `photo_id`, `history_json`, optional raw JPEG image bytes, and optional `notebook_id`.
- `RecentHistoryPolicy` (`RecentHistoryPolicy.kt`): Enforces limits on conversation context sent to backend (max 8 turns, max 4,000 characters).
- `ChatModels.kt`: UI domain models (`ChatMessage`, `Discussion`, `SourceReference`, `RetrievalStatus`).

## Invariants
- **Database Migrations**: Schema alterations MUST provide explicit Room `Migration` instances tested via unit tests (`ConversationMigrationTest.kt`); destructive fallback is forbidden.
- **Collections Ephemeral Decoupling**: When an active NotebookLM collection is selected (`activeNotebook != null`), conversational memory is managed upstream by NotebookLM; turns are not persisted to the local Room database, and conversation history is fetched via `NotebookAuthApi.getHistory`.
- **Audio & Earcon Coordination**: In Collections voice turns, `EarconPlayer` generates waiting tones during network latency; upon response arrival, tones cease and `Speaker` articulates the answer via TTS. Incoming speech or navigation stops audio immediately.
- **Context Bounding**: Only finalized turns are included in recent history; streaming drafts and in-progress turn states are excluded.
- **One-Shot Photo Lifecycle**: A captured or selected photo is submitted exactly once for the immediate turn; subsequent turns clear `sessionPhotoId` and default to pure text turns without re-uploading bytes, while retaining historical correlation via `photo_id` in `history_json` so the assistant can selectively inspect photos on demand.

## Side Effects
- Reads and writes SQLite database `conversations.db` via Room.
- Persists and evicts discussion thumbnail images in application cache.
- Triggers text-to-speech audio playback via the `Speaker` interface.

## Verification
- Run: `.\android\gradlew.bat -p android testDebugUnitTest --tests "com.visualassistant.app.chat.*"`
