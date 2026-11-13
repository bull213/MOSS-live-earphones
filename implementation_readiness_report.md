# Document Review Report: Implementation Readiness & Detailed Plan Audit

**Target:** [docs/designs/private_remote_cellular_connectivity.md](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L240-L304) (Lines 240–304)  
**Stage:** 9 (Implementation Readiness)  
**Deliverable:** `implementation_readiness_report.md`  

## Reviewer Status

| Reviewer | Status |
| :--- | :--- |
| `egm-implementation-readiness` | completed |
| `egm-codebase-details` | completed |
| `egm-style` | completed |

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Phantom Protocol Action `session.attach` & Invalid State `ACTIVE_CALL` in Reconnect Handshake - Severity: 🔴 Blocker
* **Reviewer Lens:** `egm-codebase-details` (cross-cutting `egm-implementation-readiness`)
* **Affected Scope:** [private_remote_cellular_connectivity.md#L280-L284](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L280-L284) (Phase 2, Step 1) & [private_remote_cellular_connectivity.md#L252](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L252) (Manifest Row 5)
* **Discrepancy Description**: Phase 2 dictates:
  > *"In onTransportClosed(code, reason): If code is non-normal (1006 or network I/O error) and the call was in ACTIVE_CALL or LISTENING state, launch a coroutine to attempt silent reconnection before calling fail(). On successful reconnect, re-send session.attach to resume duplex streaming."*
  
  This introduces three fatal architectural mismatches:
  1. **Fabricated Protocol Action**: `session.attach` does not exist in [MossLiveProtocol.kt](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveProtocol.kt) or [backend/app/moss_live.py](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/app/moss_live.py). In `backend/app/moss_live.py` (lines 60–70), client actions are validated against `ALLOWED_CLIENT_ACTIONS` (`{"mic_state", "media.mode", "video.state", "video.stream_focus", "session.instructions.append", "playback.chunk.completed", "playback.completed", "playback.stopped", "response.cancel", "ping"}`). If Android transmits `{"action": "session.attach"}`, the backend rejects it with `invalid_event` and closes the socket immediately (`backend/app/moss_live.py#L268-L270`). Duplex negotiation is triggered server-side by emitting `session.updated`, to which the client responds with `media.mode` + `mic_state(true)`.
  2. **Non-Existent Enum State**: `MossConnectionState` in [MossLiveSessionController.kt](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveSessionController.kt#L16) has no `ACTIVE_CALL` value (the states are `IDLE`, `CONNECTING`, `CONFIGURING`, `LISTENING`, `SPEAKING`, `STOPPING`, `ERROR`). Active duplex operation spans `LISTENING` and `SPEAKING`.
  3. **Listener Signature Mismatch**: `MossLiveTransportListener` in [MossLiveTransport.kt](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveTransport.kt#L18-L24) defines `fun onClosed()` with zero parameters, not `onTransportClosed(code, reason)`.
* **Rationale & Recommendation**: Any reconnection sequence following this design will fail or crash at runtime. Revise Phase 2 to:
  1. Specify updating `MossLiveTransportListener.onClosed` to accept `(code: Int, reason: String)`.
  2. Target active states `LISTENING` and `SPEAKING`.
  3. Execute the established session initialization sequence (`media.mode`, `mic_state(true)`, and any pending instructions chunk) upon reconnect rather than sending `session.attach`.

---

### 2. Phantom Removal Instruction for `val fullHost` in `ServerScanner.kt` - Severity: 🔴 Blocker
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** [private_remote_cellular_connectivity.md#L273](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L273) (Phase 1, Step 2)
* **Discrepancy Description**: Step 2 commands:
  > *"Patch ServerScanner.kt: ... Remove val fullHost = if (resolvedHost.contains(":")) resolvedHost else "$resolvedHost:8000""*
  
  In the actual codebase, [ServerScanner.kt](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/network/ServerScanner.kt) contains no variable or statement matching `fullHost`. This code exists in [MossLiveTransport.kt#L54-L55](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveTransport.kt#L54-L55) and [LiveAssistant.kt#L96](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/chat/LiveAssistant.kt#L96).
* **Rationale & Recommendation**: Implementers following Step 2 will fail to locate this code in `ServerScanner.kt`. Move this instruction to Phase 1, Step 3 ("Clean Up `LiveAssistant.kt` & `MossLiveTransport.kt`") and cite the exact file paths and line numbers.

---

### 3. Undefined Audio Capture Lifecycle & Buffer Overflow Risk During Roaming Drop - Severity: 🔴 Blocker
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** [private_remote_cellular_connectivity.md#L280-L284](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L280-L284) (Phase 2, Step 1)
* **Discrepancy Description**: During cell tower handovers, the WebSocket connection drops for 500ms to 5000ms across the retry intervals (500ms, 1500ms, 3000ms). The plan does not specify the state of the audio capture pipeline (`RealtimeAudioSource`). If the hardware microphone continues streaming PCM chunks while the transport is dead, the queue exceeds `MAX_AUDIO_BACKLOG_BYTES` (32KB in [MossLiveSessionController.kt#L347](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveSessionController.kt#L347)), causing an immediate fatal call to `fail()`. Furthermore, the plan does not define what happens to `MossAudioPlayer` downlink audio playback or whether a `RECONNECTING` connection state is displayed in the UI.
* **Rationale & Recommendation**: Explicitly define the controller's audio state during reconnection:
  1. Temporarily pause audio capture or discard uplink frames instead of pushing them into `transport.sendBinary()`.
  2. Pause/drain active downlink response playback.
  3. Define the UI transition behavior and how the retry counter resets upon receiving `session.updated`.

---

### 4. Non-Existent OkHttpClient Dependency in `ServerScanner.kt` - Severity: 🟡 Major
* **Reviewer Lens:** `egm-codebase-details`
* **Affected Scope:** [private_remote_cellular_connectivity.md#L249](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L249) (Manifest Row 2) & [private_remote_cellular_connectivity.md#L271-L272](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L271-L272) (Phase 1, Step 2)
* **Discrepancy Description**: Step 2 requires: *"Probe `https://<host>/health` via OkHttp client configured with a 5000ms connection timeout and read timeout."* [ServerScanner.kt](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/network/ServerScanner.kt) exclusively uses standard `java.net.HttpURLConnection` and `java.net.Socket`. It does not import or own an `OkHttpClient` instance. Allocating a new `OkHttpClient` inside `ServerScanner.verifyHost` creates fresh connection pools and background threads on every subnet probe.
* **Rationale & Recommendation**: Either update `isHealthOkUrl()` in `ServerScanner.kt` to handle HTTPS via standard `HttpURLConnection` with the specified 5000ms timeout, or explicitly define an injected/shared `OkHttpClient` singleton.

---

### 5. Automation Gap in Dynamic Quick Tunnel URL Extraction vs Named Tunnels - Severity: 🟡 Major
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** [private_remote_cellular_connectivity.md#L254](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L254) (Manifest Row 7) & [private_remote_cellular_connectivity.md#L286-L291](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L286-L291) (Phase 3, Step 1)
* **Discrepancy Description**: Step 1 claims `setup_remote_tunnel.ps1` will *"Provide options for both persistent named tunnels and quick tunnels running as a persistent background daemon"* and *"Output the exact connection URL and Bearer token formatted for immediate entry into the mobile app settings."*
  However:
  - Quick tunnels (`cloudflared tunnel --url http://127.0.0.1:8000`) cannot be installed as Windows Services via `cloudflared service install` without a Cloudflare account token.
  - Quick tunnels assign a random ephemeral hostname (`https://<random>.trycloudflare.com`) on every start, requiring async stdout/stderr scraping to discover the active URL.
  - If the background daemon restarts, the URL mutates, breaking Android client connectivity unless a Named Tunnel is configured.
* **Rationale & Recommendation**: Disambiguate Quick Tunnels (development mode: process execution with regex extraction from log files) from Named Tunnels (production mode: requiring Cloudflare account credentials/tunnel token and static hostname). Detail how the script extracts and presents the dynamic URL.

---

### 6. Missing Fallback Trigger Specification for WebSocket `?token=` Query Param - Severity: 🟡 Major
* **Reviewer Lens:** `egm-implementation-readiness` / `egm-codebase-details`
* **Affected Scope:** [private_remote_cellular_connectivity.md#L251](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L251) (Manifest Row 4) & [private_remote_cellular_connectivity.md#L274-L275](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L274-L275) (Phase 1, Step 3)
* **Discrepancy Description**: Manifest Row 4 requires: *"Ensure Authorization: Bearer <token> header is added to the OkHttp WebSocket connection request, with ?token=<token> query parameter fallback."*
  However, Phase 1 Step 3 contains no instruction on how the fallback is triggered. [MossLiveTransport.kt#L76-L80](file:///c:/Users/liorc/PycharmProjects/PythonProject1/android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveTransport.kt#L76-L80) attaches the header, but never decorates the URL with `?token=`. If an edge proxy strips the `Authorization` header, the connection fails with code 4401.
* **Rationale & Recommendation**: Explicitly state whether `?token=<token>` should always be appended to the WebSocket URL when `bearerToken` is present (which [backend/app/moss_live.py#L515-L517](file:///c:/Users/liorc/PycharmProjects/PythonProject1/backend/app/moss_live.py#L515-L517) supports natively), or if it is an automatic retry fallback upon receiving 401/4401.

---

### 7. Missing Automated Unit Tests for Controller Reconnect State Machine - Severity: 🟢 Nit
* **Reviewer Lens:** `egm-implementation-readiness`
* **Affected Scope:** [private_remote_cellular_connectivity.md#L253](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L253) (Manifest Row 6) & [private_remote_cellular_connectivity.md#L276-L277](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L276-L277) (Phase 1, Step 4)
* **Discrepancy Description**: The verification step only runs `BackendEndpointTest`. The new reconnection logic in `MossLiveSessionController.kt` (exponential backoff timing, max retry counters, coroutine lifecycle, buffer preservation) has no unit tests scheduled.
* **Rationale & Recommendation**: Add `MossLiveSessionControllerTest` to the touched manifest and Phase 1 Step 4 test execution command to verify reconnect behavior deterministically without requiring physical cellular handover tests.

---

### 8. Static Method Placement Ambiguity (`normalizeHost` & `isRemoteHost`) - Severity: 🟢 Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** [private_remote_cellular_connectivity.md#L248](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L248) & [private_remote_cellular_connectivity.md#L261-L268](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/private_remote_cellular_connectivity.md#L261-L268) (Phase 1, Step 1)
* **Discrepancy Description**: Step 1 defines `normalizeHost(raw: String): String`, and Step 2 calls `BackendEndpoint.isRemoteHost(host)`. Because `BackendEndpoint` is a class requiring constructor dependencies (`getPreference`, `setPreference`), calling `BackendEndpoint.isRemoteHost(host)` requires it to be on the `companion object`.
* **Rationale & Recommendation**: Explicitly specify that `normalizeHost` and `isRemoteHost` are static companion methods on `BackendEndpoint.Companion` so callers can invoke them without constructing instance dependencies.

---

## Summary Severity Tally
* 🔴 **Blockers**: 3
* 🟡 **Major**: 3
* 🟢 **Nits**: 2

The review deliverable is ready for integration into the design document revision.
