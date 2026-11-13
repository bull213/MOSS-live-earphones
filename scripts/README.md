# Windows Automation & Setup Scripts

## Overview
PowerShell and VBScript utilities for launching, stopping, and automating the local Python development backend and ADB reverse port forwarding daemon on Windows.

## Interface
- `run_backend.ps1`: Starts the FastAPI development server using `.venv\Scripts\python.exe -m uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8000`.
- `stop_backend.ps1`: Finds and terminates active Python/Uvicorn processes bound to port 8000.
- `start_backend_silently.vbs`: VBScript launcher running `run_backend.ps1` without creating a visible console window.
- `phone_trigger_listener.ps1`: Background event listener monitoring ADB device state and maintaining `adb reverse tcp:8000 tcp:8000` port forwarding.
- `install_startup_task.ps1` / `uninstall_startup_task.ps1`: Registers/unregisters a Windows Task Scheduler job running the phone trigger listener at Windows user logon.
- `setup_firewall.ps1`: Configures Windows Firewall rules for local development.
- `setup_always_on_service.ps1`: Configures AC power management (never sleep), Windows Firewall for Tailscale/LAN on port 8000, and elevated startup task running backend as SYSTEM.
- `setup_server_node.ps1`: One-time automated bootstrap of the dedicated 24/7 server machine (creates venv, installs requirements, sets up service, and bootstraps NotebookLM).
- `update_server.ps1`: 1-click update on 24/7 server: pulls latest git commits, updates python dependencies, restarts the backend service, and checks health.
- `backup_server_data.ps1`: Backs up `library.sqlite3`, `master_token.json`, `storage_state.json`, and `.env` with 7-day retention.

## Invariants
- **Host Binding**: Scripts must bind the backend strictly to `127.0.0.1`, never `0.0.0.0`.
- **User Scope**: Task Scheduler tasks install under the current user's login session (`$env:USERNAME`), avoiding requirement for domain administrator elevation.

## Side Effects
- Spawns and kills Windows processes (`uvicorn.exe`, `python.exe`, `adb.exe`).
- Modifies Windows Task Scheduler (`schtasks.exe`).
- Executes ADB CLI commands (`adb reverse tcp:8000 tcp:8000`).

## Verification
- Test execution: `powershell -ExecutionPolicy Bypass -File scripts\run_backend.ps1`
