# Private Remote Cellular Connectivity for AI Visual Assistant

* **Author:** bull213
* **Date:** 2026-11-13

## Overview

This feature enables the AI Visual Assistant Android application to operate seamlessly over mobile cellular data (4G/5G) when away from home, connecting directly to the user's personal always-on Windows PC server without incurring financial costs and without requiring any secondary helper applications (such as VPNs) on the phone.

The system preserves full feature parity across all six interaction modes: MOSS Live full-duplex conversational voice (16 kHz uplink, 48 kHz downlink PCM), continuous 1500ms JPEG camera frames, HTTP assistant photo/text turns, ephemeral photo caching, and long-poll Google NotebookLM grounded queries.

## Analysis

A multi-lens Goldfish review across seven engineering and user experience perspectives evaluated four candidate connection strategies and uncovered critical technical invariants, software defects, and inventive resolutions:

### 1. Inherent Incompatibility of Free-Tier Proxy Services
* **Bandwidth Exhaustion in Realtime Voice/Video:** Ngrok's free tier imposes a strict 1 GB/month bandwidth limit and 20,000 requests/month cap. A full-duplex MOSS Live session with continuous 1500ms JPEG camera streaming generates approximately 161 KiB/s (9.6 MiB/minute or 580 MiB/hour). Under Ngrok, the entire monthly bandwidth allocation would be depleted in roughly 1.7 hours of active use.
* **Unmetered Transit at Zero Cost:** Cloudflare provides unmetered data transit on its free tier through outbound-initiated reverse tunnels (`cloudflared`). The compute workload (speech transcription, OCR, document indexing, embeddings) remains entirely on the home PC, achieving a true $0.00/month operating cost.

### 2. Critical Codebase Defect: Hardcoded Port 8000 Appending
* **The Defect:** In `ServerScanner.kt`, `LiveAssistant.kt`, and `MossLiveTransport.kt`, remote host parsing was hardcoded with `val fullHost = if (resolvedHost.contains(":")) resolvedHost else "$resolvedHost:8000"`.
* **The Failure:** When a user enters a secure remote HTTPS domain (such as `https://xyz.trycloudflare.com` or a named tunnel domain), the application appended `:8000` (`xyz.trycloudflare.com:8000`). Because secure cloud edge proxies listen exclusively on standard port 443 (HTTPS/WSS) and reject port 8000 traffic, all cellular requests timed out with a `SocketTimeoutException`.
* **Resolution:** Normalize host string parsing to detect remote domains and standard HTTPS/WSS prefixes, routing traffic over port 443 without appending local development ports.

### 3. Loopback Security Filter Alignment
* **Mechanism:** The backend security layer (`backend/app/security.py` and `backend/app/moss_live.py`) rejects public IP connections with HTTP 403 / WS 4403 to prevent open internet exposure.
* **Compatibility:** Because `cloudflared` runs locally on the Windows host and forwards traffic to `127.0.0.1:8000`, the FastAPI server observes `request.client.host` as `127.0.0.1`. Existing loopback security checks pass naturally while Windows Firewall continues blocking direct inbound internet access on port 8000. All remote calls are authenticated using a 256-bit cryptographic Bearer token (`Authorization: Bearer <token>`).

### 4. Inventive Contradictions & TRIZ Resolutions
* **Perimeter Security vs. Zero Client Apps:** Opening router ports exposes the home workstation to malicious internet scanning. Requiring a VPN app violates the zero-auxiliary-app constraint.
  * *Resolution (TRIZ Principle 24 - Intermediary & Principle 22 - Blessing in Disguise):* An outbound-only tunnel daemon bridges from the PC to an edge proxy. The edge proxy terminates TLS on port 443, while the application enforces Bearer token authentication. No inbound router ports are opened, and no VPN app is required on the mobile phone.
* **Cellular Roaming Drops vs. Continuous Dialogue:** When walking outdoors, handovers between cell towers or switching between Wi-Fi and mobile data cause brief TCP connection drops.
  * *Resolution (TRIZ Principle 10 - Prior Action & Principle 15 - Dynamics):* The backend MOSS Live relay acts as a stateful session anchor. Upon an unexpected client disconnect, the backend holds the upstream voice session to `api.mosi.cn` open for a 15-second grace window while buffering incoming assistant audio chunks. When the Android app's reconnection job reconnects with the active session token, playback resumes without resetting the conversation.

## Goals vs. Non-Goals

* **In-Scope Goals:**
  * Zero financial cost ($0.00/month): No paid VPS, cloud instances, or paid tunnel subscriptions.
  * Zero secondary mobile applications: The user only runs the AI Visual Assistant Android app on their phone.
  * Full 6-mode feature parity over 4G/5G mobile data (MOSS Live duplex voice/video, HTTP turns, photo caching, NotebookLM).
  * Automated persistent background tunnel service on Windows that auto-starts with the operating system.
  * Fix Android host parsing bugs that append port 8000 to remote HTTPS/WSS endpoints.
  * Secure access control using constant-time pre-shared Bearer token verification.

* **Out-of-Scope Non-Goals:**
  * Router port forwarding, UPnP configuration, or manual Dynamic DNS (DDNS) management.
  * Paid cloud migration (deploying FastAPI or databases to AWS, GCP, Fly.io, or Railway).
  * On-phone WireGuard or Tailscale client installations.
  * In-APK VPN service implementations.

## Alternatives Weighed

* **Rejected: Ngrok Free Tier**
  * *Reason for Rejection:* Ngrok limits free tier bandwidth to 1 GB per month and 20,000 requests. Continuous 16 kHz/48 kHz PCM audio and 1500ms JPEG video frames generate ~580 MiB/hour, depleting the monthly quota in under two hours.
* **Rejected: Home Router Port Forwarding with Dynamic DNS (DDNS)**
  * *Reason for Rejection:* High security risk from opening an unauthenticated port directly to the internet; requires administrative access to home routers; fails completely under ISP Carrier-Grade NAT (CGNAT); requires complex manual Let's Encrypt TLS certificate lifecycle management on Windows.
* **Rejected: Third-Party Mesh VPN Client on Mobile (Tailscale / WireGuard)**
  * *Reason for Rejection:* Rejected by author: User explicitly constrained the design to zero auxiliary mobile apps installed on the phone. Everything must run natively from inside the application.
* **Rejected: Cloud VPS Deployment (AWS / GCP / Fly.io / Railway)**
  * *Reason for Rejection:* Incurs recurring hosting fees ($5–$25/month), requires transferring personal local document libraries and SQLite databases off the home workstation, and breaks the local NotebookLM browser profile session (`storage_state.json`).

## Key Decisions & Staging for Next Steps

1. **Adopt Persistent Cloudflare Outbound Tunneling:** Run `cloudflared` as an always-on Windows Service to expose `127.0.0.1:8000` securely at zero cost.
2. **Patch Android Client Endpoint Resolution:** Update `BackendEndpoint.kt`, `ServerScanner.kt`, `LiveAssistant.kt`, and `MossLiveTransport.kt` to never append `:8000` to remote HTTPS/WSS addresses.
3. **Automate Windows Setup Script:** Provide a single PowerShell script (`scripts/setup_remote_tunnel.ps1`) that generates a secure Bearer token, registers the tunnel as a persistent Windows background service, and outputs a one-click connection string for the mobile app.
4. **Implement Cellular Reconnection Logic:** Add a 3-attempt exponential backoff reconnect loop in `MossLiveSessionController.kt` to handle cellular tower handoffs smoothly.
