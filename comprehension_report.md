# Stage 6: Comprehension Test Report

**Target:** `docs/designs/backend_migration_to_android.md`  
**FeatureRange:** Lines 1–339  
**DetailedRange:** Lines 340–490  
**Reviewer:** `egm-comprehension` (Naive Reader)

---

### What this proposes
This document proposes migrating the AI visual assistant from a PC-dependent FastAPI relay architecture into a dual-mode native Android application operating on a Samsung Galaxy S24+. In the default Standalone Mobile Mode, the phone executes local processing (offline Moonshine STT, image normalization, audio routing) and communicates directly with cloud AI services (OpenAI Vision, StepFun, MOSS Live WebSocket, and Google NotebookLM) without requiring a companion PC. Document and book intelligence is entirely offloaded to Google NotebookLM via session cookies extracted through an in-app WebView or PC QR code sync, eliminating on-device PDF parsing and vector indexing. Additionally, the system retains an optional Workstation Mode that routes traffic to the legacy local PC FastAPI server on port 8000 for development and desktop debugging.

### How the existing system works, per this document
As described in the document, the current visual assistant relies on a companion Windows PC running a Python FastAPI backend on port 8000 to relay requests between the mobile app and cloud AI providers. The phone connects to this backend either through a physical USB cable with `adb reverse tcp:8000 tcp:8000` or by scanning local Wi-Fi subnets using `ServerScanner`. Document assistance previously attempted on-device raw PDF parsing, OCR, and 384-dimensional vector embedding computation, which caused severe thermal throttling and memory pressure on the phone. User conversations and photo metadata are persisted locally on the phone in a Room database (`conversations.db`) alongside app-private normalized JPEG files.

---

## Findings

### 1. Google NotebookLM Anti-CSRF Token & RPC Envelope Omission - Severity: Blocker
* **Affected Scope**: Section 2.4 (lines 256–268), Section 340 File 12 (lines 411–416)
* **Discrepancy Description & Rationale**: The document specifies that `DirectMobileNotebookLMClient` will execute Google internal web RPCs (`batchexecute`) directly from Android using extracted session cookies (`SIDTS`, `__Secure-1PSID`, `__Secure-1PAPISID`). However, Google's `batchexecute` framework requires an anti-CSRF token (the `at` parameter / `SNlM0e`) embedded in the initial page HTML payload (`WIZ_global_data`), not accessible via Android's `CookieManager`. Furthermore, the internal RPC request structure (method IDs, envelope serialization, chunked `)]}'` anti-XSSI response framing) is omitted. Direct calls using cookies alone will fail with HTTP 400 / CSRF errors.

### 2. Workstation Mode Over Wi-Fi Cannot Use Loopback `127.0.0.1` - Severity: Blocker
* **Affected Scope**: Overview (line 15), Architecture (lines 178–189), Section 340 Files 6 & 9 (lines 378–380, 397–399)
* **Discrepancy Description & Rationale**: The document states that Workstation Mode routes traffic to `127.0.0.1:8000` "via ADB or local Wi-Fi" and hardcodes `http://127.0.0.1:8000` in `RemotePCAssistantGateway` and `ws://127.0.0.1:8000` in `RemotePCMossTransport`. In Android, `127.0.0.1` represents the phone's local loopback. While USB ADB reverse port-forwarding redirects loopback port 8000 to the PC, connecting over Wi-Fi without ADB requires connecting to the PC's LAN IP address. Without host IP input or discovery, Wi-Fi workstation mode cannot function.

### 3. Provider Selection & Routing Logic Undefined - Severity: Blocker
* **Affected Scope**: Goals G3 (line 39), Section 2.2 (lines 218–225), Section 2.6 (lines 278–284), Section 340 File 5 (lines 372–375), File 16 (lines 438–442)
* **Discrepancy Description & Rationale**: The document mandates direct communication with both OpenAI Vision (`gpt-6.1-terra`) and StepFun (`step-5-preview`), and specifies configuring keys for both in `SecureCredentialStore`. However, neither `DirectCloudAssistantGateway`, `BackendModeRouter`, nor `SettingsScreen` defines how the app selects between OpenAI and StepFun. There is no UI setting, fallback cascade, or routing condition specified.

### 4. MOSS Live Downlink & Acknowledgement Protocol Wire Specification Missing - Severity: Major
* **Affected Scope**: Section 2.3 (lines 247–250), Section 340 File 8 (lines 387–394)
* **Discrepancy Description & Rationale**: The specification states that the client must transmit `playback.chunk.completed` and `playback.completed` matching "provider run and turn IDs" and feed 48 kHz PCM audio downlink into `MossAudioPlayer`. However, it does not specify the wire protocol format for downlink messages (whether audio chunks are raw binary frames with tags, base64 within JSON events, or multiplexed text frames), how `run_id` and `turn_id` are delivered to the client, or what the exact JSON schema is for chunk completion events.

### 5. Nonexistent PC Endpoint for Key Syncing - Severity: Major
* **Affected Scope**: Section 2.6 (lines 283–284)
* **Discrepancy Description & Rationale**: The design introduces a "PC Sync Button" in `SettingsScreen` that "automatically syncs keys from `backend/.env` over local loopback when attached to the PC." However, neither the PC backend routes nor the implementation plan specifies what endpoint serves `.env` secrets, what security checks protect it, or what HTTP contract is used.

### 6. Undefined Interface Types in Core Abstractions - Severity: Major
* **Affected Scope**: Section 2.1 (lines 198–215)
* **Discrepancy Description & Rationale**: The core Kotlin interfaces reference several custom domain types that are neither defined in the document nor linked:
  * `ChatMessage` in `AssistantGateway.answer(...)`
  * `MossLiveTransportListener` in `MossLiveTransport.connect(...)`
  * `NotebookItem` in `NotebookLMClient.listNotebooks()`
  * `GroundedChatResult` in `NotebookLMClient.askChat(...)`

### 7. Core Subsystem Files Omitted from Implementation File List - Severity: Major
* **Affected Scope**: Section 340 "Detailed Implementation Plan - Target Files to Create / Modify / Delete" (lines 340–464)
* **Discrepancy Description & Rationale**: Several core components described in the Architecture diagram and CUJs are omitted from the Target Files list:
  * `MossAudioPlayer` (Section 1.1, CUJ 2, Section 3.2): Referenced as handling 48 kHz audio playback with a 1-frame threshold, but no file is listed to create or modify.
  * `MossEchoSuppressor` (Section 1.1, Section 2.5): Referenced as providing dynamic AEC and decay guard, but omitted from Target Files.
  * `AssistantErrorMapper` (Section 340 File 19 line 457): Referenced as being wired into `ChatViewModel`, but not listed as a target file to create.

### 8. Cloud Model Identifiers are Gateway Aliases, Not Public Endpoints - Severity: Major
* **Affected Scope**: Goals G3 (line 39), CUJ 1 (line 73), Section 2.2 (lines 220–221), Section 340 File 5 (line 373)
* **Discrepancy Description & Rationale**: The design hardcodes specific model strings: OpenAI `gpt-6.1-terra` and StepFun `step-5-preview`. In public OpenAI and StepFun APIs, these model strings do not exist (standard OpenAI models are `gpt-4o`, `gpt-4o-mini`, etc.). If direct cloud calls are made to public provider endpoints, using `gpt-6.1-terra` will fail with HTTP 404 / `model_not_found`.

### 9. Undefined Reference to "Airflow" - Severity: Nit
* **Affected Scope**: Overview (line 11)
* **Discrepancy Description & Rationale**: Line 11 refers to the project as "Airflow / AI Visual Assistant", but "Airflow" is never defined or mentioned again.

### 10. File Naming Discrepancy for Image Normalization - Severity: Nit
* **Affected Scope**: Section 2.2 (line 226) vs Section 340 File 7 (line 381)
* **Discrepancy Description & Rationale**: Section 2.2 designates `BitmapUtils.kt` as the location for image normalization parity, while Section 340 specifies modifying `AndroidPhotoEncoder.kt`.

### 11. Orphaned "Option C" Section Header - Severity: Nit
* **Affected Scope**: Section 2.4 (line 256)
* **Discrepancy Description & Rationale**: Section 2.4 is titled "Option C NotebookLM Mobile Client", but Options A and B are never described or listed anywhere in the document.

### 12. Vague "100 MiB Rolling Memory Budget" Definition - Severity: Nit
* **Affected Scope**: CUJ 1 (line 74), Architecture Diagram (line 117)
* **Discrepancy Description & Rationale**: The document mentions a "100 MiB rolling memory budget" without clarifying whether this refers to RAM limits, Room database size, or JPEG disk cache, nor does it specify the eviction policy.

### 13. Unstated Target Demographic Assumptions - Severity: Nit
* **Affected Scope**: Open Question 1 (line 60), Section 2.6 (line 276), Section 3.3 (line 336)
* **Discrepancy Description & Rationale**: The document refers to designing for "older adult users" and "senior-friendly" UI in later sections, but target user demographics, constraints, or accessibility requirements are not defined in the Overview, Problem, or Goals.

### 14. Assumed PC Scripts & Storage State Prerequisites - Severity: Nit
* **Affected Scope**: CUJ 4 (line 99), Section 2.4 (lines 263–264), Section 340 File 18 (lines 448–452)
* **Discrepancy Description & Rationale**: The document references `backend/.data/notebooklm/storage_state.json` without explaining how it is initially created on the PC or providing the Windows batch launcher for the QR script.
