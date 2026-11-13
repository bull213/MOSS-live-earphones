# Stage 9: Implementation Readiness Test Report

**Target:** `docs/designs/backend_migration_to_android.md` (Lines 340–490)  
**Standard:** `resources/document_standards/implementation-readiness-standard.md`  
**Reviewers:** `egm-implementation-readiness` (Pragmatic Tech Lead), `egm-codebase-details` (Codebase Details Auditor), `egm-style` (Technical Copy Editor)

---

## Reviewer Status

| Reviewer | Status |
| :--- | :--- |
| `egm-implementation-readiness` | completed |
| `egm-codebase-details` | completed |
| `egm-style` | completed |

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Undefined Google NotebookLM `batchexecute` Wire RPC Protocol & Schema - Severity: Blocker
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Items 10 & 12 (Lines 400–404, 411–416)
* **Discrepancy Description**: The plan directs the implementer to send cookie-authenticated HTTPS requests to `notebooklm.google.com/_/NotebooklmUi/data/batchexecute` for `listNotebooks()` and `askChat()`, but leaves the entire wire protocol undefined: RPC IDs (`wXbhs`/`q7Lhdb`), anti-CSRF token parsing (`SNlM0e`/`at` from HTML), session build labels (`f.sid`, `bl`), array envelope wrappers, and chunked `)]}'` anti-XSSI response unwrapping.
* **Finding & Guidance**: Document the exact HTTP request method, URL query parameters, form headers, payload envelopes, and array unwrapping logic, or discard reverse-engineered mobile NotebookLM in favor of a supported cloud API or PC proxy.

---

### 2. Technical Impossibility: Scanning QR Codes with ML Kit Latin OCR - Severity: Blocker
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Item 17 (Lines 443–447) & Item 1 (Lines 346–352)
* **Discrepancy Description**: Item 17 specifies that `CameraKeyScanner` uses bundled Google ML Kit Latin OCR (`com.google.mlkit:text-recognition`) to scan desktop screen QR codes. ML Kit Latin OCR is an alphanumeric text recognition model; it cannot detect or decode 2D matrix QR codes. Scanning QR codes requires `com.google.mlkit:barcode-scanning`, which is not in `build.gradle.kts`.
* **Finding & Guidance**: Either add `com.google.mlkit:barcode-scanning` to `build.gradle.kts` and implement a barcode analyzer, or eliminate `CameraKeyScanner` and QR sync altogether in favor of standard clipboard paste.

---

### 3. Missing Kotlin Serialization Compiler Plugin & MockWebServer Dependencies - Severity: Blocker
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Item 1 (Lines 346–352) & Verification Plan (Lines 475–481)
* **Discrepancy Description**: Item 1 adds the runtime library `kotlinx-serialization-json:1.6.3`, but omits the mandatory Gradle compiler plugin `id("org.jetbrains.kotlin.plugin.serialization")`. Adding the library without the compiler plugin causes `@Serializable` compile-time failures. Furthermore, the verification plan specifies unit tests using `MockWebServer`, but `com.squareup.okhttp3:mockwebserver` is missing from `build.gradle.kts`.
* **Finding & Guidance**: In Item 1, add `plugins { id("org.jetbrains.kotlin.plugin.serialization") }` and `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`.

---

### 4. Non-Existent Filepath Reference for `AndroidPhotoEncoder.kt` - Severity: Blocker
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Item 7 (Line 381)
* **Discrepancy Description**: The document lists `android/app/src/main/java/com/visualassistant/app/chat/AndroidPhotoEncoder.kt (Modify)`. This file does not exist in the repository. The actual file is `PhotoEncoder.kt`, which defines internal class `AndroidPhotoEncoder`.
* **Finding & Guidance**: Update the target file path to `android/app/src/main/java/com/visualassistant/app/chat/PhotoEncoder.kt`.

---

### 5. False Assertion of Codebase State & Duplication: `OkHttpMossLiveTransport` - Severity: Blocker
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Item 9 (Lines 395–399)
* **Discrepancy Description**: Item 9 proposes creating `RemotePCMossTransport.kt` to "refactor `OkHttpMossLiveTransport` to connect to `ws://127.0.0.1:8000/v1/realtime/moss-live` when Workstation Mode is active." In reality, `OkHttpMossLiveTransport` is located in `MossLiveTransport.kt` and already connects to the PC backend using dynamic discovery via `ServerScanner.findOrResolveBackend`.
* **Finding & Guidance**: Retain `OkHttpMossLiveTransport` in `MossLiveTransport.kt` for workstation mode and create only `DirectMossLiveTransport.kt` for standalone mode.

---

### 6. False Assertion & Class Reinvention: `ChatViewModel` & `AssistantGateway` - Severity: Blocker
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Item 3 (Lines 359–363) & Item 19 (Lines 453–458)
* **Discrepancy Description**:
  1. Item 19 claims `ChatViewModel` hardcodes `PhoneLiveAssistant`. In reality, `ChatViewModel` receives interface `LiveAssistant`.
  2. Item 3 defines `AssistantGateway`, reinventing `LiveAssistant` while omitting `notebookId` and stripping `readAloud`, `notebookTitle`, and `sources` from the return type.
* **Finding & Guidance**: Discard `AssistantGateway`. Implement `DirectCloudLiveAssistant` implementing existing `LiveAssistant` (`AssistantTurnResult`).

---

### 7. Missing Composition Root & Navigation Files in Target Manifest - Severity: Blocker
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Lines 344–464
* **Discrepancy Description**: The target files manifest omits critical files that wire the application:
  1. `MainActivity.kt`: Composition root where `LiveAssistant`, `MossLiveTransport`, `BackendEndpoint`, and `ChatViewModel` are wired.
  2. `ChatRoute.kt`: Contains UI launchers and callbacks for `PhoneBookImporter` (which is marked for deprecation).
  3. `ConversationWorkspace.kt`: Hardcodes rendering of `SettingsInfo`.
* **Finding & Guidance**: Add `MainActivity.kt` (Modify), `ChatRoute.kt` (Modify), and `ConversationWorkspace.kt` (Modify) to the target files manifest.

---

### 8. Non-Existent Class Reference: `AssistantErrorMapper` - Severity: Blocker
* **Reviewer Lens:** `egm-codebase-details`, `egm-implementation-readiness`
* **Affected Scope:** Item 19 (Line 457)
* **Discrepancy Description**: Item 19 directs the implementer to wire error translation via `AssistantErrorMapper`, but this class does not exist in the repository and is omitted from the target files list to create.
* **Finding & Guidance**: Add `AssistantErrorMapper.kt` (Create) under `com/visualassistant/app/network/ai/` and define its mapping table.

---

### 9. Underspecified Cloud Gateway Wire Contracts & OpenAI Protocol Mismatch - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`
* **Affected Scope:** Item 5 (Lines 369–375)
* **Discrepancy Description**: The plan states that `DirectCloudAssistantGateway` calls OpenAI and StepFun, but leaves active provider resolution undefined, omits JSON schemas for structured output, and claims both use `/v1/chat/completions`, whereas the PC backend uses OpenAI's Responses API (`client.responses.parse`) for `gpt-6.1-terra`.
* **Finding & Guidance**: Specify the active provider selection mechanism, provide exact JSON payload schemas for OpenAI and StepFun, and define the `AssistantAction` enum.

---

### 10. Remote PC Gateway Multipart Wire Contract Underspecified - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Item 6 (Lines 376–380)
* **Discrepancy Description**: The PC backend endpoint `POST /v1/assistant/turns` requires `multipart/form-data` with `question`, `image`, `history_json`, etc., and returns `AssistantTurnResponse`. The plan does not specify this multipart structure.
* **Finding & Guidance**: Document the `MultipartBody.Builder` construction, form field names, and mapping logic from `AssistantTurnResponse` to `AssistantTurnResult`.

---

### 11. Cleartext Configuration Requires Modifying `network_security_config.xml` - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-codebase-details`, `egm-style`
* **Affected Scope:** Item 2 (Lines 353–358)
* **Discrepancy Description**: In Android, `android:usesCleartextTraffic` is an all-or-nothing flag. Restricting cleartext strictly to loopback and private subnets requires modifying `network_security_config.xml`, which currently has `cleartextTrafficPermitted="true"` globally.
* **Finding & Guidance**: Add `android/app/src/main/res/xml/network_security_config.xml` (Modify) to the target files list with explicit domain configs.

---

### 12. MOSS Live Handshake State Desynchronization - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** Item 8 (Lines 387–394)
* **Discrepancy Description**: `MossLiveSessionController` expects `session_attached` and `session.updated` via `onText()` to transition to `LISTENING`. If `DirectMossLiveTransport` consumes the handshake internally without emitting these events, the controller hangs in `CONFIGURING`.
* **Finding & Guidance**: Specify whether `DirectMossLiveTransport` forwards raw provider handshake messages to `MossLiveTransportListener`, and add explicit lifecycle hooks (`suspendTransport()`, `resumeTransport()`).

---

### 13. Missing Method `getHistory` on `NotebookLMClient` - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Item 10 (Lines 400–404)
* **Discrepancy Description**: `ChatViewModel` calls `notebookAuthApi.getHistory(notebook.id)` to load previous Q&A turns upon notebook selection. Item 10 omits `getHistory` from `NotebookLMClient`.
* **Finding & Guidance**: Add `suspend fun getHistory(notebookId: String): List<ConversationTurn>` to `NotebookLMClient`.

---

### 14. Existing Component Conflict: `SettingsInfo.kt` vs `SettingsScreen.kt` - Severity: Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** Item 16 (Lines 435–442)
* **Discrepancy Description**: The app already contains `SettingsInfo.kt`. Item 16 proposes creating `SettingsScreen.kt` without defining whether `SettingsInfo.kt` is replaced, modified, or wrapped.
* **Finding & Guidance**: State explicitly whether `SettingsScreen.kt` replaces or embeds `SettingsInfo.kt`.

---

### 15. Grammatical Inconsistency & Taxonomy Ambiguity in Implementation List - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 346–463
* **Discrepancy Description**: Items mix imperative verbs, third-person indicative, and verbless noun fragments. Items 9 and 20 introduce non-standard actions `(Create / Refactor)` and `(Deprecate / Remove)`.
* **Finding & Guidance**: Standardize all items on active imperative verbs and adhere strictly to `(Create)`, `(Modify)`, or `(Delete)`.

---

### 16. Uncalibrated Latency Benchmarks & Unobservable Verification Criteria - Severity: Major
* **Reviewer Lens:** `egm-implementation-readiness`, `egm-style`
* **Affected Scope:** Lines 475–487
* **Discrepancy Description**: Requiring a 3s round-trip SLA for external cloud vision APIs is uncalibrated to real-world networks. Stating "verify speaker AEC" specifies an internal DSP process rather than an observable test behavior.
* **Finding & Guidance**: Calibrate test criteria to observable behavior: successful receipt within timeout (<10s) and duplex audio stability without acoustic echo feedback during simultaneous speech.

---

### 17. Nits (File Clamping, Version Constants, Slang) - Severity: Nit
* **Redundant Clamping in `PhotoEncoder.kt`**: 1600px edge clamping is already implemented.
* **Omission of `PROMPT_VERSION`**: Include `const val PROMPT_VERSION = "visual-assistant-v1"`.
* **UI Nomenclature**: Replace "active cookie status pill" with "badge" or "indicator".
* **Redundant Phrasing**: Strip redundant wordiness ("duplicate collisions", "earcon tones").
