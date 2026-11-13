# Goldfish Annotated Review Report

**Target:** `docs/designs/backend_migration_to_android.md` (Lines 1–490)  
**Status:** In Review  
**Date:** 2026-11-13  
**Author:** bull213  

---

## Escalated Decisions Requiring User Direction

### [ACT-B1] [ESCALATE] NotebookLM Mobile Client Strategy: Workstation Bridge vs. Official Cloud API
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L17-L18](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L17-L18), [L40-L42](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L40-L42), [L256-L268](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L256-L268)
* **Discrepancy Description:** The design relies on in-app WebView Google Sign-In and calling internal Google web RPCs (`batchexecute`) in pure Kotlin. Google strictly blocks WebView sign-in (`disallowed_useragent`), and `batchexecute` requires dynamic anti-CSRF tokens (`SNlM0e`) embedded in page HTML that cannot be extracted from `CookieManager`. Reverse-engineering undocumented Google web RPCs on mobile without Playwright headless browser recovery is an architectural dead-end.
* **Reviewer Lens:** `egm-zoom-out`, `egm-comprehension`, `egm-design`, `egm-implementation-readiness` (Severity: Blocker)
* ***AI Suggestion:*** **ESCALATE**
* ***Justification:*** Reverse-engineering private Google web endpoints in Kotlin will break upon any Google UI update or cellular IP change. The author must decide between two viable paths:
  1. **Option 1 (Workstation-Assisted NotebookLM)**: Acknowledge that unofficial Google NotebookLM integration requires the PC backend (with its Playwright browser recovery). In Workstation Mode, the app queries NotebookLM. In Standalone Mobile Mode, document Q&A is disabled or prompts connection to the PC, while Vision and MOSS Live run 100% standalone.
  2. **Option 2 (Official Cloud Document API for Standalone)**: Replace reverse-engineered NotebookLM with an official, token-authenticated cloud API (such as the Gemini API with File Search / uploaded context or OpenAI Assistant API). This uses standard Bearer token auth, zero WebView scraping, and official client SDKs.
* ***Proposed Edit:***
  * Location: Section 2.4 & Goal G4
  * Change: Update document to adopt Option 1 or Option 2 based on user selection.

---

## Prioritized Findings & AI Suggestions

### [ACT-B2] [`egm-design`, `egm-inventive-catalyst`, `egm-zoom-out`] - Severity: Blocker
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L83-L85](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L83-L85), [L269-L275](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L269-L275) (Section 2.5)
* **Discrepancy Description:** `MossEchoSuppressor` is specified as zeroing uplink PCM audio during speaker playback with a 200ms decay tail. Zeroing capture buffers enforces half-duplex walkie-talkie operation: the user cannot interrupt (barge in on) MOSS Live while it speaks, and the decay guard cuts off the user's initial words.
* **Rationale:** Full-duplex conversational voice models require simultaneous bidirectional audio.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Samsung Galaxy S24+ hardware natively provides DSP-grade hardware Acoustic Echo Cancellation when using `AudioSource.VOICE_COMMUNICATION`. Eliminating software zeroing restores full-duplex conversational barge-in without custom DSP overhead.
* ***Proposed Edit:***
  * Location: Section 2.5 (`PhoneAudioCaptureRouter`) & Architecture Diagram
  * Change: Eliminate `MossEchoSuppressor`. Attach Android platform `AcousticEchoCanceler` to the `AudioRecord` session ID using `AudioSource.VOICE_COMMUNICATION`. Document that hardware AEC isolates speaker audio while transmitting near-end speech for upstream MOSS barge-in detection.

---

### [ACT-B3] [`egm-comprehension`, `egm-zoom-out`] - Severity: Blocker
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L15](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L15), [L178-L189](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L178-L189), [L378-L380](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L378-L380)
* **Discrepancy Description:** The design states Workstation Mode connects to `127.0.0.1:8000` over local Wi-Fi. On Android, `127.0.0.1` is device loopback. Over Wi-Fi, connecting to `127.0.0.1` fails immediately with `ECONNREFUSED`.
* **Rationale:** Connecting to a PC over Wi-Fi requires the PC's actual LAN IP address.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** The codebase already contains `ServerScanner.findOrResolveBackend` and `BackendEndpoint` for this exact purpose. Retaining them preserves USB loopback via ADB reverse and automated LAN discovery over Wi-Fi.
* ***Proposed Edit:***
  * Location: Overview, Architecture, and Item 6/9
  * Change: Retain `ServerScanner.findOrResolveBackend` and `BackendEndpoint`. State that Workstation Mode routes to `127.0.0.1:8000` via USB ADB reverse, and resolves the PC LAN IP address via `ServerScanner` when connected over Wi-Fi.

---

### [ACT-B4] [`egm-zoom-out`, `egm-codebase-details`, `egm-implementation-readiness`] - Severity: Blocker
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L114-L189](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L114-L189), [L195-L216](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L195-L216), [L359-L400](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L359-L400)
* **Discrepancy Description:** The proposal invents new abstractions (`AssistantGateway`, `RemotePCAssistantGateway`, `RemotePCMossTransport`, `BackendModeRouter`) that duplicate existing working classes (`LiveAssistant`, `PhoneLiveAssistant`, `MossLiveTransport`, `OkHttpMossLiveTransport`) and break existing method signatures (`AssistantTurnResult`, `notebookId`).
* **Rationale:** The existing abstractions already support history, photo capture, and notebook context, and are already integrated with `ChatViewModel`.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Eliminates 6 redundant classes, reduces architecture complexity, and avoids breaking changes to `ChatViewModel`.
* ***Proposed Edit:***
  * Location: Sections 2.1, 2.2, 2.3, and Target Files 3, 5, 6, 8, 9, 14
  * Change: Drop `AssistantGateway`, `RemotePCAssistantGateway`, `RemotePCMossTransport`, and dynamic `BackendModeRouter`. Implement `DirectCloudLiveAssistant` (implementing `LiveAssistant`) and `DirectMossLiveTransport` (implementing `MossLiveTransport`). Retain `PhoneLiveAssistant` and `OkHttpMossLiveTransport` for workstation mode. Mode selection is resolved during initialization.

---

### [ACT-B5] [`egm-zoom-out`, `egm-implementation-readiness`, `egm-codebase-details`] - Severity: Blocker
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L276-L284](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L276-L284), [L443-L452](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L443-L452) (Items 17, 18)
* **Discrepancy Description:** Proposing `CameraKeyScanner` with Google ML Kit Latin OCR to scan desktop QR codes is a technical impossibility (ML Kit text recognition cannot decode QR codes). Furthermore, retaining ML Kit adds 15–20 MB of bloat for a one-time setup action.
* **Rationale:** Onboarding API keys is an infrequent setup action that does not justify camera scanning or terminal QR scripts.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Masked text fields with clipboard paste buttons are standard, lightweight, and completely sufficient.
* ***Proposed Edit:***
  * Location: Section 2.6, Items 1, 17, 18
  * Change: Drop `CameraKeyScanner.kt` and `scripts/show_auth_qr.py`. Remove `com.google.mlkit:text-recognition` from `build.gradle.kts`. Use standard masked Compose text fields with clipboard paste.

---

### [ACT-B6] [`egm-implementation-readiness`, `egm-codebase-details`] - Severity: Blocker
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L346-L352](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L346-L352) (Item 1) & [L475-L481](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L475-L481)
* **Discrepancy Description:** Adding `kotlinx-serialization-json` without `id("org.jetbrains.kotlin.plugin.serialization")` fails compilation. The verification plan also references `MockWebServer`, which is missing from `build.gradle.kts`.
* **Rationale:** Build files must explicitly include the compiler plugin and test libraries.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Essential for clean compilation and unit testing.
* ***Proposed Edit:***
  * Location: Target File 1 (`android/app/build.gradle.kts`)
  * Change: Add `plugins { id("org.jetbrains.kotlin.plugin.serialization") }` and `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`.

---

### [ACT-B7] [`egm-codebase-details`, `egm-comprehension`] - Severity: Blocker
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L381](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L381) (Item 7)
* **Discrepancy Description:** Target File 7 lists `AndroidPhotoEncoder.kt (Modify)`. This file does not exist; the actual file in the repository is `PhotoEncoder.kt`.
* **Rationale:** File paths in implementation plans must exist in the repository.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Fixes broken file reference.
* ***Proposed Edit:***
  * Location: Target File 7
  * Change: Rename path to `android/app/src/main/java/com/visualassistant/app/chat/PhotoEncoder.kt`.

---

### [ACT-B8] [`egm-implementation-readiness`, `egm-codebase-details`, `egm-comprehension`] - Severity: Blocker
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L344-L464](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L344-L464)
* **Discrepancy Description:** The implementation file list omits `MainActivity.kt` (composition root), `ChatRoute.kt` (navigation and legacy importer callbacks), `ConversationWorkspace.kt` (settings UI host), and `AssistantErrorMapper.kt`.
* **Rationale:** The plan must enumerate all files required to wire the feature.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Completes composition root and UI navigation wiring.
* ***Proposed Edit:***
  * Location: Detailed Implementation Plan
  * Change: Add `MainActivity.kt` (Modify), `ChatRoute.kt` (Modify), `ConversationWorkspace.kt` (Modify), and `AssistantErrorMapper.kt` (Create) to the target files list.

---

### [ACT-B9] [`egm-structural`, `egm-style`] - Severity: Blocker
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L9-L55](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L9-L55), [L256](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L256)
* **Discrepancy Description:** `The Problem`, `Goals`, and `Non-Goals` are formatted as H2 rather than H3 under `## Overview`. Section 2.4 leaks conversational ideation labeling ("Option C").
* **Rationale:** Conforms the document to the standard layout template.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Ensures clean document heading hierarchy.
* ***Proposed Edit:***
  * Location: Lines 9–55, Line 256
  * Change: Format `### The Problem`, `### Goals`, and `### Non-Goals` under `## Overview`. Rename Section 2.4 to `#### 2.4 Mobile Document Intelligence Client`.

---

### [ACT-M1] [`egm-comprehension`, `egm-zoom-out`, `egm-implementation-readiness`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L39](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L39), [L218-L225](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L218-L225), [L369-L375](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L369-L375)
* **Discrepancy Description:** The design mandates supporting OpenAI and StepFun but defines no selection logic. Furthermore, `gpt-6.1-terra` is not a public model identifier, and OpenAI uses Responses API while StepFun uses chat completions.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Resolves provider routing ambiguity and aligns with public API schemas.
* ***Proposed Edit:***
  * Location: Sections 2.2, 2.6, and Item 5
  * Change: Add an active provider selector in Settings ("OpenAI" vs "StepFun"). Use standard public models (`gpt-4o`/`gpt-4o-mini`). In `DirectCloudLiveAssistant`, specify JSON schema payloads and distinguish OpenAI Responses API from StepFun chat completions.

---

### [ACT-M2] [`egm-comprehension`, `egm-implementation-readiness`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L247-L250](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L247-L250), [L387-L394](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L387-L394)
* **Discrepancy Description:** Downlink wire framing and chunk completion schemas are missing. Consuming handshake events inside `DirectMossLiveTransport` without emitting them to `listener.onText()` causes `MossLiveSessionController` to hang in `CONFIGURING`.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Prevents state machine deadlocks.
* ***Proposed Edit:***
  * Location: Section 2.3 and Item 8
  * Change: Document tagged binary downlink framing and chunk completion schemas. Ensure `DirectMossLiveTransport` forwards `session_attached` and `session.updated` events to `MossLiveTransportListener`.

---

### [ACT-M3] [`egm-comprehension`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L283-L284](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L283-L284)
* **Discrepancy Description:** References a "PC Sync Button" in Settings to pull `.env` secrets, but no backend endpoint exists.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Removes nonexistent endpoint dependency.
* ***Proposed Edit:***
  * Location: Section 2.6
  * Change: Delete the PC Sync Button reference.

---

### [ACT-M4] [`egm-comprehension`, `egm-implementation-readiness`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L198-L215](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L198-L215)
* **Discrepancy Description:** Interfaces reference custom domain types (`AssistantTurnResult`, `CapturedPhoto`, `NotebookItem`) without specifying their packages and properties.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Ensures clear contract boundaries.
* ***Proposed Edit:***
  * Location: Section 2.1
  * Change: Cite package names and data structures matching existing definitions in `com.visualassistant.app`.

---

### [ACT-M5] [`egm-design`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L38-L39](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L38-L39), [L238-L250](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L238-L250)
* **Discrepancy Description:** Android 12+ halts background camera access. Pausing the app or turning off the screen crashes unhandled camera coroutines.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Prevents runtime crashes when phone is locked or pocketed.
* ***Proposed Edit:***
  * Location: Section 2.3
  * Change: Specify camera lifecycle state machine: suspend video frames (`video.state: false`) and maintain audio-only duplex streaming inside a Foreground Service with `camera|microphone` types.

---

### [ACT-M6] [`egm-design`, `egm-inventive-catalyst`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L38-L39](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L38-L39), [L246-L248](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L246-L248), [L328-L333](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L328-L333)
* **Discrepancy Description:** Continuous 1500ms JPEG frames + uncompressed PCM consume >650 MB/hour over cellular data.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Conserves mobile data and prevents thermal throttling.
* ***Proposed Edit:***
  * Location: Section 2.3 & Section 3
  * Change: Add an Economics & Data Footprint section. Implement an adaptive camera frame rate controller (5000ms heartbeat on static scenes, bursting to 1000–1500ms on motion or visual intent).

---

### [ACT-M7] [`egm-design`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L41-L43](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L41-L43), [L86-L96](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L86-L96), [L269-L275](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L269-L275)
* **Discrepancy Description:** Audio routing contention between MOSS and local Moonshine ONNX STT during live calls.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Clarifies pipeline operation and prevents CPU saturation.
* ***Proposed Edit:***
  * Location: Section 2.5
  * Change: Explicitly document that voice switching during MOSS Live is driven by inspecting MOSS server-side transcripts, and that local Moonshine STT is suspended during MOSS Live.

---

### [ACT-M8] [`egm-design`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L43-L44](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L43-L44), [L218-L225](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L218-L225)
* **Discrepancy Description:** No client-side rate limits or spending safeguards for BYOK keys.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Protects user credit balances from rapid taps or retry loops.
* ***Proposed Edit:***
  * Location: Section 2.2
  * Change: Implement client-side turn rate limiting (max 10 requests/min), retry ceilings, and UI debounce guards.

---

### [ACT-M9] [`egm-design`, `egm-inventive-catalyst`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L251-L255](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L251-L255), [L334-L336](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L334-L336)
* **Discrepancy Description:** Wi-Fi/cellular handover creates silent socket black holes where audio freezes for 15–30s.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Eliminates silent socket freezes during handovers.
* ***Proposed Edit:***
  * Location: Section 2.3
  * Change: Register `ConnectivityManager.NetworkCallback` to proactively detect network switches, terminate stale sockets, and reconnect immediately.

---

### [ACT-M10] [`egm-design`, `egm-structural`] - Severity: Major
* **Affected Scope:** Whole feature specification scope
* **Discrepancy Description:** Missing quantitative success metrics, formal risk matrix, and rollback plan.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Required by Feature Specification Standard.
* ***Proposed Edit:***
  * Location: Before Detailed Implementation Plan
  * Change: Add `## Success Metrics`, `## Risk Matrix & Mitigations`, and `## Fallback Strategy` sections.

---

### [ACT-M11] [`egm-structural`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L107-L284](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L107-L284), [L287-L318](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L287-L318)
* **Discrepancy Description:** Technical Plan lacks explicit `### Component Architecture` and `### Data Models & APIs` subheadings. Alternatives Considered elevates rejected options to full H3 headers.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Restores structural template compliance.
* ***Proposed Edit:***
  * Location: Lines 107–318
  * Change: Reformat Technical Plan subheadings and format alternatives as bullet points under `## Alternatives Considered`.

---

### [ACT-M12] [`egm-style`, `egm-implementation-readiness`] - Severity: Major
* **Affected Scope:** Lines 37–42, 111, 196, 293–317, 346–463
* **Discrepancy Description:** Absolute claims ("100% PC Independence"), conversational rejection wrappers (`Rejected by author:`), and grammatical mood dissonance across implementation items.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Restores professional technical style.
* ***Proposed Edit:***
  * Location: Goals, Alternatives, Detailed Implementation Plan
  * Change: Replace absolute claims with calibrated statements, rewrite rejection rationales objectively, and standardize all 20 implementation items on active imperative verbs.

---

### [ACT-M13] [`egm-implementation-readiness`, `egm-codebase-details`, `egm-style`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L353-L358](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L353-L358) (Item 2)
* **Discrepancy Description:** Cleartext traffic rules require modifying `network_security_config.xml`.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Ensures valid Android cleartext configuration.
* ***Proposed Edit:***
  * Location: Target File 2
  * Change: Add `android/app/src/main/res/xml/network_security_config.xml` (Modify) restricting cleartext strictly to `127.0.0.1` and private subnets.

---

### [ACT-M14] [`egm-codebase-details`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L400-L404](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L400-L404) (Item 10)
* **Discrepancy Description:** Omits `getHistory` from `NotebookLMClient`.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Required by `ChatViewModel` for notebook selection.
* ***Proposed Edit:***
  * Location: Target File 10
  * Change: Add `suspend fun getHistory(notebookId: String): List<ConversationTurn>`.

---

### [ACT-M15] [`egm-codebase-details`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L435-L442](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L435-L442) (Item 16)
* **Discrepancy Description:** Existing `SettingsInfo.kt` is not reconciled with proposed `SettingsScreen.kt`.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Clarifies UI component replacement.
* ***Proposed Edit:***
  * Location: Target File 16
  * Change: Clarify that `SettingsScreen.kt` replaces `SettingsInfo.kt`.

---

### [ACT-M16] [`egm-implementation-readiness`, `egm-style`] - Severity: Major
* **Affected Scope:** [docs/designs/backend_migration_to_android.md#L475-L487](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android.md#L475-L487)
* **Discrepancy Description:** Unit tests lack assertions for error codes (401, 429, 503), and hardware verification criteria are non-observable.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Justification:*** Makes verification plan actionable and testable.
* ***Proposed Edit:***
  * Location: Verification Plan
  * Change: Add explicit status code assertions and calibrate hardware tests to observable user outcomes.

---

### [ACT-N1..N4] [`egm-design`, `egm-structural`, `egm-style`, `egm-comprehension`] - Severity: Nit
* **Affected Scope:** Various lines across document
* **Discrepancy Description:** `RGB_565` color banding, orphaned "Airflow" reference, vague 100 MiB memory budget, divider dash syntax, metadata ordering, and wordiness.
* ***AI Suggestion:*** **PROPOSE FIX**
* ***Proposed Edit:***
  * Change: Use `ARGB_8888`, remove "Airflow", define 100 MiB budget as rolling JPEG cache, normalize dividers to `---`, reorder metadata to `Status`, `Author`, `Date`.
