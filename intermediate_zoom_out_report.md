# Stage 8: Intermediate Zoom Out Review Report

**Target:** `docs/designs/backend_migration_to_android.md` (Lines 1–490)  
**Standard:** `resources/document_standards/zoom-out-standard.md`  
**Reviewer:** `egm-zoom-out` (Skeptical Architect)  
**Context Ingested:** `zoom_out_report.md`, `comprehension_report.md`, `critic_report.md`

---

## Reviewer Status

| Reviewer | Status |
| :--- | :--- |
| `egm-zoom-out` | completed |

---

## High-Altitude Realignment Synthesis

Synthesizing findings across Comprehension and Critic stages reveals that the current draft risks failing its core objective—enabling PC-independent mobile execution—by introducing massive speculative complexity and fragile reverse-engineering.

1. **The NotebookLM Mobile Trap**: Reverse-engineering Google's internal web RPC (`batchexecute`) on Android using an in-app `WebView` cookie harvester and desktop QR code sync is an architectural dead-end. On the PC, NotebookLM required a full headless Chromium browser (Playwright) to survive Google's anti-bot checks and CSRF token rotations (`SNlM0e`). Porting this to native Android Kotlin is fragile and unmaintainable.
2. **Reinventing Existing Codebase Primitives**: The design invents brand-new interfaces (`AssistantGateway`, `BackendModeRouter`) and duplicate remote classes (`RemotePCAssistantGateway`, `RemotePCMossTransport`), ignoring that `LiveAssistant`, `PhoneLiveAssistant`, `MossLiveTransport`, and `OkHttpMossLiveTransport` already exist in `com.visualassistant.app` with working `ServerScanner` LAN IP resolution.
3. **Destructive DSP Over-Engineering**: The proposal invents a custom `MossEchoSuppressor` that zeroes the microphone buffer during playback, degrading full-duplex voice AI into a half-duplex walkie-talkie and ignoring Android's native hardware `AcousticEchoCanceler`.
4. **Credential Ingestion Gold-Plating**: Bundling Google ML Kit Latin OCR (15–20 MB) and writing a `CameraKeyScanner` just to avoid copy-pasting an API key is pure gold-plating that directly contradicts the goal of keeping the APK lightweight.

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Reverse-Engineering Google NotebookLM on Mobile is an Architectural Dead-End - Severity: Blocker
* **Affected Scope:** Overview (Lines 17–18), Goal G4 (Lines 40–42), CUJ 3 (Lines 86–96), Section 2.4 (Lines 256–268), Section 340 Items 10–13 (Lines 400–421)
* **Discrepancy Description**: Google NotebookLM provides no public developer API. Re-implementing a bare-bones `batchexecute` client on Android by spoofing `User-Agent` in a `WebView` omits the mandatory anti-CSRF token (`SNlM0e`) and violates Google authentication policies. The app will break on cellular IP shifts and Google web UI updates.
* **Architectural Realignment**:
  - *Option A (Workstation-Assisted NotebookLM)*: Unofficial Google NotebookLM integration remains workstation-assisted via the PC backend. Standalone Mobile Mode focuses on Vision (OpenAI/StepFun) and MOSS Live duplex voice.
  - *Option B (Supported Cloud Document API)*: If standalone mobile document search is mandatory, replace reverse-engineered NotebookLM with an official, token-authenticated cloud API (e.g., Gemini File API or OpenAI Assistant API).

---

### 2. Reinventing Existing Core Interfaces and Transport Layers - Severity: Blocker
* **Affected Scope:** Section 1.1 Diagram (Lines 114–189), Section 2.1 (Lines 195–216), Section 340 Items 3, 5, 6, 8, 9, 14 (Lines 359–400, 422–428)
* **Discrepancy Description**: The design invents `AssistantGateway`, `RemotePCAssistantGateway`, `RemotePCMossTransport`, and `BackendModeRouter`. The codebase already has `LiveAssistant`, `PhoneLiveAssistant`, `MossLiveTransport`, and `OkHttpMossLiveTransport`.
* **Architectural Realignment**: Discard `AssistantGateway`, `RemotePCAssistantGateway`, `RemotePCMossTransport`, and `BackendModeRouter`. Implement `DirectCloudLiveAssistant` (implementing `LiveAssistant`) and `DirectMossLiveTransport` (implementing `MossLiveTransport`). Keep existing `PhoneLiveAssistant` and `OkHttpMossLiveTransport` for workstation mode. Mode selection is a simple factory check at initialization.

---

### 3. Loopback Wi-Fi Networking Fallacy - Severity: Blocker
* **Affected Scope:** Overview (Line 15), Architecture (Lines 178–189), Section 340 Items 6 & 9 (Lines 378–380, 397–399)
* **Discrepancy Description**: Hardcoding `127.0.0.1:8000` for Wi-Fi workstation mode connects to the phone's loopback and fails with `ECONNREFUSED`. The existing codebase already solved this with `ServerScanner.findOrResolveBackend(...)`.
* **Architectural Realignment**: Retain `ServerScanner` and `BackendEndpoint` for Workstation Mode so that USB uses `127.0.0.1` (via ADB reverse) while Wi-Fi uses the resolved LAN IP address.

---

### 4. Software Echo Zeroing Breaks Duplex Voice (Barge-In) - Severity: Major
* **Affected Scope:** Section 1.1 Diagram (Line 127), CUJ 2 (Lines 83–85), Section 2.5 (Lines 269–275)
* **Discrepancy Description**: Zeroing microphone capture buffers during playback turns full-duplex conversational voice into half-duplex walkie-talkie mode, preventing user barge-in and cutting off user speech.
* **Architectural Realignment**: Eliminate `MossEchoSuppressor`. Rely on Android's platform hardware `AcousticEchoCanceler` on the `AudioRecord` session ID with `AudioSource.VOICE_COMMUNICATION`.

---

### 5. Over-Engineered Credential Entry & Dependency Bloat (YAGNI) - Severity: Major
* **Affected Scope:** Section 2.6 (Lines 276–284), Section 340 Items 16, 17, 18 (Lines 435–452)
* **Discrepancy Description**: Adding `CameraKeyScanner` with Google ML Kit Latin OCR and a Python desktop QR script adds 15–20 MB of APK bloat for a one-time credential setup action.
* **Architectural Realignment**: Drop `CameraKeyScanner.kt`, `scripts/show_auth_qr.py`, and remove `com.google.mlkit:text-recognition` from `build.gradle.kts`. Use standard masked input fields with clipboard paste buttons.

---

### 6. Dual-Cloud Provider Ambiguity & Premature Multi-Model Abstraction - Severity: Major
* **Affected Scope:** Goal G3 (Line 39), CUJ 1 (Line 73), Section 2.2 (Lines 218–225), Section 340 Item 5 (Lines 372–375)
* **Discrepancy Description**: The document mandates supporting both OpenAI Vision (`gpt-6.1-terra`) and StepFun (`step-5-preview`), but provides zero routing logic or fallback rules. Furthermore, `gpt-6.1-terra` is not a public model identifier.
* **Architectural Realignment**: Add an explicit Provider Selector in Settings ("OpenAI" vs "StepFun"), use verified public model identifiers (`gpt-4o` / `gpt-4o-mini`), and dispatch accordingly.

---

### 7. Implementation Weeds in Architecture Specification - Severity: Nit
* **Affected Scope:** Section 2.2 (Lines 229–235), Section 2.3 (Lines 242–245, 252–253), Section 468 (Lines 471–472)
* **Discrepancy Description**: High-level sections contain low-level Canvas bitmap drawing snippets, raw JSON payloads, and Gradle CLI execution strings.
* **Architectural Realignment**: Keep high-level sections focused on component responsibilities, lifecycle transitions, and interface contracts.
