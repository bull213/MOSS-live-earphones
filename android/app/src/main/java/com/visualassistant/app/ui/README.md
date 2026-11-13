# User Interface & Compose Views

## Overview
Jetpack Compose presentation layer providing accessible, high-contrast, phone-first user interfaces for older adults. Includes the conversation timeline, camera viewfinder, discussion sidebar, and speech readout controls.

## Interface
- `ConversationWorkspace` (`ConversationWorkspace.kt`): Main application container managing navigation between Recent Memory, saved chats, and camera review.
- `ChatScreen` (`ChatScreen.kt`): Timeline composable displaying message bubbles, photo attachments, citations, and input composer.
- `ChatMessageItems.kt`:
  - `AssistantMessageBubble`: Renders assistant replies with badge chips and citation cards.
  - `NotebookManualBadge`: Displays purple badge `[📚 Manual: <title>]` for NotebookLM grounded responses.
  - `SpeechReadoutButton`: Interactive audio icon triggering `onReplaySpeech` or indicating live audio readout.
- `CameraScreen` (`CameraScreen.kt`), `CameraPreview` (`CameraPreview.kt`): CameraX viewfinder surface and photo capture overlay.
- `AssistantSidebar` (`AssistantSidebar.kt`): Modal drawer listing saved discussions, NotebookLM Collections, and application settings.
- `VisualAssistantTheme` (`VisualAssistantTheme.kt`): Material 3 theme optimized with enlarged typography and high-contrast colors.

## Invariants
- **Accessibility & Contrast**: Typography and touch targets must adhere to accessibility standards (minimum 48dp touch targets, bold typography) suitable for older adults.
- **State Hoisting**: All composables hoist event callbacks (`onSendTurn`, `onReplaySpeech`, `onCapturePhoto`) up to top-level routes (`ChatRoute.kt`, `CameraRoute.kt`).
- **No Direct Hardware Access**: Composables interact strictly through ViewModels, never invoking Android Camera, TTS, or SQLite directly.

## Side Effects
- Renders UI elements on Android screen.
- Triggers haptic feedback on key actions.

## Verification
- Run: `.\android\gradlew.bat -p android testDebugUnitTest`
