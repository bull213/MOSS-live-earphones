# Stage 5: Initial Zoom Out Review Report

**Target:** `docs/designs/backend_migration_to_android.md` (Lines 1–490)  
**Standard:** `resources/document_standards/zoom-out-standard.md`  
**Reviewers:** `egm-zoom-out` (Skeptical Architect), `egm-inventive-catalyst` (Inventive TRIZ Analyst)

---

## Reviewer Status

| Reviewer | Status |
| :--- | :--- |
| `egm-zoom-out` | completed |
| `egm-inventive-catalyst` | completed |

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Reverse-Engineering Google NotebookLM `batchexecute` RPCs on Android - Severity: Blocker
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Section 2.4 (Lines 256–268), Section 344 Items 10–13 (Lines 400–421), Goal G4 (Lines 40–42)
* **Discrepancy Description**: Google NotebookLM provides no public developer API. In the PC backend, running NotebookLM relies on `notebooklm-py` with headless Playwright browser automation (`attempt_headless_reauth`) and persistent Chromium browser profiles to survive frequent Google session cookie invalidations and cellular IP shifts. Re-implementing a bare-bones `batchexecute` client on Android (`DirectMobileNotebookLMClient`) while spoofing Chrome Mobile user-agent in WebView creates an unrecoverable failure mode on cellular networks and violates Google authentication security policies.
* **Rationale & Recommendation**: Acknowledge that unofficial NotebookLM integration requires a proxy/desktop environment with headless browser automation, or evaluate standard official cloud APIs (such as OpenAI Assistant/File Search or Gemini API with cached file context) for standalone mobile document Q&A.

---

### 2. Reinventing Existing Core Interfaces and Transports - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Section 2.1 (Lines 195–216), Section 344 Items 3, 5, 6, 8, 9, 14 (Lines 359–399, 422–428)
* **Discrepancy Description**: The design introduces a new set of interfaces and classes (`AssistantGateway`, `DirectCloudAssistantGateway`, `RemotePCAssistantGateway`, `MossLiveTransport`, `RemotePCMossTransport`) that duplicate already existing, fully functional abstractions in the codebase:
  1. `LiveAssistant` (`com.visualassistant.app.chat.LiveAssistant`) already abstracts assistant turns, supports `notebookId`, and is wired to `ChatViewModel`.
  2. `PhoneLiveAssistant` (`com.visualassistant.app.chat.PhoneLiveAssistant`) already implements HTTP communication with `127.0.0.1:8000`. `RemotePCAssistantGateway` is redundant.
  3. `MossLiveTransport` (`com.visualassistant.app.mosslive.MossLiveTransport`) already exists with `sendCameraFrame`, `queuedBytes`, and `close(code, reason)`.
  4. `OkHttpMossLiveTransport` already connects to `ws://127.0.0.1:8000/v1/realtime/moss-live`. `RemotePCMossTransport` is redundant.
* **Rationale & Recommendation**: Reuse existing interfaces (`LiveAssistant` and `MossLiveTransport`). Implement `DirectCloudLiveAssistant` and `DirectMossLiveTransport` implementing these existing contracts, and retain `PhoneLiveAssistant` and `OkHttpMossLiveTransport` for workstation mode.

---

### 3. Over-Engineered Credential Ingestion & Flawed Key Scanner - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Section 2.6 (Lines 276–284), Section 344 Items 17, 18 (Lines 443–452)
* **Discrepancy Description**: The proposal specifies four separate credential ingestion mechanisms: manual typing, clipboard paste, a Camera Key Scanner using Google ML Kit Latin OCR, and a desktop QR terminal sync script (`scripts/show_auth_qr.py`).
  1. **Technical Defect**: Google ML Kit Latin OCR is a text recognition model; it cannot parse QR codes without a barcode scanning library.
  2. **Dependency Bloat**: Since on-device book OCR is deprecated (Non-Goal NG1), retaining ML Kit Latin OCR (15–20 MB) solely to scan printed keys off screens is unnecessary gold-plating.
* **Rationale & Recommendation**: Simplify credential entry to standard masked input fields with clipboard paste buttons. Drop `CameraKeyScanner.kt` and `scripts/show_auth_qr.py`, and remove `com.google.mlkit:text-recognition` from `build.gradle.kts`.

---

### 4. Premature Over-Abstraction for Mode Routing - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Section 1.1 Diagram & Section 2.1 (Lines 114–189, 195–216), Section 344 Item 14 (Lines 422–428)
* **Discrepancy Description**: The design specifies `BackendModeRouter` to manage dynamic hot-swapping between `Direct*` and `Remote*` gateways in memory without restarting the application. Switching between Standalone and Workstation modes is an infrequent developer setting that does not warrant dynamic runtime hot-swapping or complex synchronization plumbing.
* **Rationale & Recommendation**: Read the mode setting from `SecureCredentialStore` during application or ViewModel initialization, instantiate the corresponding implementation (`PhoneLiveAssistant` vs. `DirectCloudAssistant`), and require an app restart if the toggle changes.

---

### 5. Half-Duplex Microphone Zeroing Breaks Full-Duplex Interruption (Barge-In) - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Section 2.5 (Lines 269–275) / CUJ 2 & CUJ 3
* **Discrepancy Description**: Zeroing uplink PCM audio during speaker playback (plus 200ms reverberation decay) forces half-duplex walkie-talkie operation during loudspeaker playback, preventing the user from interrupting (barging in on) MOSS Live.
* **Rationale & Recommendation**: Rely on hardware Acoustic Echo Cancellation (`AcousticEchoCanceler` on `VOICE_COMMUNICATION` session ID) natively supported on Galaxy S24+ hardware. If software suppression is retained as a secondary safeguard, use voice-activity energy thresholds or spectral ducking rather than hard binary zeroing.

---

### 6. Fixed 1500ms Camera Heartbeat Unnecessarily Burns Mobile Bandwidth and Thermal Headroom - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Section 2.3 (Lines 246–248) & Section 3.2 (Lines 319–324)
* **Discrepancy Description**: Continuously transmitting JPEG frames every 1.5 seconds consumes cellular bandwidth and CPU/GPU thermal headroom even during static visual scenes or purely auditory conversational turns.
* **Rationale & Recommendation**: Support dynamic camera frame rate adaptation: 5000ms keepalive heartbeat during static scenes, bursting to 1000–1500ms upon scene change or visual query intent.

---

### 7. Reconnection Latency Inconsistency Following 60-Second Quiescence Timeout - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Section 2.3 (Lines 251–254) & CUJ 3 (Step 5)
* **Discrepancy Description**: Establishing a new TLS 1.3 socket, WebSocket upgrade, and two-step handshake on cellular networks requires 300–800ms, conflicting with the advertised "<100ms" voice resumption guarantee when the 60-second timeout has elapsed.
* **Rationale & Recommendation**: Trigger socket pre-warming upon completion of NotebookLM TTS readout, and play an earcon audio chime to mask network latency if a cold reconnection is needed.

---

### 8. Altitude Drops & Implementation Weeds in Architecture Document - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Section 2.2 (Lines 229–235), Section 2.3 (Lines 242–245, 252–253), Section 468 (Lines 471–472)
* **Discrepancy Description**: The document includes low-level code snippets (Canvas drawing boilerplate, raw JSON strings, build commands) inside high-level architectural sections.
* **Rationale & Recommendation**: Maintain architectural focus on component responsibilities, state transitions, and error recovery contracts, leaving line-by-line coding details to the implementation phase.
