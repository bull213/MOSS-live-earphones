# Concepts Brief: Dual-Mode Native Android Backend Migration

* **Author:** bull213
* **Date:** 2026-11-13
* **Target Architecture:** Dual-Mode Architecture (Native Standalone Mobile by Default + Optional Workstation PC Mode)
* **Target Device:** Samsung Galaxy S24+ (Android 14 / API 34+, minimum API 31+)
* **Document Status:** Post-Goldfish Multi-Lens Brainstorm Synthesis
* **Target Architecture Document:** [backend_migration_to_android_plan.md](file:///c:/Users/liorc/PycharmProjects/PythonProject1/docs/designs/backend_migration_to_android_plan.md)

---

## Overview

This project migrates the **Airflow / AI Visual Assistant** from a Windows PC-dependent architecture (FastAPI on Uvicorn + ADB reverse port forwarding over port 8000) into a **self-contained Android application** operating independently on a Samsung Galaxy S24+ phone.

In **Standalone Mobile Mode (Default)**, the phone executes all local tasks on-device (offline speech recognition via Moonshine STT, hybrid document search via SQLite FTS5 and ONNX MiniLM, image preprocessing, rolling conversation history) and communicates directly with cloud AI providers (OpenAI Vision, StepFun, MOSS Live, Google NotebookLM) over TLS 1.3 HTTPS and WSS connections. The application requires no PC, no USB tethering, no ADB commands, and no local network discovery.

In **Workstation Mode (Optional)**, the user or developer can flip a single toggle in Settings to route traffic through the existing Windows PC FastAPI backend on port 8000 for development, debugging, and desktop testing.

---

## Analysis

A multi-lens Goldfish review (covering UX & Accessibility, Codebase Architecture & Room v3, Skeptical Systems Design & Galaxy S24+ Thermals, Plain English Comprehension, Implementation Readiness & Native Dependencies, Structural Layering, and Inventive Contradictions) evaluated the proposed migration and identified critical issues and technical resolutions:

### 1. Missing Native PDF Text Extraction on Android (`egm-implementation-readiness`, `egm-codebase-details`)
* **Finding:** Android's built-in `android.graphics.pdf.PdfRenderer` only renders PDF pages into Bitmaps. It has **no API to extract raw digital text**. Android's new `PdfDocument` text extraction APIs are restricted to Android 15 (API 35+), whereas this application targets API 31+.
* **Resolution:** Integrate `com.tom-roush:pdfbox-android:2.0.27.0` (pure Java/Android port of Apache PDFBox). It extracts structured digital text blocks, page numbers, and bookmarks in ~50ms per page directly on the phone without bitmap rendering, perfectly satisfying the user's requirement to delete the book OCR pipeline while ingesting digital PDFs.

### 2. Native Library Collision (`libonnxruntime.so`) (`egm-implementation-readiness`)
* **Finding:** The application bundles `ai.moonshine:moonshine-voice:0.1.6`, which includes vendored JNI libraries (`libmoonshine-jni.so`). Adding `com.microsoft.onnxruntime:onnxruntime-android` causes duplicate `.so` packaging collisions or ABI symbol conflicts during APK assembly.
* **Resolution:** Add `packaging.jniLibs.pickFirsts.add("**/libonnxruntime*.so")` to `android/app/build.gradle.kts`. In Milestone 3, verify early linking to confirm that Moonshine STT and Microsoft ONNX Runtime coexist cleanly in the same process on `arm64-v8a`.

### 3. Google WebView Authentication Blocks (`disallowed_useragent`) (`egm-design`, `egm-implementation-readiness`)
* **Finding:** Pointing an embedded Android `WebView` to `https://accounts.google.com` to capture session cookies frequently triggers Google Error 403 (`disallowed_useragent`), as Google actively blocks embedded webviews for security.
* **Resolution:** 
  1. Set `WebSettings.userAgentString` to match the system Chrome Mobile browser user-agent and enable DOM storage.
  2. Provide a **Desktop-to-Mobile QR Transfer Fallback**: When the PC backend is running in Workstation mode, display a local QR code with authenticated cookies from `storage_state.json` that the phone camera scans to authenticate instantly.

### 4. Older Adult Key Entry Ergonomics (`egm-design`, `egm-comprehension`, `egm-inventive-catalyst`)
* **Finding:** Requiring older adults to manually type three 40–64 character cryptographic API keys (OpenAI `sk-proj-...`, StepFun, MOSS Live) on a mobile touch keyboard causes high input friction and errors.
* **Resolution:** Provide three assistive input methods in `ApiKeySettingsScreen`:
  1. **One-Tap Clipboard Paste** with immediate visual validation checkmarks (`✓ Key Active`).
  2. **Camera Key Scanner** using the bundled offline ML Kit Latin OCR to scan keys printed on paper or displayed on a computer screen.
  3. **PC-to-Phone Sync**: In Workstation mode, a single button automatically transfers keys from `backend/.env` over local loopback into the phone's Keystore.

### 5. Isolated Knowledge Database vs Room v3 Migration (`egm-structural`)
* **Finding:** Merging document tables and FTS5 virtual tables into `conversations.db` (currently Room schema version 3) risks accidental eviction under the 100 MiB rolling memory budget and requires complex schema migrations.
* **Resolution:** Maintain **two distinct database files**:
  - `conversations.db`: Existing Room v3 database managing chat turns and photos with rolling eviction.
  - `knowledge.db`: Independent `KnowledgeDatabase` managing permanent documents, sections, passages, and FTS5 virtual table `passage_fts`.

### 6. Galaxy S24+ Thermal Budget & Lexical-First Vector Pruning (`egm-zoom-out`, `egm-inventive-catalyst`)
* **Finding:** Concurrently running Moonshine STT + WebRTC VAD + CameraX streaming + MOSS Live audio + ONNX MiniLM vector retrieval can cause CPU spikes and thermal throttling.
* **Resolution:**
  - Quantize `all-MiniLM-L6-v2.onnx` to INT8 (~23 MB).
  - Adopt **Lexical-First Vector Pruning**: Query SQLite FTS5 first to retrieve the top 50 BM25 candidate passages. Compute ONNX cosine similarity only on those 50 candidates rather than thousands of passages, cutting matrix multiplication overhead by >95% while preserving high RRF retrieval accuracy.

---

## Goals vs. Non-Goals

### In-Scope Goals
- **100% PC Independence**: Complete operation of all features on the Samsung Galaxy S24+ with the PC turned off.
- **Dual Execution Mode**: Settings toggle switching between "Standalone Mobile" (direct cloud calls) and "Workstation PC" (`127.0.0.1:8000`).
- **Direct MOSS Live Realtime Duplex**: Android connects directly to `wss://api.mosi.cn/v1/realtime` with 16 kHz audio uplink, 48 kHz downlink, continuous 1500ms video heartbeat, and 15s suspended keepalive ping.
- **Direct OpenAI & StepFun Vision/Chat**: Native OkHttp clients calling OpenAI (`gpt-6.1-terra`) and StepFun (`step-5-preview`).
- **On-Device Knowledge Engine**: SQLite FTS5 + INT8 quantized ONNX MiniLM-L6-v2 vector retrieval on the phone.
- **Native PDF Text Ingestion**: Digital PDF parsing via `pdfbox-android` without OCR.
- **NotebookLM Collections (Option C)**: In-app Google Sign-In WebView for cookie extraction, plus PC QR code sync fallback.
- **Senior-Friendly BYOK Security**: Android Keystore encryption (AES-256-GCM) with Camera Key Scanner and Clipboard Paste.
- **Hardware Audio Discipline**: Single persistent `AudioRecord` (`PhoneAudioCaptureRouter`) and dynamic echo suppression (`MossEchoSuppressor`).

### Out-of-Scope Non-Goals
- **Book OCR Pipeline**: Converting scanned or photographed book pages into text is explicitly deleted from scope.
- **Embedded Python (Chaquopy)**: Embedding Python runtime or PyTorch into the APK is permanently rejected.
- **Third-Party Server Hosting Requirement**: The app does not mandate a paid cloud relay server; all standalone operations occur directly between phone and provider APIs.
- **Cross-Database Merging**: Knowledge tables will not be merged into `conversations.db`.

---

## Alternatives Weighed

1. **Embedded Python Backend (Chaquopy / Termux)**:
   * *Rejected by reviewers (`egm-zoom-out`, `egm-implementation-readiness`)*: Adds >150 MB to APK, consumes 450–800 MB RAM, takes 5–8 seconds to cold start, and frequently triggers Android Low Memory Killer during camera streaming.
2. **Android Framework `PdfRenderer` for Text Extraction**:
   * *Rejected by reviewers (`egm-implementation-readiness`, `egm-codebase-details`)*: `PdfRenderer` only renders Bitmaps and cannot extract raw text characters; Android 15 `PdfDocument` text APIs are unavailable on target API 31+. Replaced by `com.tom-roush:pdfbox-android`.
3. **Merging Knowledge into `conversations.db`**:
   * *Rejected by reviewers (`egm-structural`)*: Risked accidental deletion of reference documents under the 100 MiB rolling memory budget and introduced complex Room migration risks. Replaced by dedicated `knowledge.db`.
4. **Single-Mode Hard Cut (Purging PC Backend Code Completely)**:
   * *Rejected by author*: User explicitly requested retaining the ability to use the PC backend for development and testing. Replaced by a clean `ModeRouter` with a Settings toggle.
5. **Full Library Vector Brute-Force Scanning**:
   * *Rejected by reviewers (`egm-zoom-out`, `egm-inventive-catalyst`)*: Scanning thousands of passages with ONNX tensor operations on every query causes thermal throttling on Galaxy S24+. Replaced by Lexical-First Vector Pruning (top 50 BM25 passages passed to ONNX).

---

## Key Decisions & Staging for Next Steps

* **Milestone 1: Direct Cloud Gateways & Mode Router**
  - Implement `ModeRouter.kt` and `AssistantGateway` interface.
  - Implement `DirectCloudAssistantGateway.kt` (OpenAI & StepFun via OkHttp) and `RemotePCAssistantGateway.kt` (`127.0.0.1:8000`).
  - Port image normalization to Android `Bitmap` with white background compositing for transparent PNGs.
* **Milestone 2: Direct MOSS Live Realtime Transport**
  - Implement `DirectMossLiveTransport.kt` connecting to `wss://api.mosi.cn/v1/realtime`.
  - Port two-step session handshake (`attach_session`, `session.update`) and 15s suspended ping loop to Kotlin.
* **Milestone 3: On-Device Knowledge Engine with ONNX Runtime**
  - Add `packaging.jniLibs.pickFirsts` in `build.gradle.kts` for `libonnxruntime.so`.
  - Package quantized `all-MiniLM-L6-v2.onnx` (INT8, ~23 MB).
  - Implement `knowledge.db` with SQLite FTS5 table `passage_fts`.
  - Implement Lexical-First Vector Pruning and Kotlin Reciprocal Rank Fusion (`k=60`).
* **Milestone 4: Native PDF Text Ingestion (No OCR)**
  - Integrate `com.tom-roush:pdfbox-android:2.0.27.0`.
  - Ingest digital PDFs, chunk text (1,200 chars / 180 overlap), and embed into `knowledge.db`.
* **Milestone 5: Collections / NotebookLM via Option C**
  - Build `NotebookSignInActivity` with customized user-agent and cookie capture.
  - Implement desktop QR-code token import fallback from PC `storage_state.json`.
  - Implement mobile Google RPC client (`batchexecute`).
* **Milestone 6: Settings Screen & BYOK Ergonomics**
  - Build `SettingsScreen.kt` with Mode Toggle (Standalone vs PC).
  - Store credentials in Android Keystore via `EncryptedSharedPreferences`.
  - Add Camera Key Scanner (ML Kit) and Clipboard Paste buttons.
* **Milestone 7: Standalone Verification & Handoff**
  - Execute test suite with PC backend completely shut down across Wi-Fi, 5G cellular, and airplane mode.
