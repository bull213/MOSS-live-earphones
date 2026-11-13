# Private Remote Cellular Connectivity for AI Visual Assistant

*   **Author:** bull213
*   **Status:** Draft
*   **Date:** 2026-11-13

--------------------------------------------------------------------------------

## Overview

This project establishes private, zero-cost remote connectivity between the AI Visual Assistant Android application (`com.visualassistant.app`) and an always-on Windows PC development server over 4G/5G mobile cellular networks. 

By eliminating the dependency on local Wi-Fi local-area-network proximity and physical USB tethering cables without requiring auxiliary VPN apps on the phone, the system enables an older adult user to experience the visual assistant anywhere outside the home while keeping document intelligence, personal SQLite history, and server-side speech relay anchored to their personal home computer.

--------------------------------------------------------------------------------

## The Problem

The AI Visual Assistant currently connects the Android smartphone to the Python FastAPI backend (`app.main:app`) via two tightly coupled local transport pathways:
1. **USB ADB Reverse Tunneling**: Requiring a physical cable mapping `tcp:8000 -> 127.0.0.1:8000`.
2. **Local Wi-Fi Subnet Scanning (`ServerScanner.kt`)**: Probing local private IP addresses (`192.168.x.x`, `10.x.x.x`) on the same local area network or phone hotspot.

When the user leaves home or disconnects from local Wi-Fi to use cellular mobile data (4G/5G):
* **Zero Cellular Reachability**: Residential internet routers and mobile carrier networks operate under Carrier-Grade NAT (CGNAT) and stateful firewalls, blocking direct inbound traffic to the PC's private IP.
* **Strict Application Constraints**: The user cannot be burdened with downloading, installing, or managing third-party mesh VPN clients (e.g., Tailscale, WireGuard, ZeroTier) or dealing with VPN configuration profiles on their phone.
* **Hardcoded Port 8000 Defect**: The existing Android networking code unconditionally appends `:8000` to server addresses lacking explicit colons. When a remote HTTPS/WSS URL is entered, the app attempts to connect to port 8000 instead of standard HTTPS port 443, causing universal connection timeouts over cellular data.
* **Cost and Quota Bottlenecks**: Free-tier proxy tools such as Ngrok impose strict monthly transfer limits (1 GB/month), which are exhausted in less than 2 hours of full-duplex PCM audio and 1500ms JPEG camera streaming (~580 MiB/hour). Cloud hosting on platforms like AWS or Fly.io incurs recurring monthly compute fees and compromises local document sovereignty.

--------------------------------------------------------------------------------

## Goals

*   **G1: Zero Auxiliary Phone Software**: The user only installs and interacts with the AI Visual Assistant Android app. No secondary VPN clients, certificates, or background proxy tools are required on the phone.
*   **G2: Zero Financial Cost ($0.00/month)**: The remote transport operates entirely on unmetered free tiers (Cloudflare persistent outbound tunnel) without recurring cloud hosting, bandwidth fees, or subscription charges.
*   **G3: Full 6-Mode Feature Parity over Cellular**:
    *   MOSS Live full-duplex conversational voice (16 kHz mono PCM uplink, 48 kHz mono PCM downlink) over secure WebSockets (`wss://`).
    *   Continuous visual awareness via 1500ms JPEG camera frame streaming (`0x01` + JPEG byte packets).
    *   Multipart HTTP assistant photo and question turns (`POST /v1/assistant/turns`).
    *   Ephemeral photo caching and cross-turn retrieval.
    *   Google NotebookLM grounded queries with up to 45.0s long-poll latency.
    *   Bundled offline Moonshine STT speech recognition.
*   **G4: Android Remote Endpoint Normalization**: Correct host parsing in `BackendEndpoint.kt`, `ServerScanner.kt`, `LiveAssistant.kt`, and `MossLiveTransport.kt` to route remote domains and `https://` / `wss://` endpoints directly over standard port 443 without appending `:8000`.
*   **G5: Persistent Host Service Automation**: Package the outbound reverse tunnel daemon (`cloudflared`) as an always-on Windows background service that automatically launches on system boot alongside the FastAPI backend without requiring active user desktop logins.
*   **G6: Application-Layer Access Control**: Protect all remote HTTP and WebSocket endpoints with a 256-bit cryptographic Bearer token (`API_BEARER_TOKEN`) validated via constant-time comparison, while preserving unauthenticated loopback health probes (`/health`).
*   **G7: Architectural Invariant Preservation**:
    *   *Host Loopback Isolation*: The FastAPI backend binds exclusively to `127.0.0.1:8000`; no inbound ports are opened on the home internet router.
    *   *Zero Credential Leakage*: Third-party API keys (`MOSS_API_KEY`, `OPENAI_API_KEY`, `STEPFUN_API_KEY`) remain strictly sealed in the server's `.env` file and are never sent to the mobile client.
    *   *Local Privacy Tiers*: Ingested reference books, SQLite FTS5 search indexes, MiniLM vector models, and Google NotebookLM browser session state remain 100% sovereign on the local PC.

--------------------------------------------------------------------------------

## Non-Goals

*   **NG1: Router Port Forwarding / UPnP**: Opening inbound ports on home residential routers or configuring dynamic DNS (DDNS) records.
*   **NG2: Cloud Hosting Deployment**: Migrating FastAPI or backend data storage to third-party public cloud providers (e.g., AWS, GCP, Fly.io, Railway).
*   **NG3: Mobile VPN Service Implementation**: Creating an internal Android `VpnService` or embedding WireGuard tunnels inside the APK.
*   **NG4: Offline Cloud Intelligence**: Running OpenAI, StepFun, or MOSS Live speech models completely offline on the phone when cellular connectivity is severed.

--------------------------------------------------------------------------------

## Open Questions

*   **Question 1: Domain Configuration vs. Named Quick Tunnels**: Should the setup script support automated registration of a free persistent Cloudflare subdomain, or can an automated tunnel daemon persist an assigned tunnel identifier across reboots?
*   **Question 2: Cellular Reconnection Backoff Tuning**: What backoff interval best balances fast voice recovery with battery preservation when a mobile device temporarily loses reception in an elevator or basement?

--------------------------------------------------------------------------------

## Critical User Journeys (CUJs)

### CUJ 1: Outside Mobile Assistant Turn (Photo + Question)
*   **Given**: The user is outside connected exclusively to 4G/5G mobile data with Wi-Fi disabled. The Windows PC is powered on at home with the backend and tunnel services running.
*   **When**: The user captures a photo of an unfamiliar street sign and asks for an explanation.
*   **Then**: The Android app sends a `POST /v1/assistant/turns` request to the secure Cloudflare tunnel hostname on port 443 with the Bearer token header. The request routes to the local PC backend, executes the OpenAI/StepFun gateway, and returns the vocalized answer to the phone within 2.5 seconds.

### CUJ 2: Continuous MOSS Live Conversation While Roaming
*   **Given**: The user starts a hands-free conversational voice session using MOSS Live while walking outdoors over cellular data.
*   **When**: The mobile phone transitions between two cellular towers, causing a transient TCP socket reset.
*   **Then**: The Android client detects the disconnection, transitions to a reconnecting state without emitting audio pops or crashing, re-establishes the WebSocket connection to the tunnel endpoint, and resumes conversational duplex streaming.

### CUJ 3: Seamless Home/Away Fallback
*   **Given**: The user returns home, and the phone reconnects to home Wi-Fi or is plugged into USB for charging.
*   **When**: The app initiates communication with the backend.
*   **Then**: The app probes USB loopback and local Wi-Fi first. If available, it uses the zero-latency local route; if disconnected, it routes over the remote tunnel URL without user intervention.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

### 1. End-to-End System Architecture

```mermaid
flowchart TD
    subgraph MobileDevice["Android Phone (com.visualassistant.app)"]
        UI["Compose Chat UI & Settings"]
        Endpoint["BackendEndpoint\n(Smart Port & Scheme Normalizer)"]
        Scanner["ServerScanner\n(Multi-Tier Transport Probe)"]
        MOSSClient["OkHttpMossLiveTransport\n(WebSocket Duplex + Binary Audio/Video)"]
        HTTPClient["AssistantApi / NotebookAuthApi\n(Multipart HTTP + Bearer Token)"]

        UI --> Endpoint
        Endpoint --> Scanner
        Scanner --> HTTPClient
        Scanner --> MOSSClient
    end

    subgraph CellularNetwork["Public Cellular Network (4G/5G CGNAT)"]
        CellRadio["Carrier Radio Network\n(Dynamic Public IPv4/IPv6)"]
        MOSSClient -. WSS / Port 443 .-> CellRadio
        HTTPClient -. HTTPS / Port 443 .-> CellRadio
    end

    subgraph CloudflareEdge["Cloudflare Global Anycast Edge (Zero Cost)"]
        Edge["Public Edge Proxy\n(Terminates Public TLS on 443)\n(Unmetered Transit, Zero Port Forwarding)"]
        CellRadio -. TLS 1.3 .-> Edge
    end

    subgraph WindowsHost["Home Windows PC (Always-On)"]
        TunnelService["cloudflared Service\n(Persistent Outbound QUIC/HTTP2 Tunnel)\n(Runs as SYSTEM Background Service)"]
        FastAPI["FastAPI Backend (127.0.0.1:8000)\n(Bound strictly to Loopback)"]
        AuthMiddleware["RequestMiddleware\n(Constant-Time Bearer Auth)"]
        MOSSRelay["MOSS Live WebSocket Relay\n(Session Anchor & Upstream Buffer)"]
        LocalData[("Local Document Index\nSQLite FTS5 + MiniLM Embeddings")]
        NotebookLM["NotebookLM Coprocessor\n(Persistent Browser Profile)"]

        Edge <== Multiplexed Outbound Tunnel ==> TunnelService
        TunnelService -. HTTP/WS (127.0.0.1:8000) .-> FastAPI
        FastAPI --> AuthMiddleware
        FastAPI --> MOSSRelay
        FastAPI --> LocalData
        FastAPI --> NotebookLM
    end

    subgraph UpstreamCloud["External AI Providers"]
        MOSSUpstream["MOSS Realtime Gateway\n(wss://api.mosi.cn/v1/realtime)"]
        OpenAIUpstream["OpenAI Vision API\n(gpt-6.1-terra)"]
        StepFunUpstream["StepFun Gateway\n(step-5-preview)"]

        MOSSRelay -. Upstream WSS .-> MOSSUpstream
        FastAPI -. HTTPS RPC .-> OpenAIUpstream
        FastAPI -. HTTPS RPC .-> StepFunUpstream
    end
```

### 2. Network Protocol and Communication Specification

The remote communication channel operates exclusively over standard web ports (`443`) terminated by Cloudflare's edge proxy, preventing firewall rejections on cellular carriers.

1. **HTTP Endpoints (HTTPS on Port 443)**:
   * `GET /health`: Probed without credentials by `ServerScanner.isHealthOkUrl` with a 5000ms timeout to verify connectivity.
   * `POST /v1/assistant/turns`: Multipart form data containing user text, audio transcripts, rolling photos, and conversation history. Authenticated via header `Authorization: Bearer <API_BEARER_TOKEN>`.
   * `POST /v1/assistant/photos/upload`: Ephemeral image staging for multi-turn visual memory.
   * `GET/POST /v1/notebooklm/*`: Grounded book discussion and notebook status queries. Supports long-poll timeouts up to 45.0 seconds (well within Cloudflare's 100-second HTTP proxy timeout).

2. **WebSocket Realtime Stream (WSS on Port 443)**:
   * `WSS /v1/realtime/moss-live`: Bidirectional full-duplex PCM audio and camera frame relay.
   * *Authentication*: Passed via initial WebSocket upgrade header `Authorization: Bearer <token>` or connection query parameter `?token=<token>`.
   * *Binary Multiplexing*:
     * Audio Uplink: 16 kHz 16-bit mono PCM chunks (~640 bytes / 20ms).
     * Audio Downlink: 48 kHz 16-bit mono PCM chunks (~1920 bytes / 20ms).
     * Video Camera Stream: Raw JPEG byte packets prefixed with single-byte header `0x01`, transmitted every 1500ms.
   * *Keep-Alive*: Inherent active traffic every 20ms–1500ms eliminates Cloudflare's 100-second idle connection drops. In suspended mode (when switching to NotebookLM), a 15-second ping heartbeat maintains socket state.

### 3. Android Client Architecture & Defect Remediation

#### Component 1: `BackendEndpoint.kt` Normalization
* **Problem**: Host string sanitization previously assumed local IP addresses and stripped schemes improperly, or failed to handle bare hostnames.
* **Solution**: Implement intelligent endpoint normalization:
  * Detect whether the configured host is a local loopback (`127.0.0.1`), a local LAN/hotspot IP (`192.168.x.x`, `10.x.x.x`), a Tailscale IP (`100.x.x.x`), or a remote domain (`.trycloudflare.com`, `.ts.net`, or custom domain).
  * If a scheme is explicitly provided (`https://` or `http://`), preserve the scheme.
  * If the host contains an explicit port (e.g., `192.168.43.18:8000`), preserve that port.
  * If the host is a remote domain without an explicit port, use standard HTTPS/WSS (port 443) and **NEVER append `:8000`**.
  * Provide clean `httpUrl(path)` and `wsUrl(path)` builders that format correctly for both HTTP/HTTPS and WS/WSS.

#### Component 2: `ServerScanner.kt` Remote Probing
* **Problem**: `ServerScanner.verifyHost()` performs a raw TCP socket connection to port 8000 before attempting `/health`. For remote cloud tunnels listening on port 443, the raw socket probe to port 8000 fails immediately. Furthermore, the previous 1200ms timeout was too aggressive for cellular latency.
* **Solution**:
  * If `isRemoteHost(host)` is true, bypass raw TCP socket probing on port 8000.
  * Probe `https://<host>/health` directly with a 5000ms connection and read timeout.
  * Strip the conditional logic in `ServerScanner` that appended `:8000` to resolved hosts.

#### Component 3: `LiveAssistant.kt` & `MossLiveTransport.kt` Port Fixes
* **Problem**: Both classes contain duplicate logic: `val fullHost = if (resolved.contains(":")) resolved else "$resolved:8000"`.
* **Solution**: Delegate host resolution directly to `BackendEndpoint.normalizeHost(host)` so no class independently appends `:8000`.

#### Component 4: `MossLiveSessionController.kt` Cellular Reconnect Loop
* **Mechanism**:
  * On unexpected WebSocket closure (e.g. mobile cell tower handover, code `1006`), transition the session state to `RECONNECTING`.
  * Trigger an exponential backoff retry loop (attempt 1: 500ms, attempt 2: 1500ms, attempt 3: 3000ms).
  * If reconnection succeeds within the window, re-attach to the server session and resume duplex audio without tearing down the UI or resetting conversation turns.

### 4. Windows Host Automation & Service Lifecycle

#### Component 1: Cloudflare Tunnel Daemon (`cloudflared`)
* Installed silently via Windows Package Manager:
  `winget install --id Cloudflare.cloudflared --exact --silent`
* Executed as a persistent Windows Service:
  `cloudflared service install`
* Configuration file (`%USERPROFILE%\.cloudflared\config.yml`):
  Routes incoming tunnel requests to local loopback:
  ```yaml
  ingress:
    - service: http://127.0.0.1:8000
  ```

#### Component 2: Deployment Automation Script (`scripts/setup_remote_tunnel.ps1`)
A self-contained, idempotent PowerShell script that:
1. Verifies administrative permissions and elevates if needed.
2. Generates a cryptographically strong 256-bit `API_BEARER_TOKEN` and writes it to `backend/.env` if not already set.
3. Installs `cloudflared.exe` if absent.
4. Registers or starts the tunnel process.
5. Verifies local loopback reachability by querying `http://127.0.0.1:8000/health`.
6. Prints a clean, copy-pasteable configuration summary containing the public URL and Bearer token for entry into the Android app.

--------------------------------------------------------------------------------

## Alternatives Considered

### Alternative A: Ngrok Free Tier Reverse Proxy
*   **Pros**: Simple command-line invocation (`ngrok http 8000`); automatic SSL certificate issuance.
*   **Cons**: Free tier enforces a strict 1 GB/month bandwidth cap and 20,000 requests/month limit. Injects HTML interstitial warning pages on non-standard HTTP client requests unless bypassed via custom headers.
*   **Reason for Rejection**: *Rejected by author*: Full-duplex MOSS Live PCM streaming (16 kHz uplink, 48 kHz downlink) and 1500ms JPEG video frames generate ~580 MiB per hour. Ngrok's entire monthly free quota is completely depleted in approximately 1.7 hours of active use.

### Alternative B: Router Port-Forwarding with Dynamic DNS (DDNS)
*   **Pros**: Direct peer-to-peer connection without an intermediary third-party relay; unmetered bandwidth.
*   **Cons**: Exposes a private Windows development machine directly to public internet port scanners and automated botnet attacks; fails completely on ISPs utilizing Carrier-Grade NAT (CGNAT) without purchasing static public IPv4 addresses; requires manual configuration of router admin consoles; requires manual ACME / Let's Encrypt TLS certificate lifecycle and renewal on Windows.
*   **Reason for Rejection**: *Rejected by author*: Unacceptable attack surface exposure on home hardware, carrier incompatibility under CGNAT, and extreme setup friction for non-technical users.

### Alternative C: Third-Party Mesh VPN Client on Mobile (Tailscale / WireGuard)
*   **Pros**: End-to-end WireGuard cryptographic encryption; automatic NAT traversal; keeps backend completely hidden from the public internet without Bearer tokens.
*   **Cons**: Requires downloading, installing, configuring, and maintaining a secondary VPN application on the user's phone; requires managing authentication accounts, keys, and background battery optimization exemptions.
*   **Reason for Rejection**: *Rejected by author*: Explicitly violates the non-negotiable architectural constraint: "Everything must be inside my app. I don't want to download other stuff, except my app, to my phone."

### Alternative D: Cloud VPS Backend Deployment (AWS / GCP / Fly.io / Railway)
*   **Pros**: High server availability independent of home PC power or sleep states; static public IP address.
*   **Cons**: Incurs ongoing monthly infrastructure costs ($5–$25+/month) for compute, memory, and storage; requires uploading sensitive local document libraries and SQLite databases to third-party cloud disks; breaks Google NotebookLM session synchronization, which relies on persistent local Playwright browser profiles (`storage_state.json`) anchored to the local machine.
*   **Reason for Rejection**: *Rejected by author*: Violates the zero-cost ($0.00/month) requirement and compromises local document privacy invariants.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

The implementation is partitioned into four distinct phases to ensure testability, safety, and zero regression of existing local Wi-Fi and USB cable modes.

### 1. Touched File Manifest & Rationale

| File Path | Modification Type | Technical Rationale |
| :--- | :--- | :--- |
| `android/app/src/main/java/com/visualassistant/app/network/BackendEndpoint.kt` | Modify | Add intelligent host/URL normalization (`normalizeHost`, `isRemoteHost`). Strip unconditional `:8000` appending for remote domains. Ensure `httpUrl()` and `wsUrl()` correctly format `https://` and `wss://` endpoints over standard port 443. |
| `android/app/src/main/java/com/visualassistant/app/network/ServerScanner.kt` | Modify | Fix remote health check probing. When the target host is a remote domain, bypass raw TCP socket probing on port 8000 and directly query `https://<host>/health` with a 5000ms timeout. Remove local `:8000` appending. |
| `android/app/src/main/java/com/visualassistant/app/chat/LiveAssistant.kt` | Modify | Delegate host sanitization and port assignment strictly to `BackendEndpoint.normalizeHost()`, eliminating duplicate and conflicting `:8000` logic. |
| `android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveTransport.kt` | Modify | Eliminate hardcoded `:8000` appending. Ensure `Authorization: Bearer <token>` header is added to the OkHttp WebSocket connection request, with `?token=<token>` query parameter fallback. |
| `android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveSessionController.kt` | Modify | Implement exponential backoff reconnect logic (500ms, 1500ms, 3000ms) on unexpected WebSocket drops (e.g. mobile cell tower handover, code `1006`) to preserve live session continuity during outdoor mobility. |
| `android/app/src/test/java/com/visualassistant/app/network/BackendEndpointTest.kt` | Modify | Expand unit test suite to validate: (1) Remote HTTPS URLs preserve port 443 and do not get `:8000` appended, (2) Local LAN IPs (`192.168.43.18:8000`) retain their explicit ports, (3) `wsUrl()` maps `https://` to `wss://` properly, (4) Bearer token headers format correctly. |
| `scripts/setup_remote_tunnel.ps1` | Create | Self-elevating PowerShell script that: installs `cloudflared` if absent, generates and synchronizes `API_BEARER_TOKEN` in `backend/.env`, configures the persistent background tunnel service, and verifies loopback health. |
| `Restart Backend.cmd` | Modify | Ensure the remote tunnel process/service is verified and restarted alongside the FastAPI backend so local and remote transports remain synchronized. |
| `PROJECT_HANDOFF.md` | Modify | Document the remote cellular architecture, Bearer token access controls, Cloudflare tunnel service lifecycle, and mobile cellular testing procedures in the master engineering handoff. |

### 2. Execution Phases

#### Phase 1: Android Client Network Normalization & Bug Remediation
1. **Refactor `BackendEndpoint.kt`**:
   * Implement `normalizeHost(raw: String): String`:
     * If empty, return `DEFAULT_HOST` (`127.0.0.1:8000`).
     * If host contains explicit protocol (`http://`, `https://`, `ws://`, `wss://`), extract scheme and host body.
     * Detect remote hosts: checks if host contains `.trycloudflare.com`, `.ts.net`, top-level domains, or is an explicit domain name without private IP prefixes (`127.`, `192.168.`, `10.`, `172.`).
     * If remote host lacks an explicit port, do **NOT** append `:8000`. Standardize on port 443.
     * If private IP lacks an explicit port, default to `:8000`.
   * Update `httpUrl(path)` and `wsUrl(path)` to respect scheme-awareness without redundant prepending.
2. **Patch `ServerScanner.kt`**:
   * In `verifyHost(host)`:
     * Check `BackendEndpoint.isRemoteHost(host)`. If true, bypass raw socket check on port 8000 (`Socket().connect(InetSocketAddress(host, 8000))`).
     * Probe `https://<host>/health` via OkHttp client configured with a 5000ms connection timeout and read timeout.
   * Remove `val fullHost = if (resolvedHost.contains(":")) resolvedHost else "$resolvedHost:8000"`.
3. **Clean Up `LiveAssistant.kt` & `MossLiveTransport.kt`**:
   * Replace manual `:8000` checks with calls to `BackendEndpoint.normalizeHost()`.
4. **Unit Verification**:
   * Execute `./gradlew testDebugUnitTest --tests com.visualassistant.app.network.BackendEndpointTest`.

#### Phase 2: Cellular Roaming Reconnect Handling
1. **State Machine Enhancement in `MossLiveSessionController.kt`**:
   * Introduce a retry counter (`maxRetries = 3`) and backoff intervals (500ms, 1500ms, 3000ms).
   * In `onTransportClosed(code, reason)`: If code is non-normal (`1006` or network I/O error) and the call was in `ACTIVE_CALL` or `LISTENING` state, launch a coroutine to attempt silent reconnection before calling `fail()`.
   * On successful reconnect, re-send `session.attach` to resume duplex streaming.

#### Phase 3: Windows Host Service Automation
1. **Create `scripts/setup_remote_tunnel.ps1`**:
   * Check for `API_BEARER_TOKEN` in `backend/.env`. If missing, generate a 256-bit secure hex token and append it.
   * Verify whether `cloudflared.exe` is present in `C:\cloudflared\cloudflared.exe` or system `PATH`. If missing, install via `winget install --id Cloudflare.cloudflared --exact --silent`.
   * Provide options for both persistent named tunnels and quick tunnels running as a persistent background daemon.
   * Validate local backend health (`http://127.0.0.1:8000/health`) before starting the tunnel.
   * Output the exact connection URL and Bearer token formatted for immediate entry into the mobile app settings.
2. **Update `Restart Backend.cmd`**:
   * Integrate tunnel status check so developers can verify both backend and tunnel health in a single command.

#### Phase 4: End-to-End Verification
1. **Local Regression Verification**:
   * Verify USB loopback mode (`127.0.0.1:8000` via ADB reverse) continues to function without regression.
   * Verify Wi-Fi hotspot mode continues to discover local PC IP.
2. **Cellular Mobile Data Verification**:
   * Disconnect phone Wi-Fi; enable 4G/5G mobile data.
   * Configure remote URL and Bearer token in Settings.
   * Tap "Test Connection" & verify `HTTP 200 OK`.
   * Execute Photo + Question turn over cellular; verify response within 2.5s.
   * Initiate MOSS Live call over cellular; verify 16kHz audio uplink, 48kHz downlink, and 1500ms camera video frames stream continuously.
