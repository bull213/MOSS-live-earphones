# Stage 7: Critic Review Report

**Target:** `docs/designs/backend_migration_to_android.md` (Lines 1–339)  
**Standard:** `resources/document_standards/feature-spec-standard.md`  
**Reviewers:** `egm-design` (Hostile Design Critic), `egm-structural` (Structural Layout Validator), `egm-style` (Technical Copy Editor)

---

## Reviewer Status

| Reviewer | Status |
| :--- | :--- |
| `egm-design` | completed |
| `egm-structural` | completed |
| `egm-style` | completed |

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Infeasible & Fragile NotebookLM Mobile Client (WebView Google Auth & `batchexecute` RPCs) - Severity: Blocker
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 58–60 (Question 1), Lines 86–96 (CUJ 3), Lines 256–268 (Section 2.4)
* **Discrepancy Description**: The design relies on loading `https://accounts.google.com` in an embedded `WebView`, spoofing `User-Agent`, extracting session cookies (`SIDTS`, `__Secure-1PSID`), and calling internal `batchexecute` web RPCs directly from Kotlin.
  1. **Google Policy Block**: Google actively blocks Google Account login inside WebViews via modern anti-bot heuristics (`window.navigator.userAgentData`, WebGL/canvas rendering, and TLS Client Hello fingerprints). Users will encounter the unrecoverable *"Couldn't sign you in. This browser or app may not be secure"* error.
  2. **Protocol Infeasibility**: `batchexecute` requires an anti-CSRF token (`at`/`SNlM0e`) and build labels embedded in the page HTML, not accessible via `CookieManager`. Direct calls will fail with HTTP 400 Bad Request.
  3. **High Maintenance Fragility**: Google's internal web RPC schemas and method IDs are private and change without notice. Re-implementing a reverse-engineered client in Kotlin without Playwright browser recovery creates an unmaintainable failure mode on mobile networks.
  4. **Security Risk**: Terminal QR code sync (`Show Auth QR.cmd`) exposes raw session cookies granting full Google account access in cleartext.
* **Rationale & Recommendation**: Re-evaluate the mobile document retrieval architecture. If Google NotebookLM is retained, document retrieval should route through the authenticated workstation backend (or a dedicated server proxy with headless browser recovery). If 100% standalone mobile operation is required, evaluate official cloud APIs (e.g., OpenAI Assistant/File Search or Gemini API with cached file context).

---

### 2. Muted Uplink Echo Suppression Destroys Full-Duplex Conversational Voice (Barge-In) - Severity: Blocker
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 83–85 (CUJ 2), Lines 269–275 (Section 2.5)
* **Discrepancy Description**: `MossEchoSuppressor` is specified as zeroing uplink PCM audio during speaker playback with a 200ms decay grace period. Zeroing capture buffers enforces half-duplex walkie-talkie operation: the user cannot interrupt (barge in on) MOSS Live while it speaks, and the 200ms decay guard cuts off the first syllables of subsequent user speech.
* **Rationale & Recommendation**: Remove zero-buffer silencing. Implement true Acoustic Echo Cancellation using Android's platform `AcousticEchoCanceler` attached to the `AudioRecord` session ID with `AudioSource.VOICE_COMMUNICATION`. For advanced echo control, integrate WebRTC AEC3 DSP to subtract speaker reference audio while keeping near-end user speech intact for barge-in detection.

---

### 3. Heading Hierarchy Violations in Document Structure - Severity: Blocker
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 9–55 (`## Overview`, `## The Problem`, `## Goals`, `## Non-Goals`)
* **Discrepancy Description**: `The Problem` (line 21), `Goals` (line 35), and `Non-Goals` (line 48) are formatted as top-level `##` (H2) headers rather than `###` (H3) subheadings nested under `## Overview`. This breaks the document tree hierarchy and violates the Feature Specification Standard template.
* **Rationale & Recommendation**: Re-nest `### The Problem`, `### Goals`, and `### Non-Goals` under `## Overview`.

---

### 4. Ideation Residue in Section Heading ("Option C") - Severity: Blocker
* **Reviewer Lens:** `egm-structural`, `egm-style`
* **Affected Scope:** Line 256 (`#### 2.4 Option C NotebookLM Mobile Client...`)
* **Discrepancy Description**: Section 2.4 leaks conversational ideation labeling ("Option C") into the normative architecture document, while sibling sections have no option designations.
* **Rationale & Recommendation**: Rename symmetrically to `#### 2.4 Mobile NotebookLM Client (DirectMobileNotebookLMClient.kt)`.

---

### 5. Unmitigated Android Background Execution & CameraX Restrictions - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 38–39 (Goal G2), Lines 76–85 (CUJ 2), Lines 238–250 (Section 2.3)
* **Discrepancy Description**: Android 12+ strictly prohibits background camera capture. Turning off the screen, putting the phone in a pocket, or switching apps immediately halts `CameraX` / `Camera2` sessions, causing unhandled exceptions if frame capture coroutines continue.
* **Rationale & Recommendation**: Explicitly define the camera lifecycle state machine. When the screen turns off or the app pauses, degrade gracefully to audio-only duplex streaming (`video.state: false`), and bind the session to an Android 14+ compatible Foreground Service with `camera|microphone` types.

---

### 6. Excessive Cellular Bandwidth Consumption - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 38–39, 238–255, 328–333
* **Discrepancy Description**: Uncompressed 16 kHz uplink PCM (32 KB/s) + 48 kHz downlink PCM (96 KB/s) + 1500ms JPEG heartbeat (~53 KB/s) totals >650 MB/hour over cellular data, causing rapid data exhaustion, thermal buildup, and battery drain.
* **Rationale & Recommendation**: Add an Economics & Data Footprint section. Investigate compressed audio support (Opus) on `api.mosi.cn`, and implement an adaptive camera frame rate controller (5000ms heartbeat on static scenes, bursting to 1000–1500ms on motion or visual intent).

---

### 7. Audio Routing Contention Between MOSS and Moonshine STT - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 41–43 (Goals G5, G6), Lines 86–96 (CUJ 3), Lines 269–275 (Section 2.5)
* **Discrepancy Description**: If `PhoneAudioCaptureRouter` routes solely to MOSS during live calls, local Moonshine STT is unpowered and cannot detect spoken switching commands (`"Use notebook Neuroscience"`). Conversely, running Moonshine ONNX streaming concurrently with MOSS WebSocket audio and CameraX JPEG encoding will heavily saturate CPU cores.
* **Rationale & Recommendation**: Clarify that hands-free switching during active MOSS Live is driven by inspecting MOSS server-side transcripts, and that local Moonshine ONNX STT is suspended during MOSS Live to preserve CPU and battery.

---

### 8. Missing Client-Side Spend Ceilings & Rate-Limiting for BYOK Keys - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 43–44, 218–225, 276–284
* **Discrepancy Description**: The PC backend enforced strict request budgets (`MAX_IMAGE_BYTES`, `agent_max_rounds`, `agent_max_estimated_usd`), but the proposed mobile client has no client-side rate limits, retry ceilings, or spending safeguards. Network retries or rapid button presses can exhaust user API credits.
* **Rationale & Recommendation**: Implement client-side turn rate limiting (e.g., max 10 requests/min), retry ceilings, and UI debounce guards.

---

### 9. Wi-Fi / Cellular Handover Socket Black Holes - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 251–255, 334–336
* **Discrepancy Description**: When transitioning between Wi-Fi and 5G, TCP sockets enter silent black holes. Relying on a 15-second ping interval leaves the audio stream frozen for 15–30+ seconds before reconnecting.
* **Rationale & Recommendation**: Register an Android `ConnectivityManager.NetworkCallback` to detect interface handovers proactively, immediately terminate stale sockets, and initiate fast reconnection.

---

### 10. Missing Success Metrics, Formal Risk Matrix, and Rollback Strategy - Severity: Major
* **Reviewer Lens:** `egm-design`, `egm-structural`
* **Affected Scope:** Whole feature specification scope (Lines 1–339)
* **Discrepancy Description**: The specification omits quantitative SLIs/SLOs (cold start time, vision latency p95, reconnect latency, battery drain rate), a formal risk matrix (account suspension, API changes, carrier blocking), and an automated fallback/rollback workflow.
* **Rationale & Recommendation**: Add dedicated `## Success Metrics`, `## Risk Matrix & Mitigations`, and `## Fallback Strategy` sections.

---

### 11. Technical Plan & Alternatives Hierarchy Deviation - Severity: Major
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 107–284, Lines 287–318
* **Discrepancy Description**: Under `## Technical Plan`, mandatory standard headings `### Component Architecture` and `### Data Models & APIs` are missing or buried. Under `## Alternatives Considered`, rejected options are promoted to full H3 headers rather than structured bullet points.
* **Rationale & Recommendation**: Structure `## Technical Plan` with explicit `### Component Architecture` and `### Data Models & APIs` subheadings, and reformat alternatives as bullet points under `## Alternatives Considered`.

---

### 12. Absolute Claims, Fluff, and Conversational Rejection Wrappers - Severity: Major
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 37, 41, 42, 111, 196, 293–317
* **Discrepancy Description**: Uncalibrated absolute statements ("100% PC Independence", "Zero Offline Voice Degradation", "Seamless") and raw conversational wrappers (`Rejected by author:`, appeals to user authority) reduce technical rigor.
* **Rationale & Recommendation**: Rephrase with objective, measurable technical statements ("Standalone PC-Independent Operation", "Offline Voice Processing", "Dynamic runtime mode switching"). Reformat rejection rationales as objective technical trade-offs.

---

### 13. Nits (Layout, Phrasing, Formatting) - Severity: Nit
* **Visual Color Banding in `RGB_565`**: Use `Bitmap.Config.ARGB_8888` on Galaxy S24+ instead of `RGB_565` to avoid color banding on appliance dials and text.
* **Divider Syntax Asymmetry**: Replace 80-character dashes with standard `---` Markdown dividers.
* **Heading Label Uniformity**: Normalize subsection titles to avoid mixing filename parentheses with bare titles.
* **Metadata Ordering**: Re-order header metadata to canonical `Status`, `Author`, `Date`.
* **Wordiness**: Strip redundant phrasing ("handles all local processing on-device", "handling must be handled by").
