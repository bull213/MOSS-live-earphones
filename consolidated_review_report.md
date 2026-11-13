# Consolidated EGM Review Report

**Target Document:** `docs/designs/backend_migration_to_android.md` (Lines 1–490)  
**Execution Mode:** Consolidated full flow (Stages 5, 6, 7, 8, 9)  
**Date:** 2026-11-13  
**Author:** bull213  

---

## Executive Summary

The multi-lens adversarial review evaluated `docs/designs/backend_migration_to_android.md` across 5 distinct review stages: Initial Zoom Out, Comprehension Test, Critic Review, Intermediate Zoom Out, and Implementation Readiness.

The review confirmed that the overarching business motivation—making the visual assistant mobile, cable-free, and independent from a Windows PC backend—is sound. However, the current specification **cannot be approved in its present state** due to critical architectural dead-ends, false assumptions regarding Google web endpoints, destructive audio processing, and extensive class/interface reinvention.

### Primary Architectural Realignment Required Before Implementation
1. **Google NotebookLM Reverse-Engineering Trap**: Google NotebookLM provides no public API and strictly blocks in-app WebViews (`disallowed_useragent`). Scraping cookies and reverse-engineering `batchexecute` without dynamic anti-CSRF tokens (`SNlM0e`) will immediately fail. The design must either retain NotebookLM as a workstation-assisted capability or migrate standalone mobile document Q&A to an official cloud API (Gemini File API / OpenAI Assistant API).
2. **Duplex Voice Degradation**: Muting the microphone during speaker playback (`MossEchoSuppressor`) degrades duplex voice into a half-duplex walkie-talkie, breaking user barge-in. Android's native hardware `AcousticEchoCanceler` on `VOICE_COMMUNICATION` must be used instead.
3. **Reinventing Existing Codebase Abstractions**: The existing codebase already contains `LiveAssistant`, `PhoneLiveAssistant`, `MossLiveTransport`, and `OkHttpMossLiveTransport` (with working Wi-Fi LAN discovery via `ServerScanner`). The design should not introduce duplicate interfaces (`AssistantGateway`) or duplicate remote classes.
4. **Credential Ingestion Gold-Plating**: Bundling Google ML Kit Latin OCR (which cannot scan QR codes anyway) solely to scan keys off desktop monitors introduces 20 MB of bloat. Standard masked input fields with clipboard paste buttons are sufficient.

---

## Prioritized Action Items

### 🔴 Blockers (Must resolve before any code is written)

#### ACT-B1: Discontinue Reverse-Engineering Google NotebookLM `batchexecute` on Mobile
* **Sources:** `zoom_out_report.md` (ZO-1), `comprehension_report.md` (COMP-1), `critic_report.md` (CRIT-1), `intermediate_zoom_out_report.md` (IZO-1), `implementation_readiness_report.md` (IR-1)
* **Description**: Google blocks Google Account authentication inside Android WebViews (`disallowed_useragent`), and `batchexecute` calls fail without an HTML-embedded anti-CSRF token (`SNlM0e`). Calling private RPCs in pure Kotlin without Playwright browser automation is an unmaintainable dead-end.
* **Resolution**:
  - *Path A (Workstation-Assisted)*: Document Q&A via NotebookLM is explicitly designated as a Workstation Mode feature routed through the PC backend.
  - *Path B (Supported Mobile Cloud API)*: If standalone mobile document search is mandatory, replace reverse-engineered NotebookLM with an official, token-authenticated cloud API (Gemini File API or OpenAI Assistant API).

#### ACT-B2: Eliminate Software Echo Zeroing to Restore Full-Duplex Voice (Barge-In)
* **Sources:** `zoom_out_report.md` (ZO-5), `critic_report.md` (CRIT-2), `intermediate_zoom_out_report.md` (IZO-4)
* **Description**: Zeroing uplink PCM buffers during playback (`MossEchoSuppressor`) converts full-duplex conversational voice into half-duplex ducking, preventing the user from interrupting MOSS Live and clipping user speech.
* **Resolution**: Eliminate custom software buffer zeroing. Attach Android's platform `AcousticEchoCanceler` to the `AudioRecord` session ID using `AudioSource.VOICE_COMMUNICATION`, preserving bidirectional audio for barge-in detection.

#### ACT-B3: Fix Wi-Fi Workstation Networking Fallacy (`127.0.0.1` Loopback)
* **Sources:** `comprehension_report.md` (COMP-2), `intermediate_zoom_out_report.md` (IZO-3)
* **Description**: On Android, `127.0.0.1` is the device's local loopback. Hardcoding `127.0.0.1:8000` for Wi-Fi workstation mode connects to the phone itself and fails immediately with `ECONNREFUSED`.
* **Resolution**: Retain `ServerScanner.findOrResolveBackend` and `BackendEndpoint` from the existing codebase so that USB connects via `127.0.0.1` (via ADB reverse) while Wi-Fi resolves the PC's actual LAN IP address.

#### ACT-B4: Reuse Existing Interfaces and Transports Instead of Duplicating Core Abstractions
* **Sources:** `zoom_out_report.md` (ZO-2), `intermediate_zoom_out_report.md` (IZO-2), `implementation_readiness_report.md` (IR-5, IR-6)
* **Description**: The codebase already has `LiveAssistant`, `PhoneLiveAssistant`, `MossLiveTransport`, and `OkHttpMossLiveTransport`. Inventing `AssistantGateway`, `RemotePCAssistantGateway`, `RemotePCMossTransport`, and `BackendModeRouter` introduces ~6 redundant classes and breaks existing return types (`AssistantTurnResult`).
* **Resolution**: Drop `AssistantGateway`, `RemotePCAssistantGateway`, `RemotePCMossTransport`, and `BackendModeRouter`. Implement `DirectCloudLiveAssistant` (implementing `LiveAssistant`) and `DirectMossLiveTransport` (implementing `MossLiveTransport`). Retain `PhoneLiveAssistant` and `OkHttpMossLiveTransport` for workstation mode. Mode selection is resolved during initialization.

#### ACT-B5: Eliminate QR Scanning via ML Kit Latin OCR (Technical Impossibility & Bloat)
* **Sources:** `zoom_out_report.md` (ZO-3), `intermediate_zoom_out_report.md` (IZO-5), `implementation_readiness_report.md` (IR-2)
* **Description**: Google ML Kit Latin OCR (`com.google.mlkit:text-recognition`) parses text characters; it cannot scan or decode QR codes. Retaining ML Kit adds 15–20 MB of APK bloat for a one-time credential setup action.
* **Resolution**: Drop `CameraKeyScanner.kt`, `scripts/show_auth_qr.py`, and remove `com.google.mlkit:text-recognition` from `build.gradle.kts`. Use standard masked input fields with one-tap clipboard paste buttons.

#### ACT-B6: Add Missing Kotlin Serialization Gradle Plugin & Test Dependencies
* **Sources:** `implementation_readiness_report.md` (IR-3)
* **Description**: Adding runtime dependency `kotlinx-serialization-json` without applying the compiler plugin `id("org.jetbrains.kotlin.plugin.serialization")` causes compile-time failures. `mockwebserver` is also missing from `build.gradle.kts`.
* **Resolution**: In Item 1 of the implementation plan, add `plugins { id("org.jetbrains.kotlin.plugin.serialization") }` and `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`.

#### ACT-B7: Correct Non-Existent Filepath for `AndroidPhotoEncoder.kt`
* **Sources:** `comprehension_report.md` (COMP-10), `implementation_readiness_report.md` (IR-4)
* **Description**: Target File 7 references `AndroidPhotoEncoder.kt`, which does not exist. The actual file is `PhotoEncoder.kt`.
* **Resolution**: Update the target file path to `android/app/src/main/java/com/visualassistant/app/chat/PhotoEncoder.kt`.

#### ACT-B8: Add Missing Composition Root and UI Navigation Files to File Manifest
* **Sources:** `comprehension_report.md` (COMP-7), `implementation_readiness_report.md` (IR-7, IR-8)
* **Description**: The implementation manifest omits `MainActivity.kt` (composition root), `ChatRoute.kt` (book import callbacks and settings navigation), `ConversationWorkspace.kt` (rendering settings), and `AssistantErrorMapper.kt`.
* **Resolution**: Add these files to the target files list with explicit modification and creation specifications.

#### ACT-B9: Fix Heading Hierarchy Violations and Strip Ideation Residue
* **Sources:** `critic_report.md` (CRIT-3, CRIT-4)
* **Description**: `The Problem`, `Goals`, and `Non-Goals` are formatted as H2 rather than H3 under `## Overview`. Section 2.4 leaks conversational ideation labeling ("Option C").
* **Resolution**: Nest subsections under `## Overview` and rename Section 2.4 symmetrically to `#### 2.4 Mobile NotebookLM Client`.

---

### 🟡 Major Findings (High Priority Corrections)

#### ACT-M1: Define Active Cloud Provider Routing & Align OpenAI Wire API
* **Sources:** `comprehension_report.md` (COMP-3, COMP-8), `intermediate_zoom_out_report.md` (IZO-6), `implementation_readiness_report.md` (IR-9)
* **Resolution**: Add an active provider selector in Settings ("OpenAI" vs "StepFun"). Use public model identifiers (`gpt-4o`/`gpt-4o-mini`). In `DirectCloudLiveAssistant`, document JSON schema definitions for structured output and distinguish OpenAI Responses API from StepFun chat completions.

#### ACT-M2: Specify MOSS Live Downlink Wire Protocol & Fix Handshake State Desynchronization
* **Sources:** `comprehension_report.md` (COMP-4), `implementation_readiness_report.md` (IR-12)
* **Resolution**: Specify downlink framing (raw binary vs base64 JSON events), `run_id`/`turn_id` extraction, and chunk completion JSON schemas. Ensure `DirectMossLiveTransport` emits `session_attached` and `session.updated` events to `MossLiveTransportListener` so `MossLiveSessionController` does not hang in `CONFIGURING`.

#### ACT-M3: Remove Phantom PC Secret Sync Button
* **Sources:** `comprehension_report.md` (COMP-5)
* **Resolution**: Remove the "PC Sync Button" from the Settings UI specification, as no backend endpoint exists to serve `.env` secrets over HTTP.

#### ACT-M4: Provide Concrete Definitions for Custom Domain Types
* **Sources:** `comprehension_report.md` (COMP-6), `implementation_readiness_report.md` (IR-11)
* **Resolution**: Explicitly specify the properties, schemas, and package locations for `AssistantTurnResult`, `CapturedPhoto`, and `NotebookItem`.

#### ACT-M5: Define Android Background Lifecycle & CameraX State Machine
* **Sources:** `critic_report.md` (CRIT-5)
* **Resolution**: Define the camera lifecycle state machine. When the app pauses or screen turns off, gracefully notify MOSS transport to suspend video (`video.state: false`) and maintain audio-only duplex communication inside an Android 14+ compatible Foreground Service.

#### ACT-M6: Specify Resource Economics & Add Adaptive Camera Heartbeat
* **Sources:** `zoom_out_report.md` (ZO-6), `critic_report.md` (CRIT-6)
* **Resolution**: Add a Resource Economics section documenting cellular bandwidth usage (~650 MB/hour for raw PCM + 1500ms JPEG). Implement an adaptive camera frame rate controller (5000ms heartbeat on static scenes, bursting to 1000–1500ms on motion or visual queries).

#### ACT-M7: Clarify Audio Routing Model During Active MOSS Calls
* **Sources:** `critic_report.md` (CRIT-7)
* **Resolution**: Clarify that voice switching during MOSS Live is driven by inspecting MOSS server-side transcripts, and that local Moonshine ONNX STT is suspended during MOSS Live to prevent CPU and thermal saturation.

#### ACT-M8: Implement Client-Side Rate Limits & Spend Ceilings for BYOK Keys
* **Sources:** `critic_report.md` (CRIT-8)
* **Resolution**: Implement client-side turn rate limiting (e.g. max 10 requests/minute), retry ceilings, and UI debounce guards to protect user API balances.

#### ACT-M9: Register NetworkCallback for Fast Cellular/Wi-Fi Handover
* **Sources:** `zoom_out_report.md` (ZO-7), `critic_report.md` (CRIT-9)
* **Resolution**: Register an Android `ConnectivityManager.NetworkCallback` to detect network interface handovers, immediately terminate stale sockets, and initiate fast reconnect without waiting for 15-second ping timeouts.

#### ACT-M10: Add Success Metrics, Formal Risk Matrix, and Fallback Strategy
* **Sources:** `critic_report.md` (CRIT-10)
* **Resolution**: Add quantitative SLIs/SLOs (cold start time, vision latency p95, reconnect time), an explicit Risk Matrix with mitigations, and an automated fallback workflow.

#### ACT-M11: Reformat Technical Plan and Alternatives Considered to Standard Template
* **Sources:** `critic_report.md` (CRIT-11)
* **Resolution**: Structure `## Technical Plan` with explicit `### Component Architecture` and `### Data Models & APIs` subheadings. Reformat alternatives as bullet points under `## Alternatives Considered`.

#### ACT-M12: Calibrate Claims, Strip Fluff, and Standardize Grammatical Mood
* **Sources:** `critic_report.md` (CRIT-12), `implementation_readiness_report.md` (IR-15)
* **Resolution**: Replace absolute claims ("100% PC Independence") with objective statements ("Standalone PC-Independent Operation"). Remove conversational wrappers (`Rejected by author:`). Standardize all 20 implementation items on active imperative verbs.

#### ACT-M13: Add `network_security_config.xml` to Modify Cleartext Traffic
* **Sources:** `implementation_readiness_report.md` (IR-11)
* **Resolution**: Add `android/app/src/main/res/xml/network_security_config.xml` (Modify) to the plan to restrict cleartext permissions strictly to `127.0.0.1` and local private subnets for workstation mode.

#### ACT-M14: Add Missing Method `getHistory` to `NotebookLMClient`
* **Sources:** `implementation_readiness_report.md` (IR-13)
* **Resolution**: Add `suspend fun getHistory(notebookId: String): List<ConversationTurn>` to `NotebookLMClient`.

#### ACT-M15: Reconcile `SettingsInfo.kt` vs `SettingsScreen.kt`
* **Sources:** `implementation_readiness_report.md` (IR-14)
* **Resolution**: State explicitly that `SettingsScreen.kt` replaces or embeds the existing `SettingsInfo.kt`.

#### ACT-M16: Calibrate Verification Plan Latency Benchmarks & Test Assertions
* **Sources:** `implementation_readiness_report.md` (IR-16)
* **Resolution**: Expand the unit test suite with explicit mock HTTP status codes (200, 401, 429, 503) and concrete assertions. Calibrate hardware test criteria to observable user behaviors (<10s response, duplex stability without acoustic feedback).

---

### 🟢 Nits (Low Priority Improvements)

* **ACT-N1**: Use `Bitmap.Config.ARGB_8888` on Galaxy S24+ instead of `RGB_565` to avoid color banding on appliance dials and text.
* **ACT-N2**: Remove orphaned reference to "Airflow" and frame target demographic requirements neutrally.
* **ACT-N3**: Clarify the "100 MiB rolling memory budget" definition (RAM vs disk cache vs database pruning).
* **ACT-N4**: Normalize Markdown dividers to `---`, reorder metadata to `Status`, `Author`, `Date`, and strip redundant phrasing.

---

## Traceability Matrix

| Raw Finding ID | Review Stage & Reviewer Lens | Severity | Consolidated Action Item |
| :--- | :--- | :--- | :--- |
| **ZO-1** | Stage 5: `egm-zoom-out` | Blocker | **ACT-B1**: Discontinue Reverse-Engineering NotebookLM |
| **ZO-2** | Stage 5: `egm-zoom-out` | Major | **ACT-B4**: Reuse Existing Interfaces & Transports |
| **ZO-3** | Stage 5: `egm-zoom-out` | Major | **ACT-B5**: Eliminate QR Scanning via ML Kit OCR |
| **ZO-4** | Stage 5: `egm-zoom-out` | Major | **ACT-B4**: Reuse Existing Interfaces & Transports |
| **ZO-5** | Stage 5: `egm-inventive-catalyst` | Major | **ACT-B2**: Eliminate Software Echo Zeroing |
| **ZO-6** | Stage 5: `egm-inventive-catalyst` | Nit | **ACT-M6**: Specify Resource Economics & Adaptive Heartbeat |
| **ZO-7** | Stage 5: `egm-inventive-catalyst` | Nit | **ACT-M9**: Register NetworkCallback for Handover |
| **ZO-8** | Stage 5: `egm-zoom-out` | Nit | **ACT-M12**: Calibrate Claims & Strip Fluff |
| **COMP-1** | Stage 6: `egm-comprehension` | Blocker | **ACT-B1**: Discontinue Reverse-Engineering NotebookLM |
| **COMP-2** | Stage 6: `egm-comprehension` | Blocker | **ACT-B3**: Fix Wi-Fi Workstation Networking Fallacy |
| **COMP-3** | Stage 6: `egm-comprehension` | Blocker | **ACT-M1**: Define Active Cloud Provider Routing |
| **COMP-4** | Stage 6: `egm-comprehension` | Major | **ACT-M2**: Specify MOSS Live Downlink Wire Protocol |
| **COMP-5** | Stage 6: `egm-comprehension` | Major | **ACT-M3**: Remove Phantom PC Secret Sync Button |
| **COMP-6** | Stage 6: `egm-comprehension` | Major | **ACT-M4**: Provide Concrete Definitions for Domain Types |
| **COMP-7** | Stage 6: `egm-comprehension` | Major | **ACT-B8**: Add Missing Composition Root Files |
| **COMP-8** | Stage 6: `egm-comprehension` | Major | **ACT-M1**: Define Active Cloud Provider Routing |
| **COMP-9** | Stage 6: `egm-comprehension` | Nit | **ACT-N2**: Remove Orphaned "Airflow" Reference |
| **COMP-10** | Stage 6: `egm-comprehension` | Nit | **ACT-B7**: Correct Non-Existent Filepath for PhotoEncoder |
| **COMP-11** | Stage 6: `egm-comprehension` | Nit | **ACT-B9**: Fix Heading Hierarchy & Strip Ideation Residue |
| **COMP-12** | Stage 6: `egm-comprehension` | Nit | **ACT-N3**: Clarify 100 MiB Memory Budget Definition |
| **COMP-13** | Stage 6: `egm-comprehension` | Nit | **ACT-N2**: Remove Orphaned Demographic Assumptions |
| **COMP-14** | Stage 6: `egm-comprehension` | Nit | **ACT-B5**: Eliminate QR Scanning via ML Kit OCR |
| **CRIT-1** | Stage 7: `egm-design` | Blocker | **ACT-B1**: Discontinue Reverse-Engineering NotebookLM |
| **CRIT-2** | Stage 7: `egm-design` | Blocker | **ACT-B2**: Eliminate Software Echo Zeroing |
| **CRIT-3** | Stage 7: `egm-structural` | Blocker | **ACT-B9**: Fix Heading Hierarchy & Strip Ideation Residue |
| **CRIT-4** | Stage 7: `egm-structural` / `style` | Blocker | **ACT-B9**: Fix Heading Hierarchy & Strip Ideation Residue |
| **CRIT-5** | Stage 7: `egm-design` | Major | **ACT-M5**: Define Background Lifecycle & CameraX State |
| **CRIT-6** | Stage 7: `egm-design` | Major | **ACT-M6**: Specify Resource Economics & Adaptive Heartbeat |
| **CRIT-7** | Stage 7: `egm-design` | Major | **ACT-M7**: Clarify Audio Routing Model During MOSS |
| **CRIT-8** | Stage 7: `egm-design` | Major | **ACT-M8**: Implement Client-Side Rate Limits & Caps |
| **CRIT-9** | Stage 7: `egm-design` | Major | **ACT-M9**: Register NetworkCallback for Handover |
| **CRIT-10** | Stage 7: `egm-design` / `structural` | Major | **ACT-M10**: Add Success Metrics, Risk Matrix & Fallback |
| **CRIT-11** | Stage 7: `egm-structural` | Major | **ACT-M11**: Reformat Technical Plan to Standard Template |
| **CRIT-12** | Stage 7: `egm-style` | Major | **ACT-M12**: Calibrate Claims & Strip Fluff |
| **CRIT-13** | Stage 7: `egm-design` / `structural` | Nit | **ACT-N1**, **ACT-N4**: Formatting, Dividers & Color Banding |
| **IZO-1** | Stage 8: `egm-zoom-out` | Blocker | **ACT-B1**: Discontinue Reverse-Engineering NotebookLM |
| **IZO-2** | Stage 8: `egm-zoom-out` | Blocker | **ACT-B4**: Reuse Existing Interfaces & Transports |
| **IZO-3** | Stage 8: `egm-zoom-out` | Blocker | **ACT-B3**: Fix Wi-Fi Workstation Networking Fallacy |
| **IZO-4** | Stage 8: `egm-zoom-out` | Major | **ACT-B2**: Eliminate Software Echo Zeroing |
| **IZO-5** | Stage 8: `egm-zoom-out` | Major | **ACT-B5**: Eliminate QR Scanning via ML Kit OCR |
| **IZO-6** | Stage 8: `egm-zoom-out` | Major | **ACT-M1**: Define Active Cloud Provider Routing |
| **IZO-7** | Stage 8: `egm-zoom-out` | Nit | **ACT-M12**: Calibrate Claims & Strip Fluff |
| **IR-1** | Stage 9: `egm-implementation-readiness` | Blocker | **ACT-B1**: Discontinue Reverse-Engineering NotebookLM |
| **IR-2** | Stage 9: `egm-implementation-readiness` / `codebase` | Blocker | **ACT-B5**: Eliminate QR Scanning via ML Kit OCR |
| **IR-3** | Stage 9: `egm-implementation-readiness` / `codebase` | Blocker | **ACT-B6**: Add Kotlin Serialization & MockWebServer |
| **IR-4** | Stage 9: `egm-codebase-details` | Blocker | **ACT-B7**: Correct Non-Existent Filepath for PhotoEncoder |
| **IR-5** | Stage 9: `egm-codebase-details` | Blocker | **ACT-B4**: Reuse Existing Interfaces & Transports |
| **IR-6** | Stage 9: `egm-codebase-details` | Blocker | **ACT-B4**: Reuse Existing Interfaces & Transports |
| **IR-7** | Stage 9: `egm-implementation-readiness` / `codebase` | Blocker | **ACT-B8**: Add Missing Composition Root Files |
| **IR-8** | Stage 9: `egm-implementation-readiness` / `codebase` | Blocker | **ACT-B8**: Add Missing Composition Root Files |
| **IR-9** | Stage 9: `egm-implementation-readiness` / `codebase` | Major | **ACT-M1**: Define Active Cloud Provider Routing |
| **IR-10** | Stage 9: `egm-implementation-readiness` | Major | **ACT-B4**: Reuse Existing Interfaces & Transports |
| **IR-11** | Stage 9: `egm-implementation-readiness` / `style` | Major | **ACT-M13**: Add network_security_config.xml |
| **IR-12** | Stage 9: `egm-implementation-readiness` | Major | **ACT-M2**: Specify MOSS Live Downlink Wire Protocol |
| **IR-13** | Stage 9: `egm-codebase-details` | Major | **ACT-M14**: Add Missing Method getHistory |
| **IR-14** | Stage 9: `egm-codebase-details` | Major | **ACT-M15**: Reconcile SettingsInfo.kt vs SettingsScreen.kt |
| **IR-15** | Stage 9: `egm-style` | Major | **ACT-M12**: Calibrate Claims & Strip Fluff |
| **IR-16** | Stage 9: `egm-implementation-readiness` / `style` | Major | **ACT-M16**: Calibrate Verification Plan Benchmarks |
| **IR-17** | Stage 9: `egm-codebase-details` / `style` | Nit | **ACT-N4**: Formatting, Constants & Redundancies |
