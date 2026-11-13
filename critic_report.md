# Design Critic Report: Private Remote Cellular Connectivity for AI Visual Assistant

**Target Document:** `docs/designs/private_remote_cellular_connectivity.md`
**Scope Audited:** Lines 1–239
**Review Stage:** Stage 7 (Design Critic)
**Reviewer Lenses:** `egm-design`, `egm-structural`, `egm-style`

---

## Reviewer Status

| Reviewer Lens | Focus Area | Status |
| :--- | :--- | :--- |
| `egm-design` | Architecture, Invariants, Protocols, Security, Failure Modes | completed |
| `egm-structural` | Document Structure, Section Completeness, Traceability, Syntax | completed |
| `egm-style` | Clarity, Technical Precision, Redundancy, Terminology | completed |

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Loopback Binding vs. Local Wi-Fi Coexistence Contradiction - Severity: Blocker
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 46, 81–83, 119
* **Discrepancy Description:** 
  Goal G7 (Line 46) and the Architecture diagram (Line 119) state: *"Host Loopback Isolation: The FastAPI backend binds exclusively to 127.0.0.1:8000; no inbound ports are opened on the home internet router."* However, CUJ 3 (*"Seamless Home/Away Fallback"*, lines 81–83) and Phase 4 (Line 298) specify: *"The app probes USB loopback and local Wi-Fi first. If available, it uses the zero-latency local route; if disconnected, it routes over the remote tunnel URL without user intervention."* 
  In standard TCP socket networking, a server socket bound strictly to `127.0.0.1` will refuse/drop all incoming packets originating from external network interfaces (such as a local Wi-Fi LAN subnet `192.168.x.x` or `10.x.x.x`). Local Wi-Fi scanning (`ServerScanner.kt`) will fail 100% of the time if the server only binds to loopback.
* **Rationale & Recommendation:** 
  These two architectural invariants are mutually contradictory. If local Wi-Fi direct connection is required, FastAPI must bind to `0.0.0.0:8000` or a specific LAN adapter with Windows Firewall/router NAT isolation. Alternatively, if strict loopback isolation is paramount, the document must state that Wi-Fi mode connects through a local proxy/forwarder, or explicitly deprecate direct Wi-Fi LAN access in favor of USB ADB reverse tunneling and Cloudflare Tunnel.

---

### 2. Cloudflare Tunnel Subdomain Persistence Defect - Severity: Blocker
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 63–65, 198–203, 212
* **Discrepancy Description:** 
  Open Question 1 (Lines 63–65) leaves unresolved: *"Domain Configuration vs. Named Quick Tunnels: Should the setup script support automated registration of a free persistent Cloudflare subdomain, or can an automated tunnel daemon persist an assigned tunnel identifier across reboots?"* In Component 1 (Lines 198–203), the configuration file is shown without a tunnel UUID or credentials file:
  ```yaml
  ingress:
    - service: http://127.0.0.1:8000
  ```
  Quick tunnels (`trycloudflare.com`) assign a randomized ephemeral hostname upon every process launch and cannot be installed as persistent Windows services via `cloudflared service install`. Named tunnels require persistent credentials and Cloudflare DNS routing. If the host PC reboots while the user is away from home, a quick tunnel will restart with a completely new URL, permanently stranding the Android client with zero automated discovery mechanism.
* **Rationale & Recommendation:** 
  A design document cannot be marked `Status: Approved` while the core routing mechanism across reboots remains unresolved. Authoritatively resolve Question 1: mandate a named tunnel with a persistent tunnel ID, credentials file path, and domain in `config.yml`, or specify an automated out-of-band dynamic DNS / URL-publishing mechanism.

---

### 3. WebSocket Authentication Bypass in HTTP Middleware - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 44–45, 120, 154–157
* **Discrepancy Description:** 
  Goal G6 and the architecture diagram specify `AuthMiddleware: RequestMiddleware (Constant-Time Bearer Auth)` to enforce Bearer token access control across HTTP and WebSocket endpoints. In FastAPI / Starlette, standard HTTP middleware derived from `BaseHTTPMiddleware` only intercepts HTTP requests (`scope["type"] == "http"`). WebSocket connections (`scope["type"] == "websocket"`) completely bypass `BaseHTTPMiddleware`.
* **Rationale & Recommendation:** 
  If authentication is only implemented in `RequestMiddleware`, the full-duplex conversational audio/video WebSocket endpoint (`/v1/realtime/moss-live`) will remain completely unauthenticated over the public internet tunnel. Explicitly specify whether authentication is enforced via raw ASGI middleware that validates both `http` and `websocket` scopes, or via an explicit token validation check inside the `/v1/realtime/moss-live` WebSocket endpoint handler prior to `websocket.accept()`.

---

### 4. Unauthenticated `/health` Endpoint Public Information Leakage & Flood Vector - Severity: Major
* **Reviewer Lens:** `egm-design`
* **Affected Scope:** Lines 44, 149, 178
* **Discrepancy Description:** 
  The specification mandates that `GET /health` is unauthenticated so that `ServerScanner.kt` can probe connectivity over HTTPS port 443 without credentials. Because the Cloudflare tunnel maps directly to the FastAPI backend, `/health` is exposed directly to the public internet. If `/health` returns internal diagnostic metadata, dependency status, or server details, it leaks system intelligence to public scanners. Furthermore, lacking authentication or rate limiting, it can be abused as a denial-of-service vector against the home PC.
* **Rationale & Recommendation:** 
  Specify that `/health` returns a strictly static, opaque payload (e.g., `{"status": "ok"}`) with zero host information, and specify origin-level or Cloudflare edge rate-limiting to mitigate automated scanning floods.

---

### 5. Premature "Approved" Status with Unresolved Open Questions - Severity: Major
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 4, 61–65
* **Discrepancy Description:** 
  The metadata header sets `Status: Approved` (Line 4), yet Section "Open Questions" (Lines 61–65) contains two open architectural questions (domain configuration persistence across reboots, and mobile reconnection backoff tuning).
* **Rationale & Recommendation:** 
  In formal RFC/design review governance, an architectural specification cannot be marked "Approved" while fundamental design questions that affect service automation (Goal G5) and roaming resilience (CUJ 2) remain unanswered. Update the status to `Status: In Review` or resolve both open questions directly in the technical plan.

---

### 6. Premature Placement of "Open Questions" Section - Severity: Major
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 61–65
* **Discrepancy Description:** 
  Section "Open Questions" is placed directly after "Non-Goals" (Line 52) and precedes "Critical User Journeys" (Line 68) and "Technical Plan (Architecture)" (Line 87).
* **Rationale & Recommendation:** 
  Open questions refer to implementation nuances and architectural trade-offs that the reader cannot evaluate without first understanding the system architecture. Move the "Open Questions" section after "Alternatives Considered" and immediately preceding "Detailed Implementation Plan".

---

### 7. Invalid Bidirectional Thick Arrow Syntax in Mermaid Diagram - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Line 125
* **Discrepancy Description:** 
  Line 125 contains: `Edge <== Multiplexed Outbound Tunnel ==> TunnelService`. Mermaid flowchart syntax does not support `<== label ==>` for thick bidirectional labelled arrows; this will cause syntax parsing errors in standard Markdown/Mermaid viewers.
* **Rationale & Recommendation:** 
  Replace with valid Mermaid syntax: `Edge <-->|"Multiplexed Outbound Tunnel"| TunnelService` or `Edge == "Multiplexed Outbound Tunnel" ==> TunnelService`.

---

### 8. Missing Ingress Catch-All Rule in `config.yml` Snippet - Severity: Nit
* **Reviewer Lens:** `egm-structural`
* **Affected Scope:** Lines 200–203
* **Discrepancy Description:** 
  In Component 1, the `config.yml` ingress snippet lists only:
  ```yaml
  ingress:
    - service: http://127.0.0.1:8000
  ```
  Cloudflare's `cloudflared` configuration validator mandates that all ingress rule blocks conclude with a catch-all rule (e.g. `- service: http_status:404`). Omitting this rule causes `cloudflared` to fail configuration validation on startup.
* **Rationale & Recommendation:** 
  Update the configuration snippet to append `- service: http_status:404` as the final ingress item.

---

### 9. Tautological Phrasing in Overview - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Line 13
* **Discrepancy Description:** 
  The sentence includes the phrase: *"local Wi-Fi local-area-network proximity"*, which contains redundant words (*"local Wi-Fi local-area-network"*).
* **Rationale & Recommendation:** 
  Simplify phrasing to: *"local Wi-Fi network proximity"* or *"home Wi-Fi proximity"*.

---

### 10. Ambiguous Attribution of WebSocket Close Code `1006` - Severity: Nit
* **Reviewer Lens:** `egm-style`
* **Affected Scope:** Lines 187, 252
* **Discrepancy Description:** 
  The document references *"e.g. mobile cell tower handover, code 1006"* without clarifying that RFC 6455 status code `1006` (Abnormal Closure) is an internal client-synthesized event emitted locally by OkHttp when the TCP socket drops abruptly without a clean closing handshake, rather than a frame sent by the server.
* **Rationale & Recommendation:** 
  Clarify in the text that `1006` is locally synthesized by OkHttp upon transport drop to prevent confusion for backend developers auditing server logs.
