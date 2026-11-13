# Android Network & Gateway Subsystem

## Overview
Manages HTTP endpoint configuration, server reachability scanning, and ADB loopback tunneling for communication with the local Python backend.

## Interface
- `BackendEndpoint` (`BackendEndpoint.kt`): Manages base URL resolution (defaulting to `http://127.0.0.1:8000/` forwarded over USB ADB reverse).
- `ServerScanner` (`ServerScanner.kt`): Periodically checks backend health (`/health` or `/v1/turns`) to detect development server availability.
- `AppWakeTrigger` (`AppWakeTrigger.kt`): Handles intent triggers waking the application when background scripts signal phone wake.
- `NotebookAuthApi` (`NotebookAuthApi.kt`): HTTP client interfacing with backend NotebookLM endpoints (`/v1/notebooklm/status`, `/v1/notebooklm/auth/trigger-login`, `/v1/notebooklm/auth/reset`, `/v1/notebooklm/notebooks`, `/v1/notebooklm/notebooks/{id}/history`).
- `NotebookAuthStatus` (`NotebookAuthApi.kt`): Data model reporting configuration state, authentication status, logged-in email, and failure reasons.
- `NotebookItem` (`NotebookAuthApi.kt`): Data model representing an individual NotebookLM notebook item (`id`, `title`).
- `NotebookTurn` (`NotebookAuthApi.kt`): Data model capturing conversation turn history retrieved from a notebook (`turnId`, `question`, `answer`, `relativeOrder`).

## Invariants
- **Security & Port Binding**: Default communication is restricted to `127.0.0.1:8000` via ADB reverse; cleartext HTTP over loopback is explicitly permitted in network security config.
- **Fail-Fast**: Network timeouts are configured to fail fast (5,000 ms connect, 10,000 ms read in `NotebookAuthApi`) and present user-friendly error banners rather than hanging the UI.
- **Zero Client Token Storage**: Authentication tokens and cookies are managed entirely by the backend coprocessor; the client only consumes boolean authentication state and email identifiers.

## Side Effects
- Outbound HTTP requests to `http://127.0.0.1:8000` (`/v1/notebooklm/*`, `/health`, `/v1/turns`).

## Verification
- Run: `.\android\gradlew.bat -p android testDebugUnitTest`
