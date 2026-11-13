# Diagnostic Architecture & Feature Specification: MOSS Live Duplex Voice & NotebookLM Grounded Retrieval Synchronization

| Parameter | Value |
| :--- | :--- |
| **Author** | bull213 |
| **Status** | Proposed |
| **Date** | 2026-11-12 |
| **Target Feature** | Synchronized MOSS Live Voice Duplex + Google NotebookLM Cloud Retrieval |
| **Primary Scope** | `backend/app/moss_live.py`, `backend/app/notebooklm/client.py`, and Android audio/session boundary |

---

## Overview

This specification establishes an operational synchronization layer connecting MOSS Live full-duplex voice interactions with Google NotebookLM cloud retrieval-augmented generation (RAG). The system spans the FastAPI backend gateway and the Android mobile client to execute dependable, conversational document retrieval. It bridges the timing gap between real-time voice expectations (<1s) and cloud retrieval latencies (15–22s), eliminates multimodal camera frame interference during document lookups, triggers speech synthesis upon answer arrival, and protects voice delivery from acoustic echo and inadvertent barge-in interruptions.

---

## The Problem

Users querying personal knowledge notebooks through real-time voice duplex experience four failure modes:

1.  **Silent Authentication Invalidation**: When Google session cookies expire in `backend/.data/notebooklm/auth_state.json`, `NotebookLMService.list_notebooks()` catches the error generically and returns an empty list rather than exposing an authentication failure. The voice session initializes without notebook titles, causing notebook queries to fail silently without user guidance.
2.  **Voice Turn Latency Mismatch**: Voice activity detection (VAD) triggers a response turn within 500–1000ms of user silence, whereas Google NotebookLM RPC queries require 15–22 seconds. Without immediate conversational pacing, users assume the system has crashed, or MOSS fills the silence with unrelated commentary.
3.  **Dormant Asynchronous Context Injection**: Incoming NotebookLM answers are appended to session instructions using `session.instructions.append`. In the MOSS Realtime protocol, this command updates context memory but does not trigger speech output (`response.create`). As a result, the answer remains silent until the user speaks again.
4.  **Multimodal Visual Contamination**: The Android client streams camera frames (`0x01` binary tag) every 1,500ms. During the 15–22 second retrieval window, MOSS processes ambient camera frames (such as desktop monitors or surrounding objects) and generates responses based on visual imagery rather than the pending notebook query.

---

## Goals

*   **Conversational Holding Pacing**: Acknowledge notebook queries within 200ms of transcript completion using an explicit verbal holding phrase to provide conversational continuity during cloud retrieval.
*   **Hybrid Visual Frame Gating**: Intercept and drop binary camera frames (`0x01`) at the gateway while signaling a client hint (`video.stream_pause: true`) to pause mobile camera transmission, resetting on a 25-second fail-open timeout.
*   **Native Grounded Speech Synthesis**: Dispatch an explicit speech synthesis trigger (`response.create`) to MOSS Realtime immediately upon receiving the cloud retrieval response.
*   **Dynamic Self-Interruption Suppression**: Protect grounded voice answers by suppressing microphone uplink transmission during speaker playback using the existing `MossEchoSuppressor`, while maintaining full-duplex operation for earphones and enabling a manual touch cancellation override.
*   **Proactive Credential Status Transparency**: Differentiate between empty notebook libraries and expired credentials, surfacing auth status via the existing `GET /v1/notebooklm/status` endpoint, MOSS session instructions, and the mobile settings UI.

---

## Non-Goals

*   Building a secondary client-side text-to-speech fallback engine on Android, which introduces audio collision risks under cloud network variance.
*   Spawning periodic WebSocket micro-earcon tickers or managing sound pool chime states during retrieval.
*   Separating notebook interactions into a disconnected, modal voice session or tearing down MOSS Live connections during document queries.
*   Modifying Google NotebookLM internal RPC endpoints or altering Google account authentication protocols.
*   Building a local vector search engine or hosting offline document embeddings on mobile hardware.
*   Altering proprietary upstream MOSS Realtime speech models or server infrastructure.

---

## Critical User Journeys

### CUJ 1: Grounded Notebook Retrieval with Progressive Pacing

*   **Given**: The user is in an active MOSS Live duplex voice session with valid Google NotebookLM credentials.
*   **When**: The user speaks: *"Check Reason is fun. What is the fun criterion?"*
*   **Then**:
    1. Within 200ms of transcript finalization, the gateway aborts MOSS's default turn via `response.cancel` and injects a verbal holding acknowledgment: *"Checking your 'Reason is fun' notebook now. One moment..."*
    2. The gateway engages frame gating, dropping outgoing camera frames (`0x01`) and dispatching `video.stream_pause: true` to Android, while PCM audio uplink (`0x00`) remains active.
    3. Upon answer arrival (~18s), the gateway dispatches `video.stream_resume: true`, releases the frame gate, and triggers MOSS speech output re-anchored to the topic: *"According to Reason is fun..."*
    4. Android's `MossEchoSuppressor` dynamically mutes microphone uplink audio during speaker playback to prevent self-interruption, while earphones retain full duplex.

### CUJ 2: In-Flight Query Preemption and Cancellation

*   **Given**: A NotebookLM cloud query is in flight.
*   **When**: The user says *"Never mind, cancel that"* or asks a question about a different notebook.
*   **Then**:
    1. The gateway cancels the active retrieval task; the task's `finally` block verifies task identity before modifying gate state to avoid race conditions.
    2. If the user requested cancellation, the gateway releases the frame gate and acknowledges the cancellation.
    3. If the user referenced a new notebook, the gateway starts the new retrieval task while maintaining the active frame gate.
    4. Any late-arriving response from the cancelled task is discarded without modifying session instructions.

### CUJ 3: Expired Credentials Detection and Reporting

*   **Given**: Google session cookies in `auth_state.json` have expired.
*   **When**: The user connects to MOSS Live or navigates to settings.
*   **Then**:
    1. `NotebookLMService.list_notebooks()` raises `NotebookLMAuthExpiredError`.
    2. `GET /v1/notebooklm/status` reports `{"configured": true, "authenticated": false, "reason": "auth_expired"}`.
    3. The MOSS system prompt informs the user that notebook access is paused due to expired credentials.
    4. The Android settings screen presents an actionable card prompting the user to refresh session credentials.

### CUJ 4: Retrieval Timeout and Error Recovery

*   **Given**: A NotebookLM cloud query is in flight, but the upstream Google service does not respond within 25.0 seconds.
*   **When**: The 25-second timeout boundary is reached.
*   **Then**:
    1. The gateway task raises `asyncio.TimeoutError` and safely resets `gate_camera_frames = False`.
    2. The gateway dispatches `video.stream_resume: true` to the Android client.
    3. The gateway prompts MOSS to speak a clear failure recovery turn: *"I couldn't retrieve information from that notebook in time. What else can I help you with?"*
    4. The session returns to normal conversational duplex without dead air.

---

## Technical Plan

### Architectural Overview

The operational architecture coordinates four interacting entities:
1. **Android Client (`com.visualassistant.app`)**: Captures 16kHz PCM audio (`0x00`) and JPEG camera frames (`0x01`), manages dynamic echo suppression via [MossEchoSuppressor.kt](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossEchoSuppressor.kt), handles session control in [MossLiveSessionController.kt](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveSessionController.kt), and displays credential status in Compose UI ([SettingsInfo.kt](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/ui/SettingsInfo.kt)).
2. **FastAPI Gateway ([backend/app/moss_live.py](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/app/moss_live.py))**: Manages the bidirectional WebSocket connection, hosts the visual frame gating state machine, dispatches holding acknowledgments, and orchestrates retrieval concurrency.
3. **MOSS Realtime Upstream (`mosi.cn`)**: Provides conversational voice activity detection, streaming audio synthesis, and multimodal vision processing.
4. **Google NotebookLM Service ([backend/app/notebooklm/client.py](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/app/notebooklm/client.py))**: Executes authenticated cloud document retrieval (`ask_chat`) and session health checks.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Android as Android Client (com.visualassistant.app)
    participant Backend as FastAPI Gateway (moss_live.py)
    participant MOSS as MOSS Realtime Upstream (mosi.cn)
    participant NLM as Google NotebookLM Service

    User->>Android: Voice Query: 'Check Reason is fun. What is the fun criterion?'
    Android->>Backend: 16kHz PCM Audio Uplink (0x00)
    Backend->>MOSS: Relays 16kHz PCM Uplink
    MOSS-->>Backend: Event: input.transcript.done ('Check Reason is fun...')
    
    rect rgb(230, 245, 255)
        Note over Backend: State: RETRIEVAL_ACTIVE<br/>1. Cancel Default Turn (response.cancel)<br/>2. Gate Camera Frames (0x01)<br/>3. Send Stream Pause Hint
        Backend->>MOSS: Command: response.cancel
        Backend->>Android: Event: video.stream_pause (pause camera frames)
        Backend->>MOSS: Command: response.create (speak holding acknowledgment)
        MOSS-->>Android: Audio: 'Checking Reason is fun now, one moment...'
    end

    par Parallel Retrieval & Video Gating
        Backend->>NLM: ask_chat(notebook_id, query, timeout=25.0)
    and Frame Suppression Invariant
        Note over Android,Backend: Android pauses camera capture; gateway drops any residual 0x01 frames
    end

    NLM-->>Backend: GroundedChatResult (answer + citations)
    
    rect rgb(240, 255, 240)
        Note over Backend: State: ANSWER_DELIVERY<br/>1. Release Frame Gate<br/>2. Send Stream Resume Hint<br/>3. Trigger Grounded Speech
        Backend->>Android: Event: video.stream_resume
        Backend->>MOSS: Command: session.instructions.append + response.create
        MOSS-->>Android: 48kHz Grounded Audio Playback
        Note over Android: MossEchoSuppressor active during speaker output<br/>Screen touch provides manual cancel override
        Android->>User: Speaks grounded answer aloud
    end
```

---

### Component Specifications

#### 1. Backend Visual Frame Gating State Machine (`backend/app/moss_live.py`)

During document retrieval, continuous camera streaming transmits ambient visual frames that cause the multimodal model to generate responses based on camera imagery rather than the pending retrieval result. The gateway implements an inline binary frame filter with hybrid client-side pause hints.

*   **State Variables (Session Scope)**:
    *   `gate_camera_frames: bool = False`: Toggled by the retrieval lifecycle.
    *   `active_retrieval_task: asyncio.Task | None = None`: Handle for active in-flight query execution and preemption.
    *   `retrieval_started_at: float = 0.0`: Monotonic timestamp for query telemetry and timeout boundaries.
    *   `android_camera_frames_suppressed: int = 0`: Telemetry counter tracking dropped frames.
*   **Frame Routing Invariant in `android_to_provider`**:
    Binary packet inspection must execute *before* `upstream.send(binary)`:
    ```text
    On incoming binary payload from Android:
    If header byte == 0x01 (Camera Frame):
        If gate_camera_frames is True:
            Increment android_camera_frames_suppressed
            Drop payload (do not forward to upstream WebSocket)
        Else:
            Forward payload to upstream WebSocket
    Else (Audio PCM 0x00 or control bytes):
        Forward payload to upstream WebSocket immediately
    ```
*   **Preemption & Gate Reset Guard**: Retrieval execution is wrapped in a `try ... finally` block. To prevent race conditions where a cancelled task resets the gate during a newly initiated query, the reset is guarded by task identity:
    ```python
    current_task = asyncio.current_task()
    try:
        # execute retrieval...
    finally:
        if self.active_retrieval_task is current_task:
            self.gate_camera_frames = False
            self.active_retrieval_task = None
    ```
*   **Fail-Open Timeout Invariant**: Retrieval is bounded at 25.0 seconds. If `ask_chat` fails or times out, the gate resets to `False` and `video.stream_resume` is dispatched to Android.

#### 2. Native Turn Cancellation and Holding Pacing

To prevent upstream MOSS from speaking an ungrounded response to the user's initial query:
*   **Turn Abort**: Upon receiving `input.transcript.done`, the gateway checks for notebook intent using [`nlp.match_notebook_from_speech`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/app/notebooklm/nlp.py#L115-L184). If matched, the gateway immediately dispatches `{"action": "response.cancel"}` to MOSS upstream to abort native turn inference.
*   **Holding Acknowledgment**: The gateway dispatches a holding instruction within 200ms of transcript completion:
    *   Format: *"Checking your '[Notebook Title]' notebook now. One moment..."*
    *   Synthesized via `response.create` directed to MOSS Realtime.

#### 3. Conversational Re-Anchoring and Native Speech Trigger

*   **Sanitization**: Incoming answers are sanitized via the existing [`nlp.sanitize_grounded_voice_summary`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/app/notebooklm/nlp.py#L186-L217), which removes citation brackets (`[1]`, `[2]`), markdown formatting, and emojis.
*   **Context Append & Speech Trigger**: The gateway appends sanitized context via `session.instructions.append` and issues `response.create` to MOSS Realtime to synthesize and broadcast the grounded voice answer.
*   **Elimination of Secondary TTS Fallback**: To eliminate dual-voice audio collisions, the speculative 1.5s client-side TTS watchdog is eliminated. System resilience relies on standard WebSocket connection error handling.

#### 4. Dynamic Echo Suppression and Playback Cancellation Override

*   **Acoustic Echo Suppression via `MossEchoSuppressor`**:
    *   Android's existing [`MossEchoSuppressor.kt`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossEchoSuppressor.kt) dynamically zeroes microphone uplink PCM frames *only* during assistant speech output when the physical built-in speaker is active (`!isEarphonesOrHeadsetActive()`).
    *   When wired or Bluetooth earphones/headsets are connected, microphone uplink audio remains unmuted, preserving natural full-duplex conversational barge-in.
*   **Manual Cancellation Override**:
    *   During speaker playback, acoustic echo muting prevents spoken keywords from reaching the cloud.
    *   Emergency cancellation is bound strictly to physical UI touch interaction (a single tap on the Compose screen).
    *   Tapping the screen immediately signals `MossLiveSessionController` to halt `AudioTrack` playback and dispatch `response.cancel` upstream.

#### 5. Credential Status Detection and Reporting

*   **API Endpoint**: Client UI queries the existing endpoint:
    `GET /v1/notebooklm/status`
    Returning structured health status:
    ```json
    {
      "configured": true,
      "authenticated": false,
      "reason": "auth_expired"
    }
    ```
*   **Handshake Injection**: During MOSS Live session initialization, `NotebookLMService.check_auth_status()` validates session cookies. If expired, an advisory directive is injected into the MOSS system instructions:
    `"Notebook access is paused because Google session credentials expired. If the user asks for notebooks, instruct them to tap Settings to reconnect."`
*   **Client Status Card**: Jetpack Compose settings UI ([`SettingsInfo.kt`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/ui/SettingsInfo.kt)) renders an actionable card when `authenticated == false`.

---

## Alternatives Considered

### Alternatives Trade-Off Matrix

| Alternative | Latency Overhead | Implementation Complexity | Voice Duplex Ergonomics | Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **1. Modal Session Handoff** | High (>2s cold-start) | High (dual voice stack) | Degraded (half-duplex) | **Rejected** |
| **2. Hybrid Suspended Duplex** | Low | High (dual-sink routing) | Moderate (half-duplex in doc) | **Rejected** |
| **3. Speculative 1.5s TTS Fallback** | Low | High (watchdog + mutex) | Fragile (dual-voice clash risk) | **Rejected** |
| **4. Pure Server-Side Frame Dropping** | Minimal (<0.1ms) | Low | Preserved | **Rejected** (wastes battery) |
| **Selected: Gateway Gating + Hybrid Hint + Native Synthesis** | Minimal (<0.1ms) | Low (reuses existing stack) | Preserved (full duplex) | **Accepted** |

### Alternative 1: Modal Session Handoff (Terminating MOSS Live during Notebook Queries)
*   **Mechanism**: Terminate the MOSS Live duplex session when a notebook query is recognized. Route subsequent turns through a separate ASR $\rightarrow$ NotebookLM $\rightarrow$ TTS pipeline until the user commands *"exit notebook"*.
*   **Reason for Rejection**: Rejected by author: Adds high implementation complexity to build and maintain a secondary voice stack, degrades duplex communication, discards conversational context across session boundaries, and incurs connection re-establishment latency upon exiting notebook scope.

### Alternative 2: Hybrid Suspended Duplex (Pausing Streams Without Disconnecting)
*   **Mechanism**: Keep the MOSS Live WebSocket open in the background, but suspend outgoing audio and camera streaming while routing notebook speech through a local TTS engine.
*   **Reason for Rejection**: Rejected by author: Increases client-side audio routing complexity and risks idle socket termination by upstream MOSS during long retrieval periods.

### Alternative 3: Speculative 1.5s Client-Side TTS Fallback Watchdog
*   **Mechanism**: Dispatch `response.create` to MOSS upstream while arming a 1,500ms watchdog; if MOSS audio does not begin within 1,500ms, dispatch a control event for Android local TTS.
*   **Reason for Rejection**: Rejected by author: Cloud LLM TTFB regularly takes 1.5–2.5s under normal network variance. Without complex upstream cancellation and client-side audio mutexes, both voices speak simultaneously, creating an unintelligible acoustic clash. Relying solely on native MOSS synthesis provides deterministic, unified voice delivery.

### Alternative 4: Pure Server-Side Frame Dropping (No Client Pause Hint)
*   **Mechanism**: Rely strictly on gateway frame dropping while Android continues encoding and streaming JPEG frames across mobile radio for 22 seconds.
*   **Reason for Rejection**: Rejected by author: Continually capturing, compressing, and transmitting 1,500ms JPEG frames across cellular networks into a server discard sink wastes device battery and cellular bandwidth. Combining gateway dropping with a `video.stream_pause` hint eliminates mobile radio waste while guaranteeing zero frame leakage.

---

## Open Questions

*   **Question 1**: Under high mobile packet loss, what is the optimal client retry window for the `GET /v1/notebooklm/status` health check upon returning to the foreground?

---

## Detailed Implementation Plan

### Target Files to Create/Modify

#### 1. [`backend/app/notebooklm/client.py`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/app/notebooklm/client.py) (Modify)
*   **Type / Scope**: Python Service Client
*   **Rationale**: Currently catches generic `Exception` during notebook listing and returns `[]`, masking expired credentials as an empty account.
*   **Proposed Changes**:
    *   Define `NotebookLMAuthExpiredError(Exception)`.
    *   In `NotebookLMService.list_notebooks()`, catch `AuthError` and raise `NotebookLMAuthExpiredError` rather than suppressing the exception and returning an empty list.
    *   Maintain the existing `check_auth_status()` dictionary return shape (`{"authenticated": bool, "email": str, "reason": str}`) for caller compatibility.

#### 2. [`backend/app/moss_live.py`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/app/moss_live.py) (Modify)
*   **Type / Scope**: WebSocket Gateway & Protocol Orchestrator
*   **Rationale**: Core gateway managing duplex connections, camera frame gating, query preemption, and holding phrase dispatch.
*   **Proposed Changes**:
    *   Add session-scoped tracking: `gate_camera_frames: bool = False`, `active_retrieval_task: asyncio.Task | None = None`, `retrieval_started_at: float = 0.0`, and counter `android_camera_frames_suppressed: int = 0`.
    *   In `android_to_provider` binary message loop, inspect byte header *before* `upstream.send(binary)`:
        *   If `binary[0] == 0x01` (camera JPEG) and `gate_camera_frames` is `True`, increment `android_camera_frames_suppressed` and discard the packet.
        *   If `binary[0] == 0x01` and `gate_camera_frames` is `False`, forward packet to upstream.
        *   If `binary[0] == 0x00` (audio PCM), stream packet upstream immediately.
    *   In `provider_to_android` text message loop, upon `input.transcript.done`:
        *   Match query intent via existing `nlp.match_notebook_from_speech`.
        *   If matched, immediately send `{"action": "response.cancel"}` upstream to abort MOSS's default turn.
        *   Send `{"action": "video.stream_pause"}` downlink to Android.
        *   Dispatch verbal holding phrase to MOSS within 200ms via `response.create`.
        *   Spawn `_query_notebook_and_inject` in a managed `asyncio.Task`.
    *   In `_query_notebook_and_inject`:
        *   Wrap retrieval in `try ... finally` block. In `finally`, verify task identity (`if self.active_retrieval_task is current_task: self.gate_camera_frames = False`) before releasing gate, and dispatch `{"action": "video.stream_resume"}` to Android.
        *   Enforce a maximum retrieval timeout of 25.0 seconds.
        *   On success, append sanitized context via `session.instructions.append` and issue `response.create`.
        *   On timeout or `NotebookLMAuthExpiredError`, dispatch a spoken recovery turn to MOSS explaining the failure.

#### 3. [`android/app/src/main/java/com/visualassistant/app/mosslive/MossEchoSuppressor.kt`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossEchoSuppressor.kt) (Verify / Reuse)
*   **Type / Scope**: Android Kotlin Audio Controller
*   **Rationale**: Existing component that suppresses microphone uplink audio dynamically during assistant speech.
*   **Proposed Changes**:
    *   Verify that `suppressUplinkPcm()` zeroes microphone uplink bytes during active playback turns when `!isEarphonesOrHeadsetActive()`.
    *   Confirm that earphones/headsets bypass uplink zeroing to maintain full duplex.

#### 4. [`android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveSessionController.kt`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveSessionController.kt) (Modify)
*   **Type / Scope**: Android Session & WebSocket Controller
*   **Rationale**: Coordinates downlink WebSocket control events with media streaming jobs.
*   **Proposed Changes**:
    *   Handle downlink control events:
        *   On `video.stream_pause`: pause camera frame capture job in `CameraStreamManager`.
        *   On `video.stream_resume`: resume camera frame capture job.
    *   Expose `cancelActiveTurn()` method callable by UI screen tap to interrupt `AudioTrack` playback and dispatch `response.cancel` upstream.

#### 5. [`android/app/src/main/java/com/visualassistant/app/ui/SettingsInfo.kt`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/ui/SettingsInfo.kt) (Modify)
*   **Type / Scope**: Jetpack Compose UI
*   **Rationale**: Users require visual status indication when Google session cookies expire.
*   **Proposed Changes**:
    *   Query existing `GET /v1/notebooklm/status` on screen display.
    *   Display an alert card when `authenticated == false`, linking to session credential refresh.

#### 6. [`backend/tests/test_notebooklm_rag.py`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/tests/test_notebooklm_rag.py) (Modify)
*   **Type / Scope**: Unit Test Suite
*   **Rationale**: Validates credential error handling and status checks offline.
*   **Proposed Changes**:
    *   Add test case verifying `NotebookLMService.list_notebooks()` raises `NotebookLMAuthExpiredError` when `AuthError` occurs.
    *   Verify `check_auth_status()` returns `{"authenticated": false, "reason": "auth_expired"}` under expired cookies.

#### 7. [`backend/tests/test_moss_live.py`](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/tests/test_moss_live.py) (Modify)
*   **Type / Scope**: WebSocket Integration Test Suite
*   **Rationale**: Validates frame filtering, task preemption, and timeout recovery turns.
*   **Proposed Changes**:
    *   Verify binary `0x01` camera frames are dropped while retrieval is active, and `0x00` audio frames stream continuously.
    *   Test task preemption: verify cancelling Task 1 does not reset `gate_camera_frames` for Task 2.
    *   Test timeout recovery turn using monkeypatched virtual time (`PROTOCOL_TIMEOUT = 0.1s`).

---

## Non-Functional Requirements

### Performance & Latency
*   **Holding Acknowledgment**: Dispatched within 200ms of speech transcript finalization.
*   **Frame Filtering Overhead**: Backend binary header inspection (`binary[0] == 0x01`) adds <0.1ms processing latency per packet, well below the 20ms audio frame interval.
*   **Fail-Open Safety**: Gate resets within 25.0 seconds across handled failure conditions (network timeout, upstream error, client cancellation).

### Reliability & Resilience
*   **Unified Voice Delivery**: Sole reliance on native MOSS speech synthesis eliminates split-brain audio clashes.
*   **Self-Interruption Suppression**: Dynamic microphone uplink muting via `MossEchoSuppressor` prevents speaker playback from triggering server-side VAD interruption.

---

## Verification Plan

### Automated Testing
*   **Backend Pytest Suite** (Windows virtual environment):
    ```powershell
    .\.venv\Scripts\python.exe -m pytest backend/tests/test_moss_live.py backend/tests/test_notebooklm_rag.py backend/tests/test_notebooklm_nlp.py -v
    ```
    *   Verifies frame gating invariants, preemption handling, error responses, and text sanitization rules offline using mocked dependencies.
*   **Python Code Quality**:
    ```powershell
    .\.venv\Scripts\python.exe -m ruff check backend/app
    ```
*   **Android Unit Tests**:
    ```powershell
    .\android\gradlew.bat -p android testDebugUnitTest
    ```
*   **Android Compilation**:
    ```powershell
    .\android\gradlew.bat -p android assembleDebug
    ```

### Manual & E2E Verification
1.  **Grounded Voice Query Walkthrough**:
    *   Initiate MOSS Live call on Android device.
    *   Ask: *"Check Reason is fun. What is the fun criterion?"*
    *   Verify holding response audio is dispatched within 200ms of transcript finalization, announcing the notebook title.
    *   Verify camera frames are dropped in backend gateway logs throughout the active retrieval window until response delivery or timeout.
    *   Verify grounded answer speaks re-anchored content without citation brackets.
    *   Verify device speaker output does not trigger a self-interruption cutoff.
2.  **Preemption Verification**:
    *   Trigger query, then say *"Cancel"* or ask a question about a different notebook.
    *   Verify initial retrieval task cancels cleanly, gate state remains consistent, and no late answer is spoken.
3.  **Auth Expiration Verification**:
    *   Simulate expired session in `auth_state.json`.
    *   Verify `GET /v1/notebooklm/status` returns unauthenticated.
    *   Verify Android settings screen shows re-authentication card.
