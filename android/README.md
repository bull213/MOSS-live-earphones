# AI Visual Assistant for Older Adults

A phone-first assistant for everyday tasks, starting in English on Android 12
or newer with a Python backend on the developer's computer.

## Architectural Subsystems & Boundaries
- [Android Application Package](./app/src/main/java/com/visualassistant/app/README.md): Single-activity Kotlin / Jetpack Compose application architecture.
  - [Camera Subsystem](./app/src/main/java/com/visualassistant/app/camera/README.md): CameraX photo capture and preview.
  - [Chat & Discussion Subsystem](./app/src/main/java/com/visualassistant/app/chat/README.md): Room DB, Recent Memory, and LiveAssistant turns.
  - [Voice & Offline Speech Subsystem](./app/src/main/java/com/visualassistant/app/voice/README.md): Moonshine offline STT, WebRTC VAD, and Android TTS playback.
  - [User Interface Subsystem](./app/src/main/java/com/visualassistant/app/ui/README.md): High-contrast accessible Compose screens, badges, and readouts.
  - [MOSS Live Subsystem](./app/src/main/java/com/visualassistant/app/mosslive/README.md): Bidirectional PCM voice streaming over loopback WebSockets.
  - [Knowledge Ingestion Subsystem](./app/src/main/java/com/visualassistant/app/knowledge/README.md): Document scanning and multi-page upload.
  - [Network & Endpoints Subsystem](./app/src/main/java/com/visualassistant/app/network/README.md): Base URL configuration and server scanner.

## Current milestone

The main screen is a combined **Recent Memory** timeline with a compact composer.
It shows retained interactions across discussions in insertion order, including
saved-chat turns that are still recent. Older messages can be loaded in pages.
The sidebar opens saved chats, starts a new discussion and shows Settings
information. **Save chat** protects the current discussion and its future
messages; it does not save every discussion visible in the combined timeline.
Opening a saved chat displays that conversation; return to Recent Memory through
the sidebar. Normal assistant turns support text and optional photos.
**Voice** transcribes English locally using the phone microphone.
**Collections**: The sidebar opens Collections to browse notebooks from the connected NotebookLM account. Entering a notebook allows direct interaction (typing or voice); turn-based questions query the notebook, audio earcons signal processing, and responses are read aloud via TTS.

### MOSS Live (voice with Recent Memory context)

**Start MOSS Live** is separate from the existing local **Voice** transcription
and normal HTTP **Live AI** mode. Starting it explicitly asks for microphone
permission if needed, connects over the development USB-forwarded loopback
connection, and transmits phone-microphone audio to MOSS through the FastAPI
backend. The phone sends continuous 16 kHz mono PCM S16LE, including silence;
MOSS audio is played as 48 kHz mono PCM S16LE. The screen shows session state,
microphone transmission, and a Stop control. User and assistant transcripts are
shown in the regular chat timeline and saved in Room as updates arrive; provider
turn IDs make revisions update rather than duplicate messages. **Start MOSS
Live** is distinct from **Switch to live AI**, which opens the rolling-memory
chat for text/photo assistant turns.

Before enabling the microphone, the app sends bounded rolling Recent Memory as
reference context: up to eight finalized text turns and up to three newest
retained photos. Photos are sent as tagged JPEG camera frames resized to a
512-pixel long side and at most 512 KiB each. Successful normal HTTP chat turns
added during the session are appended as reference context, with an attached
photo sent as a frame. MOSS's own live transcripts are not resent.

MOSS is a cloud service: audio is sent to MOSS only while the user has
explicitly started this session. The session stops when the user stops it, the
app backgrounds, or the active chat route is left. Raw audio is not saved by
this feature. Camera streaming is **not implemented**; no camera is activated
by MOSS Live. No MOSS API key is in the Android app. Configure it only in the
backend's ignored `backend/.env` file as described in
[backend setup](../backend/README.md#moss-live-realtime-gateway), then restart
the loopback backend and establish `adb reverse tcp:8000 tcp:8000`.

The stable model ID `moss-live-1.0` passed an upstream WebSocket handshake
preflight; this did not attach a session or verify audio relay. Do not start a
MOSS session for routine validation: it connects to the real, potentially
billable provider. Camera streaming is not implemented and remains off.

### Collections (NotebookLM Voice & Text Chat)

Selecting **Collections** in the sidebar displays the list of notebooks associated with the connected Google account (retrieved via `NotebookAuthApi.listNotebooks`). Selecting a notebook opens a dedicated conversational view:
- **Server-Side Memory**: Conversation turns are grounded directly in the selected NotebookLM notebook; chat history is fetched from the notebook upon opening (`NotebookAuthApi.getHistory`) and managed upstream.
- **Turn-Based Voice Interaction**: Tapping the microphone uses on-device Moonshine STT to transcribe the query. Once transcribed, `ToneEarconPlayer` emits a subtle waiting tone (`TONE_PROP_BEEP`) while the backend answers. Upon completion, earcons stop and the Android `TextToSpeech` engine articulates the response aloud.
- **Audio-Optimized Badging**: Grounded answers display a purple `[📚 Manual: <title>]` badge with audio readout controls (`Replay`).

### Voice-Command Switching Between MOSS Live and NotebookLM

Hands-free voice switching between MOSS Live and NotebookLM Collections:
- **Shared Hardware Capture Multiplexer (`AudioCaptureRouter` / `PhoneAudioCaptureRouter`)**: Avoids Android 12+ `AudioFlinger` capture concurrency lockouts (`isClientSilenced = true`) by running a single 16 kHz Mono PCM `AudioRecord` instance on `Dispatchers.IO` and multiplexing frames across `AudioDestination.MOSS`, `AudioDestination.VOICE`, and `AudioDestination.MUTED`.
- **Command Grammar & Intent Parsing (`VoiceCommandClassifier`)**: Pure Kotlin classifier matching speech transcripts against the user's available notebooks:
  - `"Use notebook [Notebook Name]"` / `"Switch to notebook [Notebook Name]"`: Suspends MOSS Live uplink, speaks TTS confirmation, and enables Moonshine STT in the chosen notebook.
  - **Compound Single-Breath Queries**: `"Use notebook [Title], [Question]"` (e.g. `"Use notebook Neuroscience what is the dopamine pathway?"`) automatically isolates the question, queries NotebookLM, plays earcon waiting tones, and speaks the grounded reply without requiring a second utterance.
  - **Return to MOSS**: `"Back to MOSS"` / `"Switch to MOSS"`: Halts active speech, vocalizes confirmation, and restores MOSS Live duplex uplink with <100ms resumption.
  - **Spoken Summary**: `"Summarize now"`: Requests a concise 2-3 sentence spoken summary from the active notebook.
  - **Unknown Notebook Protection**: Vocalizes `"Notebook [Title] not found. Staying in MOSS"` and cleanly resumes MOSS uplink without dropping the session.
- **60-Second Quiescent Suspension Window**: Temporarily suspends MOSS Live audio/video (`mic_state: false`, `video.state: false`) with 15-second backend ping heartbeats. Automatically closes upstream after 60s of inactivity (`code=1000`) and seamlessly auto-reconnects when returning to MOSS.
- **Acoustic Echo Discipline**: 200ms post-TTS acoustic decay guard prevents phone speaker audio from triggering self-transcription echo.

### Latest implementation verification

The backend suite passes **198 tests** with one opt-in local-embedding test
skipped. Android passes **116 JVM tests**, app and instrumentation APK builds,
and lint; instrumentation tests were compiled but not run on the connected
phone. The updated debug APK was installed in place on the Samsung S24+ and the
app launched. The install used `adb install -r`, preserving app data; no
permissions, settings, or other phone state were changed. No MOSS session was
started.

### On-device speech transcription

Tap **Voice** in chat and grant microphone permission when Android asks. The app
buffers speech and begins transcribing after roughly two seconds of detected
silence. The completed speech sequence appears as one saved message, including
when Moonshine internally divides a long recording into segments. You can speak
additional phrases while listening continues. Tap **Stop voice**
to finish; listening also stops when the app goes into the background or you
leave the chat. If a transcript could not be saved, the app offers **Save
text** or **Discard**. A saved voice message remains available after restarting
the app, subject to the existing recent-memory retention rules. A pause of two
seconds can be interpreted as the end of a sequence, and background speech or
noise can affect detection. A single uninterrupted recording is limited to five
minutes; exceeding this limit stops recording and discards that unfinished audio.
Voice transcription does not automatically send a request to an AI provider.

The app uses the official Moonshine Voice SDK 0.1.6 with the English **Small
Streaming** model, pinned to `quantized_26_11_02`. The model is bundled in the APK;
speech recognition works without a network connection and raw microphone audio
is neither uploaded nor saved by this feature. The first activation extracts
and verifies the model in private app storage, so it needs extra free space and
can take longer than later activations. The app deliberately selects the phone's
built-in microphone; future earphones support needs a separate audio source.
The offline WebRTC VAD in `android-vad` 2.0.10 detects pauses; it does not
transcribe the buffered audio.
TV and other nearby speech can be transcribed too: this does not identify speakers.

On a new checkout, fetch the pinned model before building. From the repository
root in PowerShell, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\android\scripts\fetch-moonshine-model.ps1
```

The script downloads approximately 136 MiB of model data, checks the sizes and
SHA-256 hashes in `android/moonshine-model-manifest.json`, and writes ignored
build assets. Gradle verifies the same files before packaging. A debug APK with
the model and three supported Android ABIs is approximately 215 MiB. Moonshine
and the model are MIT licensed; their license is bundled in the app. The small
JNI compatibility patch for an upstream audio-buffer leak, its sources, and
rebuild instructions are described in [third_party/moonshine-jni/README.md](third_party/moonshine-jni/README.md).

The speech flow has been exercised offline on an isolated Android emulator,
including an actual Moonshine audio fixture and conversation restoration. A
physical-phone walkthrough and measurements of accuracy, latency, battery use,
and long-session behavior remain necessary; see [the speech evaluation plan](../evaluation/speech/README.md).

Recent chats share a 100 MiB logical content budget: the oldest completed unsaved
turns are removed when it fills. Existing conversations were migrated as saved.
Use **Photo** to capture and review an image, then send it with a typed question
to the configured assistant. The regular chat timeline shows submitted photos,
questions and provider responses.

Room stores messages in the app-private `databases/conversations.db`. Submitted
photos are re-encoded, orientation corrected and stripped of EXIF metadata, then
stored in app-private `files/conversation_photos`; temporary captures remain in
the existing cache. Deleting a conversation removes its saved photos when they
are no longer referenced. The app retries cleanup of orphaned owned files on
startup. Photos do not go into the gallery. Saved chats are local to this app;
uninstalling it or clearing its data removes them. The 100 MiB limit counts text
and distinct referenced photos, not database or filesystem overhead. A pending
or newest turn is protected and can temporarily exceed that budget. Chats from
the previous in-memory version cannot be recovered after that app process ends.

Normal assistant requests use `POST /v1/assistant/turns`. **Switch to live AI**
selects bounded rolling Recent Memory as context for text/photo turns; MOSS Live
is a separate voice-only session. Voice transcripts are excluded from normal
assistant context unless the user explicitly submits their text. The backend
can also use the local knowledge library and approved methods according to its
configured provider.

The simulated demo UI, client, backend endpoint and demo-only tests have been
removed. `camera/` owns capture, `chat/` owns assistant requests and Room
conversation storage, `mosslive/` owns realtime transport/session state,
`voice/` owns on-device speech transcription, and `ui/` owns Compose screens.

<details>
<summary>Earlier UI milestone notes (historical)</summary>

The presentation refactor separates shared controls, capture layout and task
progress screens; it retains the redesigned flow. An isolated offline build
passed 43 JVM tests and lint (0 errors, 6 warnings). Device UI tests and visual
verification are still pending; the refactored build is not installed.

The UI redesign replaces Home, camera, photo review and task layouts with a
shared light/dark Material 3 theme. Home now starts with **Take a photo**. The
task check reflects only the user's report; the demo does not analyze the photo.
An isolated offline build passed 43 Android unit tests and lint (0 errors, 6
warnings); instrumented tests and the real-device visual walkthrough remain
pending. No before/after device screenshots have been captured.

UI Milestone 3 adds a task assistant after submitting the photo and question.
Its example step and demo response are explicitly simulated; the photo is not
analyzed. Follow-ups reuse the same USB demo endpoint and current photo, without
conversation context being sent to the backend. The temporary display history
contains up to 30 text messages and is cleared on Finish. Confirmation is not
visual verification. Another photo reuses the existing camera and capture cache.
The Milestone 3 app and UI tests have not been run on the user's phone.

UI Milestone 2 adds a multiline question field and Get guidance / Retake photo
actions to the existing photo review. Retake preserves the question and clears
the previous response. Results are explicitly simulated. The user confirmed
that the previous Home and demo worked. The new debug APK and test APK build,
all 42 Android JVM tests pass, and lint reports 0 errors and 9 warnings. The
new UI tests have not run on a device. The Milestone 2 APK was installed on
the user's phone; the manual walkthrough remains pending.

</details>

The roadmap is in [EARPHONES_UI_PLAN.md](EARPHONES_UI_PLAN.md) and
[UI_AND_APP_PLAN.md](UI_AND_APP_PLAN.md). The phone assistant and MOSS Live
integrations are implemented; the E12 SDK and protocol remain unavailable.

The project also has a catalog of evaluation cases. A developer-only **mock
camera** tests delays, capture failures and disconnect/reconnect behavior
without E12 hardware. Normal assistant and MOSS Live requests require a running
backend reachable through the configured local or secure network connection.

The Python backend provides text/photo assistant turns through
`POST /v1/assistant/turns` and the separate real MOSS Live WebSocket gateway.
The older `/v1/turns` photo endpoint is retained. The demo endpoint is removed.
See [backend README](../backend/README.md) for provider configuration and
current backend verification; no MOSS session is started during builds/tests.

The intended E12 earphones protocol remains unknown; the phone supplies current
camera, microphone and speaker hardware.

## Android camera: setup and build

Use Android Studio for Kotlin and camera development; continue using PyCharm for
the Python backend. Open the `android` directory as the Android Studio project.
Android Studio 2026.2.2 is installed locally at
`android/.tools/android-studio/bin/studio64.exe`; its downloaded archive was
verified against the published SHA-256 checksum. Studio's graphical project sync
has not been exercised for the persistence milestone. Its Android build was
verified in an isolated copy without the existing Gradle daemon toolchain file;
the original project's daemon toolchain provisioning stalled in this environment.
The app supports Android 12 or newer (`minSdk=31`), compiles against SDK 37, and
currently targets SDK 36. Compiling against SDK 37 does not require Android 17
on the phone.

Pinned tooling: Android Gradle plugin 9.4.0, Gradle wrapper 9.6.0 with a distribution
checksum, built-in Kotlin/Compose compiler 2.2.10, Compose BOM 2026.09.00 and
CameraX 1.6.2. Use SDK platform 37.0, build tools 36.0.0 and platform tools (ADB).
Local conversation storage uses Room 2.8.5 and KSP 2.3.10.
Java/Kotlin bytecode targets Java 17.

Phone interface tests explicitly use Espresso 3.7.0. Compose's transitive
Espresso 3.5.0 attempts to call `InputManager.getInstance`, which is unavailable
on the connected Android 17 phone. The fix is confined to `androidTestImplementation`;
Google documents it in the [Espresso 3.7.0 release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0).

Local tools and SDK downloads are kept in ignored `.tools`; Gradle
caches and Android tool state are also ignored. `android/local.properties`
points to this machine's SDK and must not be copied to another computer.
On another computer, install the SDK packages with Android Studio's SDK Manager
and let Studio generate the local SDK path.

For this Windows/PyCharm environment, run from the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\PyCharm 2026.2.3\jbr'
$env:JAVA_OPTS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
$env:ANDROID_USER_HOME = Join-Path (Get-Location) 'android/.android-user-home'
& .\android\gradlew.bat --no-daemon --max-workers=4 --gradle-user-home .\android\.gradle-user-home -p android assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

This shell uses the existing PyCharm JBR 25.0.4. Java on this computer needs the
Windows certificate trust store to download dependencies. The ignored
`.gradle-user-home/gradle.properties` contains the matching
`systemProp.javax.net.ssl.trustStoreType=Windows-ROOT` and
`systemProp.javax.net.ssl.trustStore=NONE` settings for Gradle's build process.
TLS verification stays enabled; these Windows-specific settings are not required
on every computer. In Android Studio, select this same Gradle JDK and local
Gradle user home to reuse the verified command-line environment.

Outputs:

- App: `app/build/outputs/apk/debug/app-debug.apk`.
- Instrumented test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Unit-test report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Lint report: `app/build/reports/lint-results-debug.html`.

The APK is a development build, not a signed commercial release. Building the
test APK does not execute phone-based tests.

## Import a scanned book into the local RAG library

In the app, open the chat menu and choose **Import a book PDF**. The Android
document picker accepts a PDF. The app copies it temporarily into private app
cache, asks the loopback backend which pages lack usable embedded text, and
runs the bundled Google ML Kit Latin recognizer on only those pages. OCR runs
on the phone; the uploaded PDF and recognized page text travel to the computer
through the existing USB port-forward. The backend stores the original in
`backend/.data/knowledge/sources/` and indexes it in the same local SQLite/FTS
and MiniLM library used by the CLI. The phone cache copy is deleted when the
import finishes.

This requires the backend on `127.0.0.1:8000`, the existing ADB reverse mapping,
and the local MiniLM embedding model to be installed. For setup, follow the
backend README's `fetch-model` step before importing the first book. A PDF with
searchable text is indexed without running OCR. Page numbers in citations refer
to the original PDF pages. The app reports extraction warnings after import.
Documents are not sent to Baidu or an OCR cloud service.

The Android APK bundles ML Kit's Latin model for offline OCR. This is the
phone-compatible OCR path; Baidu Unlimited-OCR uses a separate model-serving
runtime and is not installed on Android. The importer supports PDFs up to 64
MiB and 3,000 pages. Keep the backend bound to loopback while using the local
book-import API.

## Install and test on an Android phone

Connect the phone with a data-capable USB cable. Enable Developer options and
USB debugging, unlock the phone, and accept its computer authorization prompt.
On Samsung phones, Developer options can be enabled by tapping **Settings →
About phone → Software information → Build number** seven times, then following
the phone's prompts.

```powershell
& .\android\.tools\sdk\platform-tools\adb.exe devices -l
```

Continue when the intended device is listed with status `device`.
`unauthorized` means the phone still needs to authorize this computer. An empty
list means ADB cannot see the phone: check USB debugging, USB connection mode,
the cable and the manufacturer's Windows ADB driver. Windows detecting the phone
for file transfer is not sufficient. Always select the intended serial explicitly
using ADB's `-s` option, even when only one phone is connected. Keep the phone
unlocked and its screen on during UI tests; do not change its lock or timeout settings.

```powershell
$phoneSerial = 'REPLACE_WITH_SERIAL_FROM_ADB_DEVICES'
$adb = '.\android\.tools\sdk\platform-tools\adb.exe'
& $adb -s $phoneSerial install .\android\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $phoneSerial shell am start -n com.visualassistant.app/.MainActivity
```

For a later update of this project's own app, use `install -r` to preserve its
data. Stop on installation errors; do not uninstall an unknown existing package
or disable phone protections to resolve a conflict. Camera permission and real
photograph capture remain under the user's control.

Automated UI tests use mock camera sources and synthetic photos; they do not
start a MOSS session. Run instrumentation only on an emulator or an explicitly
authorized test phone. The demo-specific route and device test have been
removed.

```powershell
& $adb -s $phoneSerial install -t .\android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
New-Item -ItemType Directory -Path .\evaluation\results -Force | Out-Null
$testResultPath = Join-Path .\evaluation\results ('android-ui-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
& $adb -s $phoneSerial shell am instrument -w -r -e class com.visualassistant.app.ui.CameraScreenTest,com.visualassistant.app.ui.MockCameraRouteTest,com.visualassistant.app.chat.PhotoEncoderTest,com.visualassistant.app.ui.PersistentPhotoChatFlowTest,com.visualassistant.app.ui.ConversationHistoryFlowTest com.visualassistant.app.test/androidx.test.runner.AndroidJUnitRunner > $testResultPath 2>&1
Get-Content -LiteralPath $testResultPath -Tail 30
```

Screen tests check camera controls and question submission; mock-route tests
cover camera failures and reconnection; persistence tests cover photo/chat and
history storage. These tests use mock camera sources and synthetic images, so
they do not replace real camera checks. MOSS is not started by these tests.

A zero ADB process exit code or `INSTRUMENTATION_CODE: -1` alone is not evidence
that tests passed.
If tests report no Compose hierarchies, first check that the phone is unlocked
with the screen on before changing app code. The 200% font test changes only its
Compose test environment; it does not change the phone's global font setting.
Reports stay in ignored `../evaluation/results`; no screenshots, photographs or
device-wide logs are collected.

Do not uninstall apps or clear data on the user's phone. Use a disposable
emulator or an explicitly authorized test device for instrumentation.

Manual acceptance checklist (record actual observations before checking items):

- [ ] Deny camera permission, then recover using Allow camera or app settings.
- [x] Capture a non-sensitive object, review photographs and retake; the user confirmed the images looked good.
- [ ] Capture in portrait and landscape; rotation preserves a completed review.
- [ ] Repeated taps do not start multiple captures or crash the app.
- [ ] Background during capture, resume and capture again; an interrupted capture
      leaves no abandoned file after its callback finishes.
- [ ] Background during review and resume: the photograph remains available.
- [ ] Retake removes the previous capture; a fresh process launch clears abandoned
      captures in the app's dedicated cache directory.
- [ ] Large text and TalkBack keep controls understandable and reachable.
- [ ] Capture/review/retake works in airplane mode with Wi-Fi off; restore the
      phone's previous connectivity and accessibility settings after testing.

For a debug build, inspect capture filenames without copying photographs:

```powershell
& $adb -s $phoneSerial shell run-as com.visualassistant.app ls cache/captures
```

Capture ownership and cancellation are handled by `FilePhotoCapture`, shared
with JVM tests. `CameraSource` and `CapturedPhoto` remain independent of CameraX
and future E12 communication. Photos are not written to the gallery or backed up
by the app. Submitted conversation photos have separate persistent private copies;
the chat loads bounded previews as needed. The OS may evict temporary cached
photos; the review screen then asks the user to retake them.

## Android code organization

The activity creates the dependencies and retained ViewModel, then displays the
camera route inside the app theme. UI coordination and permission handling are
composition-scoped, so the ViewModel never retains an activity or permission
launcher. The capture, review and retake screens retain their existing wording
and appearance.

Paths in this table are relative to
`app/src/main/java/com/visualassistant/app`:

| Component | Responsibility |
| --- | --- |
| `MainActivity.kt` | Activity setup, dependency creation and ViewModel acquisition; rotation retains the ViewModel. |
| `ui/VisualAssistantTheme.kt` | Existing light/dark colors and readable typography. |
| `ui/CameraRoute.kt` | Connect UI actions and ViewModel state; one lifecycle observer refreshes permission on resume and interrupts capture on stop. |
| `ui/CameraBinding.kt` | Injectable camera-preview binding. The phone binding remains the app's normal choice; alternate bindings can publish the same camera availability and `CameraSource`. |
| `ui/CameraPermissionState.kt`, `ui/CameraPermissionStatus.kt` | Permission launcher, settings action, persisted request history, and the Granted/Requestable/SettingsRequired decision. |
| `ui/CameraPreview.kt`, `camera/CameraAvailability.kt` | CameraX binding and disposal; publish Starting, Ready(source), or Unavailable as one consistent state. Errors retain the retry action until retry or reopening; disposed previews ignore late callbacks. |
| `ui/CameraScreen.kt`, `ui/PhotoReview.kt` | Stateless screen rendering and bounded photograph decoding. |
| `camera/CameraViewModel.kt`, `camera/CaptureStore.kt`, `camera/FilePhotoCapture.kt` | Capture/review state, private file ownership and cancellation cleanup. |
| `chat/LiveAssistant.kt`, `chat/PhotoEncoder.kt` | Send configured assistant requests and normalize photos without preserving EXIF metadata. |
| `mosslive/MossLiveSessionController.kt`, `mosslive/OkHttpMossLiveTransport.kt` | Manage MOSS session audio, events, interruptions and playback acknowledgements. |
| `chat/ConversationDatabase.kt`, `chat/ConversationRepository.kt`, `chat/ConversationPhotoStore.kt` | Room records, session management and private saved-photo lifecycle. |
| `chat/RecentTimelineReader.kt` | Read recent turns in bounded pages without splitting a turn. |
| `ui/ChatRoute.kt`, `ui/ChatScreen.kt`, `ui/ConversationWorkspace.kt` | Chat components, navigation coordination and deletion confirmation. |
| `ui/AssistantSidebar.kt`, `ui/SettingsInfo.kt` | Saved-chat navigation and informational settings presentation. |
| `ui/ChatMessageItems.kt` | Message bubbles and bounded saved-photo previews. |

`CameraSource.capture()` and `CapturedPhoto` keep the same device-independent
contract. MOSS Live is a separate voice-only subsystem; the normal assistant
continues to use its existing HTTP backend route.

## Developer-only mock camera

`app/src/debug` includes a mock camera and binding for development and
tests. The mock uses a generated JPEG supplied by its test; it never uses the
phone's camera or gallery and does not implement an E12 protocol. It simulates
successful capture, configurable transfer delay, a one-shot capture failure,
disconnection and reconnection. Captures use the same `CameraSource`,
`CapturedPhoto` and private `CaptureStore` contract as the phone camera.

The shared `CameraRoute` accepts a `CameraBinding`, which provides availability,
preview and whether the phone-camera permission is needed. The app's normal
entry point selects `PhoneCameraBinding`; the mock is injected only by tests.
A future E12 adapter can supply another binding and source without changing the
screen or assistant, but its real connection and transfer logic must wait for
the manufacturer's documentation and hardware.

The mock route test does not require the backend or USB reverse forwarding. With
the intended phone unlocked, after installing the app and test helper as described
above, run only this test:

```powershell
& $adb -s $phoneSerial shell am instrument -w -r -e class com.visualassistant.app.ui.MockCameraRouteTest com.visualassistant.app.test/androidx.test.runner.AndroidJUnitRunner
```

Inspect the output for `OK (1 test)`; the instrumentation exit code alone is not
sufficient. The test creates its own synthetic image and dedicated app-cache
directory, then removes only its own files. Remove the test helper only if this
session installed it. The app does not expose mock-camera controls to users.

## Historical Android verification status — 2026-11-05

Verified on this Windows computer on **2026-11-05**, after the Android refactor:

| Check | Result |
| --- | --- |
| `assembleDebug` | **Passed**; installable debug APK generated. |
| `testDebugUnitTest` | **40 passed**, zero failures/errors. |
| `lintDebug` | **Passed**, 0 errors and 9 warnings (previously 10). |
| `assembleDebugAndroidTest` | **Passed**; interface-test APK generated. |
| Packaged app permissions (`aapt dump permissions`) | Camera, internet for the USB demo, and AndroidX's internal signature permission; no network-state, microphone or storage permission. |
| Android Studio installation | Archive checksum verified and extraction completed; GUI sync not tested. |
| Phone installation | **Passed** on Samsung S24+ (SM-S926B), Android 17 / API 37; updated app installed with data-preserving installation. |
| Eight instrumented tests | **8 passed** on the unlocked S24+, Android 17 / API 37, including one synthetic-image USB demo transfer. |
| Refactored demo and phone regression tests | **11 passed** on the S24+, including simulated camera behavior, synthetic-image rotation/EXIF stripping, demo response validation and USB transfer. |
| Mock camera shared-route phone test | **Passed** on the S24+: simulated failure, successful capture, disconnect, retry and reconnection through the shared route. |
| Existing phone UI regression tests | **7 passed** on the S24+ after camera binding was introduced. |
| Release source compilation | **Passed**: `:app:compileReleaseKotlin` compiled without the debug-only mock. This is not a signed release build. |
| Test helper cleanup | **Completed**; only `com.visualassistant.app.test` was removed after recording results. Camera app remains installed. |
| Camera app launch | **Passed**; explicit launch of `MainActivity` returned `Status: ok`. Permission and real-photo checks are user-controlled. |
| Real camera and retake | **Passed after the camera binding refactor**, according to the user's report following the preview, capture, review and retake smoke test. The installed APK was verified to match the local debug build. |
| Rotation, TalkBack and airplane-mode checks | **Not run**; manual checklist remains unchecked. |

The 40 JVM tests cover eight camera ViewModel scenarios, four cache/storage
scenarios, seven file-capture scenarios, four permission-policy scenarios, and
four demo state/late-response scenarios, eight mock camera scenarios, and five
demo orchestration/transport scenarios. Existing
regressions check cancellation immediately after file allocation, cancellation
after saving but before delivery, successful file retention, synchronous/asynchronous
failures, and callbacks that arrive after cancellation without removing a newer
photograph. Permission coverage checks the first request, denial with rationale,
denial requiring settings, and grants taking precedence over request history.

Lint warnings remain visible: seven notices about newer dependency/tool versions
or the retained target SDK, plus Android 12 backup-rule guidance and a missing
custom launcher icon. The permission helper now uses the existing SharedPreferences
KTX extension, resolving the previous style warning. Photos use the cache directory,
which Android excludes from backup. Dependency upgrades and release polish are
separate work; no lint baseline or warning suppression was added.

The initial phone test run failed inside transitive Espresso 3.5.0 before exercising
the UI. Pinning the test-only dependency to 3.7.0 fixed the unavailable Android API
lookup. A second run found no Compose hierarchies while the phone was locked and
dozing. After the user unlocked it, all six tests passed without changing UI code
or the phone's lock, timeout, connectivity or accessibility settings.

Local instrumentation reports are retained under ignored `../evaluation/results`:
`android-ui-20261105-224534.txt` (dependency failure),
`android-ui-20261105-224800.txt` (locked-phone failure), and
`android-ui-20261105-225011.txt` (**OK (6 tests)**), and
`android-demo-20261105-231249.txt` (**OK (8 tests)**, including USB transport),
`android-mock-20261105-233218.txt` (**OK (1 test)**), and
`android-ui-regression-20261105-233251.txt` (**OK (7 tests)**), and
`android-refactor-20261105-235508.txt` (**OK (11 tests)**).

The build, computer-based checks and eleven current phone tests are complete.
An app-owned cache filename check observed the first nonempty camera file replaced
by a different nonempty file; no photograph contents were read or copied. Both
observed files exceeded the backend's 4 MiB image upload limit. The demo client now
resizes and compresses its copy before uploading and checks the resulting size.
The user confirmed that the captured images looked good;
rotation, background/resume and other manual acceptance checks remain pending. The
interface tests use simulated previews. No OpenAI requests or E12 communication were
performed in this milestone. No firmware changes, resets,
app-data clearing, other-app removals, or collection of photographs/screenshots/
device-wide logs were performed.

## Current backend setup

Use the maintained [backend README](../backend/README.md) for credentials,
provider configuration, running the FastAPI gateway, and backend tests. Keep
MOSS credentials only in the ignored backend `.env`; a normal build/test does
not open a provider session.

<details>
<summary>Archived backend setup and verification notes (pre-MOSS)</summary>

The following legacy instructions predate the current assistant and MOSS Live
gateway. They are retained as development history only; demo endpoints and
demo-specific tests described below have been removed.

## Archived setup in PyCharm

The configured interpreter is Python 3.13.3 in the project `.venv`. Run these
PowerShell commands from the repository root:

```powershell
& .\.venv\Scripts\python.exe -m pip install -r .\backend\requirements-dev.txt
```

Create configuration without overwriting an existing file:

```powershell
if (-not (Test-Path -LiteralPath '.\backend\.env')) {
    Copy-Item -LiteralPath '.\backend\.env.example' -Destination '.\backend\.env'
}
```

Open `../backend/.env` in PyCharm and set `OPENAI_API_KEY` locally. Keep the key
out of chat, screenshots, commits, and client applications. Git ignores this file.

| Setting | Purpose |
| --- | --- |
| `OPENAI_API_KEY` | Backend API credential; blank disables image answering. |
| `OPENAI_MODEL` | Model identifier; configured default: `gpt-6.1-terra`. |

Environment variables override `.env` values. The backend locates this file
relative to its source directory, independent of the terminal's working
directory. Restart the server after changing settings.

The server starts without a key. Health, documentation and `/v1/demo/turns` work;
a valid live `/v1/turns` request returns `503 ai_not_configured` until credentials
are configured. The demo route never calls the AI gateway.

## Run the backend

```powershell
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir .\backend --host 127.0.0.1 --port 8000 --reload
```

In another terminal:

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health'
```

Expected: `status` is `ok`. Health checks the process, not AI access or billing.
Open [interactive documentation](http://127.0.0.1:8000/docs) to try the API.
Swagger UI loads browser assets from a CDN and needs internet access.
`/openapi.json` serves the API schema directly.

Press `Ctrl+C` to stop. If port 8000 is occupied, stop and investigate rather
than replacing the existing service. The Android demo currently expects port
8000. The server binds to loopback, with USB forwarding for the phone.

For a PyCharm Python run configuration, use module `uvicorn`, parameters
`app.main:app --app-dir backend --host 127.0.0.1 --port 8000`, the repository
root as working directory, and the project `.venv`. Omit `--reload` while
debugging so the debugger stays attached to the serving process.

## Send a photograph and question

In `/docs`, expand `POST /v1/turns`, choose **Try it out**, enter a question,
select a JPEG or PNG, and choose **Execute**. Omit optional fields; disable
**Send empty value** for them if Swagger offers that checkbox. Successful AI
calls use the configured API account and can incur charges.

Alternatively, replace the photograph path in this PowerShell example:

```powershell
curl.exe --silent --show-error --max-time 45 -X POST 'http://127.0.0.1:8000/v1/turns' -F 'question=Which button changes the TV input?' -F 'image=@C:/photos/remote.jpg;type=image/jpeg'
```

For PNG use `type=image/png`. Send multipart form data, not JSON.

| Field | Required | Meaning |
| --- | --- | --- |
| `question` | Yes | 1-1,000 characters after trimming surrounding whitespace. |
| `image` | Yes | JPEG or PNG; at most 4 MiB and 20 million pixels. |
| `request_id` | No | Correlation UUID; generated when omitted. |
| `photo_id` | No | Photograph UUID included in completion logs. |
| `captured_at` | No | Timestamp with timezone, e.g. `2026-11-05T12:00:00Z`. |

The total request limit is 5 MiB, including multipart overhead. The backend
checks actual image format, rejects corrupt and animated images, applies EXIF
orientation, strips metadata, and downsizes proportionally to a maximum
1,600-pixel long edge without enlarging small images. It sends RGB JPEG at
quality 85; transparent areas become white. Fine labels may need a closer view.

Illustrative success response (not a recorded model result):

```json
{
  "request_id": "9b158860-f72f-4eeb-97cd-2df734af2724",
  "reply": "Press the visible INPUT button once.",
  "action": "do_step",
  "timings_ms": {"model": 1800, "server_total": 1840}
}
```

Actions are `answer`, `do_step`, `take_photo`, `clarify`, `complete`, and `stop`.
They describe replies and do not trigger hardware actions. The prompt asks for
one physical step at a time, clarification when evidence is insufficient, and
safe stopping for risky tasks. These instructions do not guarantee accuracy;
the live acceptance checks below assess grounding.

`model` measures the model call and parsing. `server_total` runs from request
receipt to before response serialization, including upload reception and
preprocessing. Neither measures phone capture or the full client round trip.
The model deadline is 30 seconds, with no automatic SDK retries and a maximum
output budget of 1,500 tokens.

Responses include `X-Request-ID`. Failures before field parsing use a generated
ID. Reusing a request ID does not deduplicate requests: sending again can cause
another billable model call. Photo IDs and capture timestamps do not establish
conversation history.

## Errors and troubleshooting

Errors share this envelope:

```json
{
  "request_id": "9b158860-f72f-4eeb-97cd-2df734af2724",
  "error": {
    "code": "model_timeout",
    "message": "The assistant took too long to respond. Please try again.",
    "retryable": true
  }
}
```

| HTTP status | Meaning and next action |
| --- | --- |
| 400 | Corrupt image or malformed multipart request; correct the upload. |
| 413 | Image, pixel count, or request too large; resize or crop. |
| 415 | Unsupported media, format mismatch, or animation; use a still JPEG/PNG. |
| 422 | Invalid field; check question length, UUIDs, and timestamp timezone. |
| 429 | Temporary rate limit; wait before manually retrying. |
| 500 | Unexpected backend error; locate its log using the request ID. |
| 502 | Provider connection failed or answer was incomplete/invalid; retry later. |
| 503 | Missing key, configuration/model-access problem, or exhausted quota; correct configuration or billing. |
| 504 | Model deadline exceeded; retry manually. |

Use `error.code` to distinguish errors sharing a status. Provider refusals
return a safe reply with `action=stop`, without raw refusal text. A timeout
can occur after the provider processed a request. `retryable` is guidance,
not an instruction for automatic retries or a guarantee of no charge.

## Privacy and logs

For live `/v1/turns` only, the question and normalized photograph go to OpenAI
over HTTPS. The separate `/v1/demo/turns` keeps them on the local phone/computer
connection and never calls OpenAI. No audio or
earlier conversation turns are sent. The backend has no photograph or
conversation database. Multipart parsing can briefly use OS temporary files;
upload handles are closed after processing, including failure paths.

Live model requests set `store=false` to disable response storage for later retrieval.
This does not guarantee zero retention: abuse monitoring and other documented
exceptions can still apply. See the official
[OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data).

Live-turn logs contain request/photo IDs, configured model, prompt version,
model/server timing, token counts when available, and error status/code/retryability.
Demo completion logs contain only the request ID and elapsed time.
Unexpected errors also log the exception class. They exclude question text,
photograph bytes, replies, credentials, and provider exception text/tracebacks.
Uvicorn also emits ordinary access logs. Keep questions in multipart fields
rather than URLs, and avoid SDK HTTP debug logging with private data.

Manual acceptance records intentionally retain selected questions and replies
for evaluation. Keep them and their source photographs in the ignored
directories below and remove them when no longer needed. The template contains
no real user data.

## Run automated tests

```powershell
& .\.venv\Scripts\python.exe -m pytest .\backend\tests -q
& .\.venv\Scripts\python.exe -m pip check
```

Tests use in-process clients and mocked provider transports. Outbound HTTP is
blocked, including when local credentials exist. Coverage includes upload
limits/cleanup, normalization, validation, SDK serialization, timeouts,
refusals, provider failures, client lifecycle, and log privacy. The installed
Starlette and OpenAI versions use HTTPX2.

Verified in the configured Windows/Python 3.13.3 environment:

- Automated tests: **74 passed**, most recently rerun offline on 2026-11-06.
- `pip check`: **No broken requirements found**.
- Started Uvicorn and checked `/health`, `/docs`, and `/openapi.json` over HTTP: **200**.
- HTTP upload checks: missing image **422**, corrupt image **400**, wrong request
  type **415**, absent credentials **503**, and oversized request **413**.
- Error response/header request IDs matched; server logs contained safe categories.
- Acceptance CSV parsed successfully with three `not_run` cases and no fabricated results.
- The earlier verification server shut down cleanly; no OpenAI calls were made.
  A separate loopback demo server may be running during the current USB session.
- Real-model image acceptance: **not run**; credentials and photographs are absent.
- Browser-rendered Swagger UI: **not tested** in this backend milestone.
- Android verification is tracked separately in the camera section above.

</details>

## Evaluation catalog

`../evaluation/case-catalog.csv` contains 25 planned cases: five TV remotes, five
appliance panels, four phone settings, four printed instructions, four error
messages and three unclear photographs. Every fixture is currently marked
`not_collected`; the catalog contains no photographs or model results.

Follow [evaluation/README.md](../evaluation/README.md) to match your own
non-sensitive photographs to the visible-evidence requirements, record ground
truth privately before testing and use the existing results template. A planned
case is not a pass, and task completion must be observed rather than inferred.

## Three-photo acceptance check

Use your own non-sensitive photographs in the ignored
`../evaluation/fixtures/private` directory. Inspect each source photo and write
its expected visible evidence before submission.

| Case | Pass criterion |
| --- | --- |
| Clear remote | Identifies a visible relevant input/source control and gives one useful step without inventing labels or positions. |
| Appliance panel or error | Reads visible controls/text correctly and gives supported guidance, asking for model details when meaning is uncertain. |
| Blurry or incomplete view | Requests a specific useful new view or clarification without inventing controls or text. |

Copy `../evaluation/acceptance-template.csv` into the ignored `evaluation/results/`
directory for each run. Initial rows are `not_run`. Record the actual reply,
request ID, human assessment, and timings. Copy token counts, model, and prompt
version from the matching completion log. Measure client elapsed time separately
if needed. Keep task completion `not_observed` unless someone actually performs
and verifies the physical action.

Grade instruction correctness and clarification appropriateness as `yes`, `no`,
or `not_applicable`; count unsupported instructions. Record every attempt in a
new row so failures remain visible. Mark `pass` only when the case criterion is
met with zero unsupported instructions; otherwise mark `fail`. Leave unmeasured
values blank rather than entering zero.

All three cases must pass before backend Milestone 2 is accepted. Offline Android
camera work can proceed independently while API access is unavailable. Fix failures
and repeat affected cases. These cases accept the initial integration, not broad
accuracy or commercial reliability. The larger 20-30-case evaluation and
multi-turn task-completion measurements come later.

## Modules and follow-up

| Module | Responsibility |
| --- | --- |
| `../backend/app/main.py` | Application factory, SDK lifetime, health, error handlers. |
| `routes.py`, `schemas.py` | Assistant REST contracts; the simulated demo route has been removed. |
| `middleware.py`, `errors.py` | Request limits/correlation and safe error responses/logs. |
| `images.py` | Image validation and normalization. |
| `openai_gateway.py`, `assistant.py` | Provider adapter and versioned grounding prompt. |
| `config.py` | Backend settings and resource limits. |
| `../backend/tests` | Offline automated verification. |
| `../evaluation/acceptance-template.csv` | Manual acceptance record template. |
| `../evaluation/case-catalog.csv`, `evaluation/README.md` | Planned cases and privacy-preserving evaluation procedure. |
| `app/src/main` | CameraX, Room-backed chat, normal assistant REST client, and separate voice-only MOSS subsystem. |
| `app/src/test` | JVM tests for state transitions, file ownership and cancellation. |
| `app/src/androidTest` | Interface tests that require an Android device or emulator. |

App module names above are relative to `../backend/app`. Real MOSS session and
phone microphone validation are still separate from offline build checks and
must be explicitly authorized because they may incur provider charges. The
phone's camera, microphone and speaker remain the current hardware interface
until the E12 SDK and protocol are available.
