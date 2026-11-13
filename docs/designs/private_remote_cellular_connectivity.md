# Private Remote Cellular Connectivity for AI Visual Assistant

*   **Author:** bull213
*   **Status:** Approved
*   **Date:** 2026-11-13

--------------------------------------------------------------------------------

## Overview

This project establishes private, zero-cost remote connectivity between the AI Visual Assistant Android application (`com.visualassistant.app`) and an always-on Windows PC development server over 4G/5G mobile cellular networks. 

By eliminating the dependency on home Wi-Fi proximity and physical USB tethering cables without requiring auxiliary VPN apps on the phone, the system enables an older adult user to experience the visual assistant anywhere outside the home while keeping document intelligence, personal SQLite history, and server-side speech relay anchored to their personal home computer.

--------------------------------------------------------------------------------

## The Problem

The AI Visual Assistant currently connects the Android smartphone to the Python FastAPI backend (`app.main:app`) via two tightly coupled local transport pathways:
1. **USB ADB Reverse Tunneling**: Requiring a physical cable mapping `tcp:8000 -> 127.0.0.1:8000`.
2. **Local Wi-Fi Subnet Scanning (`ServerScanner.kt`)**: Probing local private IP addresses (`192.168.x.x`, `10.x.x.x`) on the same local area network or phone hotspot.

When the user leaves home or disconnects from local Wi-Fi to use cellular mobile data (4G/5G):
* **Zero Cellular Reachability**: Residential internet routers and mobile carrier networks operate under Carrier-Grade NAT (CGNAT) and stateful firewalls, blocking direct inbound traffic to the PC's private IP.
* **Strict Application Constraints**: The user cannot be burdened with downloading, installing, or managing third-party mesh VPN clients (e.g., Tailscale, WireGuard, ZeroTier) or dealing with VPN configuration profiles on their phone.
* **Hardcoded Port 8000 Defect**: The existing Android networking code unconditionally appended `:8000` to server addresses lacking explicit colons. When a remote HTTPS/WSS URL was entered, the app attempted to connect to port 8000 instead of standard HTTPS port 443, causing universal connection timeouts over cellular data.
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
    *   Bundled offline Moonshine STT speech recognition (runs locally on device).
*   **G4: Android Remote Endpoint Normalization**: Correct host parsing in `BackendEndpoint.kt`, `ServerScanner.kt`, `LiveAssistant.kt`, `MossLiveTransport.kt`, and `SettingsInfo.kt` to route remote domains and `https://` / `wss://` endpoints directly over standard port 443 without appending `:8000`.
*   **G5: Persistent Host Tunnel Automation**: Package the outbound reverse tunnel daemon (`cloudflared`) via an automated PowerShell runner (`scripts/setup_remote_tunnel.ps1`) and one-click batch launcher (`Start Remote Tunnel.cmd`) that validates local backend health and provides automated configuration pairing.
*   **G6: Application-Layer Access Control**: Protect all remote HTTP and WebSocket endpoints with a 256-bit cryptographic Bearer token (`API_BEARER_TOKEN`) validated via constant-time comparison, while preserving unauthenticated loopback health probes (`/health`).
*   **G7: Architectural Invariant Preservation**:
    *   *Dual Local/Remote Binding*: The FastAPI backend binds to `0.0.0.0:8000` (accepting USB ADB reverse, local Wi-Fi LAN, and local Cloudflare tunnel loopback forwarding), while Windows Firewall and router NAT prevent unauthorized external access.
    *   *Zero Credential Leakage*: Third-party API keys (`MOSS_API_KEY`, `OPENAI_API_KEY`, `STEPFUN_API_KEY`) remain strictly sealed in the server's `.env` file and are never sent to the mobile client.
    *   *Local Privacy Tiers*: Ingested reference books, SQLite FTS5 search indexes, MiniLM vector models, and Google NotebookLM browser session state remain 100% sovereign on the local PC.

--------------------------------------------------------------------------------

## Non-Goals

*   **NG1: Router Port Forwarding / UPnP**: Opening inbound ports on home residential routers or configuring dynamic DNS (DDNS) records.
*   **NG2: Cloud Hosting Deployment**: Migrating FastAPI or backend data storage to third-party public cloud providers (e.g., AWS, GCP, Fly.io, Railway).
*   **NG3: Mobile VPN Service Implementation**: Creating an internal Android `VpnService` or embedding WireGuard tunnels inside the APK.
*   **NG4: Offline Cloud Intelligence**: Running OpenAI, StepFun, or MOSS Live speech models completely offline on the phone when cellular connectivity is severed.

--------------------------------------------------------------------------------

## Critical User Journeys (CUJs)

### CUJ 1: Outside Mobile Assistant Turn (Photo + Question)
*   **Given**: The user is outside connected exclusively to 4G/5G mobile data with Wi-Fi disabled. The Windows PC is powered on at home with the backend and tunnel running.
*   **When**: The user captures a photo of an unfamiliar street sign and asks for an explanation.
*   **Then**: The Android app sends a `POST /v1/assistant/turns` request to the secure Cloudflare tunnel hostname on port 443 with the Bearer token header. The request routes to the local PC backend, executes the OpenAI/StepFun gateway, and returns the vocalized answer to the phone within 2.5 seconds.

### CUJ 2: Continuous MOSS Live Conversation While Roaming
*   **Given**: The user starts a hands-free conversational voice session using MOSS Live while walking outdoors over cellular data.
*   **When**: The mobile phone transitions between two cellular towers, causing a transient TCP socket drop (synthesized locally by OkHttp as code `1006`).
*   **Then**: The Android client detects the disconnection, transitions cleanly, re-establishes the WebSocket connection to the tunnel endpoint, and resumes conversational duplex streaming.

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
        HTTPClient["PhoneLiveAssistant / NotebookAuthApi\n(Multipart HTTP + Bearer Token)"]

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
        TunnelService["cloudflared Tunnel Daemon\n(Persistent Outbound QUIC/HTTP2 Tunnel)\n(Runs in User Session / Task Scheduler)"]
        FastAPI["FastAPI Backend (0.0.0.0:8000)\n(Bound to Loopback + Private LAN)"]
        AuthMiddleware["RequestMiddleware\n(Constant-Time Bearer Auth)"]
        MOSSRelay["MOSS Live WebSocket Relay\n(Direct WebSocket Auth & Upstream Relay)"]
        LocalData[("Local Document Index\nSQLite FTS5 + MiniLM Embeddings")]
        NotebookLM["NotebookLM Coprocessor\n(Persistent Playwright Browser Profile)"]

        Edge <-->|"Multiplexed Outbound Tunnel"| TunnelService
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
   * `GET /health`: Probed without credentials by `ServerScanner.isHealthOkUrl` with a 5000ms timeout to verify connectivity. Returns static `{"status": "ok"}` with zero host metadata.
   * `POST /v1/assistant/turns`: Multipart form data containing user text, audio transcripts, rolling photos, and conversation history. Authenticated via header `Authorization: Bearer <API_BEARER_TOKEN>`.
   * `POST /v1/assistant/photos/upload`: Ephemeral image staging for multi-turn visual memory.
   * `GET/POST /v1/notebooklm/*`: Grounded book discussion and notebook status queries. Supports long-poll timeouts up to 45.0 seconds.

2. **WebSocket Realtime Stream (WSS on Port 443)**:
   * `WSS /v1/realtime/moss-live`: Bidirectional full-duplex PCM audio and camera frame relay.
   * *Authentication*: Enforced in `backend/app/moss_live.py` via HTTP upgrade header `Authorization: Bearer <token>` (or `?token=<token>` query param).
   * *Binary Multiplexing*:
     * Audio Uplink: 16 kHz 16-bit mono PCM chunks (~640 bytes / 20ms).
     * Audio Downlink: 48 kHz 16-bit mono PCM chunks (~1920 bytes / 20ms).
     * Video Camera Stream: Raw JPEG byte packets prefixed with single-byte header `0x01`, transmitted every 1500ms.
   * *Keep-Alive*: Inherent active traffic every 20ms–1500ms eliminates Cloudflare's 100-second idle connection drops. In suspended mode (when switching to NotebookLM), a 15-second ping heartbeat maintains socket state.

### 3. Android Client Architecture & Defect Remediation

#### Component 1: `BackendEndpoint.kt` Normalization
* **Protocol-Driven Normalization**:
  * Detect whether the configured host is a local loopback (`127.0.0.1`), a local private LAN IP (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`), or a remote domain (`.trycloudflare.com`, `.ts.net`, `.ngrok`, or custom FQDN).
  * If a scheme is explicitly provided (`https://` or `http://`), preserve the scheme.
  * If the host is a remote domain without an explicit port, use standard HTTPS/WSS (port 443) and **NEVER append `:8000`**.
  * Provide clean `httpUrl(path)` and `wsUrl(path)` builders that format correctly for both HTTP/HTTPS and WS/WSS.

#### Component 2: `ServerScanner.kt` Remote Probing
* **Bypass Raw TCP Sockets for Remote Endpoints**:
  * If `isRemoteHost(host)` is true, bypass raw TCP socket probing on port 8000.
  * Probe `https://<host>/health` directly via `HttpURLConnection` with a 5000ms connection and read timeout.
  * Delegate `isRemoteHost` strictly to `BackendEndpoint.isRemoteHost()`.

#### Component 3: Elimination of Duplicate `:8000` Suffix Logic
* `LiveAssistant.kt`, `MossLiveTransport.kt`, and `SettingsInfo.kt` delegate host resolution strictly to `BackendEndpoint.normalizeHost()`.

### 4. Windows Host Automation & Service Lifecycle

#### Component 1: Deployment Script (`scripts/setup_remote_tunnel.ps1`)
* Checks for `API_BEARER_TOKEN` in `backend/.env`. If missing, generates a 256-bit secure hex token and saves it.
* Automatically locates or downloads `cloudflared.exe` from official Cloudflare releases.
* Verifies local backend health (`http://127.0.0.1:8000/health`) before starting the tunnel.
* Runs the tunnel, extracts the active `https://*.trycloudflare.com` URL, and displays a clean console banner.
* Detects USB-connected Android phones via ADB and automatically writes the configuration into app SharedPreferences for instant zero-typing setup.

#### Component 2: One-Click Launcher (`Start Remote Tunnel.cmd`)
* Double-click batch script located in the repository root to start the tunnel easily.

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

## Detailed Implementation Plan & Status

### 1. Touched File Manifest

| File Path | Status | Summary of Changes |
| :--- | :--- | :--- |
| `android/app/src/main/java/com/visualassistant/app/network/BackendEndpoint.kt` | **COMPLETED** | Added `isRemoteHost()` and `normalizeHost()` static companion methods; ensured remote domains use HTTPS/WSS over port 443 without appending `:8000`. |
| `android/app/src/main/java/com/visualassistant/app/network/ServerScanner.kt` | **COMPLETED** | Updated `verifyHost()` to bypass raw socket probing for remote endpoints and query `https://<host>/health` with 5000ms timeout; delegated `isRemoteHost` to `BackendEndpoint`. |
| `android/app/src/main/java/com/visualassistant/app/chat/LiveAssistant.kt` | **COMPLETED** | Replaced manual `:8000` port logic with `BackendEndpoint.normalizeHost()`. |
| `android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveTransport.kt` | **COMPLETED** | Replaced manual `:8000` port logic with `BackendEndpoint.normalizeHost()`. |
| `android/app/src/main/java/com/visualassistant/app/ui/SettingsInfo.kt` | **COMPLETED** | Replaced manual `:8000` port logic with `BackendEndpoint.normalizeHost()`. |
| `android/app/src/test/java/com/visualassistant/app/network/BackendEndpointTest.kt` | **COMPLETED** | Expanded unit tests for remote Cloudflare tunnel normalization, HTTPS/WSS scheme generation, and Bearer token headers (all passing). |
| `scripts/setup_remote_tunnel.ps1` | **COMPLETED** | Created automated PowerShell script for token generation, automatic `cloudflared` binary acquisition, tunnel execution, URL discovery, and ADB auto-provisioning. |
| `Start Remote Tunnel.cmd` | **COMPLETED** | Created root one-click launcher for starting the remote cellular tunnel. |
| `PROJECT_HANDOFF.md` | **COMPLETED** | Updated master project documentation with remote cellular connectivity guide. |
