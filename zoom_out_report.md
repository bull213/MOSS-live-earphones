# Goldfish Review Report: Stage 5 Initial Zoom Out

**Target:** `docs/designs/private_remote_cellular_connectivity.md` (`lines 1..304`)  
**Target Deliverable:** `zoom_out_report.md`

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |
| `egm-inventive-catalyst` | completed |

## Severity Breakdown
- 🔴 **Blocker:** 3
- 🟡 **Major:** 5
- 🟢 **Nit:** 3
- **Total Findings:** 11

---

## Prioritized Findings (Blockers -> Major -> Nits)

### 1. Ephemeral Quick Tunnels vs Persistent Named Tunnels Destroys Usability on Reboot - Severity: Blocker
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 61–65, 198–204, 286–291 (Open Question 1 & Windows Host Automation)
* **Discrepancy Description:** The RFC leaves the choice between Quick Tunnels and Named Tunnels as an open question (Question 1), while simultaneously specifying `cloudflared service install` and automated background launch. In Cloudflare's architecture, an ad-hoc quick tunnel (`cloudflared tunnel --url ...`) generates a dynamic, random subdomain on `*.trycloudflare.com` on every process start and reboot. Conversely, a persistent hostname requires an authenticated Cloudflare Named Tunnel associated with an account and domain/subdomain.
* **Rationale & Recommendation:** If the PC reboots or the service restarts, a quick tunnel generates a new random URL. Because the target user is an older adult outside the home, requiring them or a caregiver to reconfigure the mobile app with a new URL on every host reboot breaks system autonomy. The RFC must decisively commit to an authenticated Cloudflare Zero Trust Free Named Tunnel (free for up to 50 users) with a stable, deterministic hostname (e.g. `*.cfargotunnel.com` or user domain), and document the one-time authentication (`cloudflared tunnel login`) in the PowerShell setup script.

---

### 2. Windows Service Boot Lifecycle Incompatibility (SYSTEM Service vs User-Session FastAPI/Playwright) - Severity: Blocker
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 43–44, 118–120, 196–197, 286–291 (Goal G5 & Phase 3)
* **Discrepancy Description:** Goal G5 mandates launching the tunnel daemon alongside the FastAPI backend on system boot without active user desktop logins. The implementation plan details installing `cloudflared` as a Windows SYSTEM service (`cloudflared service install`), but provides no background service registration for the Python FastAPI backend or its Google NotebookLM Playwright dependency.
* **Rationale & Recommendation:** If the PC reboots, `cloudflared` starts as a background service, but `uvicorn` and FastAPI remain unstarted until the user logs into their desktop and runs `Restart Backend.cmd`. During this window, `cloudflared` receives remote traffic on port 443, attempts to proxy to `127.0.0.1:8000`, and returns `HTTP 502 Bad Gateway`. Furthermore, NotebookLM requires access to the user profile's Playwright persistent browser profile (`storage_state.json`), which fails under `NT AUTHORITY\SYSTEM`. The RFC must define the service wrapper or Windows Task Scheduler task (configured to run whether the user is logged on or not, under the user account) to manage FastAPI alongside `cloudflared`.

---

### 3. Subnet Scanning Priority Inversion Inverting Home/Away Fallback - Severity: Blocker
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 80–84, 168–172, 174–180, 248–249 (CUJ 3 & Phase 1)
* **Discrepancy Description:** CUJ 3 specifies seamless home/away fallback: probing USB loopback and local Wi-Fi first, and only falling back to the remote tunnel when disconnected. However, in `ServerScanner.kt`, Tier 2 evaluates `currentHost` (the configured remote tunnel domain) immediately after USB loopback (Tier 1) and *before* local subnet discovery (Tier 4). 
* **Rationale & Recommendation:** Because a Cloudflare tunnel URL is globally routable over the internet, probing `https://<tunnel-host>/health` will succeed even when the phone is connected to home Wi-Fi. Under the proposed logic, Tier 2 will resolve the remote tunnel on home Wi-Fi, routing uncompressed audio and camera frames across the public internet to Cloudflare's WAN edge instead of using low-latency local LAN. The RFC must update `ServerScanner.kt` to reorder discovery or inspect the active network transport so local LAN is prioritized when on home Wi-Fi.

---

### 4. Manual 64-Hex-Character Bearer Token & URL Entry Lacks Pairing Automation - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Lines 11–13, 44–45, 211–213, 301–302 (Overview, G6, and Verification)
* **Discrepancy Description:** The design requires manually copying and typing a 256-bit (64 hex character) `API_BEARER_TOKEN` and remote tunnel URL into the Android app's Settings screen.
* **Rationale & Recommendation:** Typing a 64-character cryptographic token and HTTPS URL on a mobile virtual keyboard is severely friction-heavy and error-prone, directly conflicting with the older adult user profile. Since `setup_remote_tunnel.ps1` runs on the host PC while the phone is often plugged into USB during initial setup, the script should offer:
  1. Automated USB provisioning via `adb shell am broadcast` or writing directly to app SharedPreferences.
  2. Terminal ASCII QR code generation (or opening a local pairing webpage) so the Android CameraX scanner can pair the app in one tap.

---

### 5. Cellular Bandwidth Exhaustion & Thermal Drain from Uncompressed Streaming - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Lines 27–28, 36–38, 158–161, 220–222 (The Problem & Protocol Spec)
* **Discrepancy Description:** The protocol streams uncompressed 16 kHz mono PCM uplink (256 kbps), 48 kHz mono PCM downlink (768 kbps), and uncompressed 1500ms JPEG camera frames over cellular, consuming ~580 MiB/hour (~1.74 GB in a 3-hour outing).
* **Rationale & Recommendation:** On typical older-adult cellular mobile data tiers (e.g. 5GB–10GB/month), a few outdoor walks will exhaust monthly data limits. Furthermore, transmitting continuous PCM over cellular keeps the phone modem in high-power Radio Resource Control (RRC) connected state, causing battery drain and thermal throttling. The RFC should incorporate:
  1. Device-side WebRTC VAD gating on the uplink (suppressing audio transmission during silence).
  2. Motion or turn-adaptive frame rate for camera streaming (pausing JPEG streaming when the scene is static and no visual query is active).

---

### 6. Unmitigated Windows OS Sleep and Modern Standby Connection Severance - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 11–13, 117–120, 233–236 (Architecture & Alternative D)
* **Discrepancy Description:** The design relies on an "always-on Windows PC" to anchor backend intelligence. However, standard Windows 10/11 desktop and laptop installations default to Sleep or Modern Standby after 15–30 minutes of user inactivity.
* **Rationale & Recommendation:** When Windows enters standby, the network adapter halts, FastAPI stops processing, and the Cloudflare tunnel severs. An older adult outside who initiates a voice session will encounter silent connection timeouts. The deployment script (`setup_remote_tunnel.ps1`) must audit or configure Windows AC power policy (`powercfg /change standby-timeout-ac 0`), and the mobile UI must display clear diagnostic messaging when the host computer is unreachable or asleep.

---

### 7. Network-Agnostic Probing Lacks Android Transport Awareness - Severity: Major
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Lines 174–180, 249–250, 269–273 (Component 2 & Phase 1)
* **Discrepancy Description:** `ServerScanner.kt` treats network interfaces agnostically, performing sequential socket timeouts and HTTP health checks regardless of whether the phone is connected via cellular data, Wi-Fi, or USB.
* **Rationale & Recommendation:** Android's `ConnectivityManager` natively reports active transports (`TRANSPORT_CELLULAR`, `TRANSPORT_WIFI`). When on `TRANSPORT_CELLULAR`, attempting local subnet scanning (`192.168.x.x`) or USB loopback checks wastes radio energy and introduces latency. The RFC should specify transport-aware routing: when `TRANSPORT_CELLULAR` is active, route immediately to the remote tunnel; when `TRANSPORT_WIFI` is active, probe the local LAN first and fallback to remote.

---

### 8. Query Parameter Bearer Token Fallback Exposes Secrets in Edge Logs - Severity: Major
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 156–157, 251–252 (WebSocket Protocol Spec & File Manifest)
* **Discrepancy Description:** The RFC specifies passing `API_BEARER_TOKEN` via initial WebSocket upgrade header `Authorization: Bearer <token>` or connection query parameter `?token=<token>`.
* **Rationale & Recommendation:** Passing tokens in query strings causes secrets to be logged in cleartext across Cloudflare edge logs, intermediate proxies, and server access logs. While browser WebSocket APIs historically required query params due to header limitations, native Android OkHttp (`OkHttpMossLiveTransport.kt`) fully supports custom headers during the HTTP upgrade request (`Request.Builder().header("Authorization", "Bearer ...")`). Query string authentication should be eliminated or restricted to local development debugging.

---

### 9. Premature 'Approved' Status on RFC with Unresolved Architectural Questions - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 3–5, 60–65 (Header Metadata & Open Questions)
* **Discrepancy Description:** Line 4 lists `Status: Approved`, while Section 6 explicitly lists critical unresolved questions (domain configuration vs named tunnels, cellular backoff tuning).
* **Rationale & Recommendation:** An architecture document with open architectural forks should maintain `Status: In Review` or `Draft` until all structural questions are resolved and evaluated.

---

### 10. Fragile Hardcoded Path Assumption for Cloudflared Binary - Severity: Nit
* **Reviewer Lens:** `egm-inventive-catalyst`
* **Affected Scope:** Line 288 (Phase 3 Specification)
* **Discrepancy Description:** Phase 3 states that the script will check for `cloudflared.exe` at `C:\cloudflared\cloudflared.exe` or system `PATH`.
* **Rationale & Recommendation:** Winget installs binaries to `%LOCALAPPDATA%\Microsoft\WinGet\Packages` or `Program Files`. Checking an arbitrary root directory `C:\cloudflared\` is non-standard on Windows. The script should rely strictly on `Get-Command cloudflared.exe -ErrorAction SilentlyContinue` and append the resolved installation folder to the process PATH.

---

### 11. Missing Connection Read Timeout Tuning for NotebookLM Long-Polls over Cellular - Severity: Nit
* **Reviewer Lens:** `egm-zoom-out`
* **Affected Scope:** Lines 40–41, 152–153 (Goals & Network Protocol)
* **Discrepancy Description:** NotebookLM queries have up to 45.0s long-poll latency. Cellular mobile data connections experiencing radio cell handovers often reset idle TCP sockets prematurely.
* **Rationale & Recommendation:** Ensure `LiveAssistant.kt` and OkHttp clients configure a dedicated read timeout of at least 60 seconds specifically for `/v1/notebooklm/*` endpoints, coupled with transparent single-attempt retry on transient cellular socket resets.

---

## Conclusion & Next Steps
The core premise of utilizing an outbound Cloudflare tunnel to eliminate port forwarding and VPN apps is sound and aligns well with the project invariants. However, resolving **Blocker 1 (Named Tunnel stability)**, **Blocker 2 (FastAPI boot service lifecycle)**, and **Blocker 3 (Scanner priority inversion)** is required before proceeding to implementation.
