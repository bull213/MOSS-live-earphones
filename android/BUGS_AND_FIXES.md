# Bugs and troubleshooting record

**Updated:** 2026-11-10  
**Scope:** Known program errors, user-observed behavior, fixes attempted, and unresolved questions. This is an evidence log, not a claim that every item is fixed.

## Status at a glance

The clearest current bug is in **MOSS Live reply completion**. The phone has transcribed speech correctly, but the user later saw two assistant chat bubbles marked **“Partial reply (connection ended early)”** with truncated text: **“I heard you clearly”** and **“The capital of France is”**. Those fragments are incomplete and should not be treated as answers.

Earlier user reports described truncated assistant replies. Two live sessions reproduced the first failing boundary: provider audio reached Android and AudioTrack accepted bytes, but playback head stayed at zero and no acknowledgements were sent. AudioFlinger reported an underrun. Android documentation identifies the default streaming start threshold as the full AudioTrack buffer capacity. The player had a 9,600-frame device buffer while processing chunks one at a time and waiting for each chunk to play before writing the next, so an initial chunk below that threshold could not start playback. After setting a 1-frame start threshold, subsequent live sessions completed playback and acknowledgements. This is strong reproduced before/after evidence for the playback stall. The earlier historical partial bubbles were not captured with these diagnostics, and final chat display was not independently verified on this run.

## Evidence labels

- **Observed:** directly reported by the user or recorded by a diagnostic check.
- **Confirmed; live reproduced and corrected:** device diagnostics identified the failure boundary, and a change was verified with live playback and provider acknowledgements. Historical incident attribution and chat display remain unverified.
- **Offline verified:** current source compiles and local tests pass; device/live verification is recorded separately.
- **Suspected:** plausible explanation that still needs evidence.
- **Not implemented:** known product gap, not a regression.

## Bug and issue register

| ID | Status | Symptom / evidence | What was tried or changed | Remaining question |
| --- | --- | --- | --- | --- |
| MOSS-01 | **Playback stall reproduced and live-verified after correction; historical chat symptom not fully traced** | Before the latest change, two live runs received provider audio (192,000 bytes each), but AudioTrack playback head remained at frame 0 and no chunk acknowledgements were created. AudioFlinger logged the track being removed after underrun. The user's earlier partial replies remain the incident symptom. | Android docs state the default streaming start threshold is the full buffer capacity. On this device, AudioTrack capacity was 9,600 frames; player wrote one current chunk and waited for it to play before writing the next. Set the threshold to 1 frame, with a logged capacity/threshold. On the updated APK the device logged threshold 1/capacity 9,600; one live session finished 166/166 chunks and sent two `playback.completed` controls, and a subsequent session finished 101/101 chunks and sent three `playback.completed` controls. The backend counters matched the Android control counts. | This strongly confirms the reproduced playback failure and verifies the threshold correction live. Output route was device type 2 (built-in speaker), not a headset. The exact earlier user-reported fragments lack a retained trace, and final chat bubbles were not visible in the captured screen. |
| MOSS-02 | **Observed in an earlier run; not reproduced after latest edits** | A direct test reportedly produced an input transcription but no assistant audio/reply. Another diagnostic session reached “MOSS is listening” without a verified spoken answer. | Read the official MOSS Live realtime protocol and compared the expected attach/configure/media/audio event flow with the app and backend. Verified session attach/configuration separately from audio behavior. | Whether the upstream generated a reply in those sessions, and whether reply events traversed the backend and Android, remains unproven. |
| MOSS-03 | **Observed; operational cause found** | The app earlier showed a generic USB/backend connection error while the phone was connected. | Checked the local health endpoint and ADB reverse mapping. The backend process was stopped. Restarting Uvicorn on loopback restored HTTP health; the phone then reached the listening state. A Windows launcher was added to verify one authorized device, restore `adb reverse tcp:8000 tcp:8000`, start the backend if needed, and open the app. | A healthy HTTP backend only proves the local process responds. It does not establish MOSS credentials, provider reply generation, or audio playback. |
| MOSS-04 | **Playback boundary confirmed on device; corrected live** | Pre-fix runs logged AudioTrack started, accepted 14,400 bytes toward a 4,800-frame chunk, but playback head remained zero and no chunk completed. AudioFlinger reported an underrun. | Configured and logged the actual streaming start threshold. After correction, Android recorded 166 and 101 chunks finished; backend recorded corresponding 166 and 101 forwarded `playback.chunk.completed` controls. | The user could not confirm audibility because the phone's sound setting was low. Output route was built-in speaker; final chat bubble visibility is also not independently verified. |
| MOSS-05 | **Offline verified; device persistence unverified** | Earlier code could expose or persist partial assistant text and confusing partial-reply bubbles. | Final transcript persistence remains final-only; an offline repository/controller regression confirms a final transcript persists independently of playback acknowledgements and an interruption fragment is not saved as a final row. Assistant identity uses the provider `(run_id, turn_id)` pair. | No phone run confirms UI/Room behavior on device. |
| MOSS-06 | **Offline verified; physical playback unverified** | Long or malformed audio streams could put pressure on playback and fallback buffers; queue rejection could be mistaken for a complete turn. | Bounded playback/reply-audio queues mark rejected turns incomplete. Fake-track tests cover partial writes, playback-head completion, early audio-done, interruption with queued audio, malformed/rejected audio, and response transitions on a reused track. | These tests validate controller/accounting logic, not speaker output or provider acceptance. |
| MOSS-07 | **Observed after PyCharm import; launcher diagnostics hardened** | `Start MOSS Live.cmd` failed with "Expected one authorized ADB device, found 0". Running the backend manually did not allow MOSS Live to connect. | 1) The phone relies strictly on `adb reverse tcp:8000 tcp:8000`; starting the backend directly in PyCharm without an active ADB reverse tunnel fails because phone loopback cannot reach the PC. 2) Hardened `Start MOSS Live.ps1` to detect the bundled Android SDK adb binary, auto-restart the ADB daemon if wedged, and provide distinct diagnostics for unauthorized devices (unlock screen and approve prompt), offline devices, and missing USB connections. | Confirm device authorization prompt on physical phone. |
| MOSS-08 | **Acoustic feedback confirmed on speaker; resolved with software echo suppression** | On built-in speaker, MOSS Live cut off prematurely after ~0.3s (4 audio chunks), leading to aborted assistant replies. In contrast, running with Bluetooth earphones (Test 1) completely eliminated the cutoff, allowing natural multi-turn conversations. | Root cause was acoustic feedback: speaker audio re-entered the adjacent phone mic, triggering MOSS cloud Voice Activity Detection (VAD) barge-in interruption. Fixed by: 1) Audio route detection via `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` distinguishing headsets/earphones from physical speakers; 2) Zeroed 16 kHz PCM frame transmission during speaker playback with a 200ms room reverberation decay grace period to prevent VAD false alarms; 3) Expanded `RealtimeAudioSource` to support Bluetooth SCO, wired, and USB headset mics alongside `TYPE_BUILTIN_MIC`; 4) Added `echo_suppressed_frames` diagnostic metric. Full-duplex barge-in is preserved when earphones are used. | Live user verification with built-in speaker after installing updated APK. |
| APP-01 | **Not implemented** | Camera streaming to MOSS is unavailable. | Kept MOSS Live voice-only because the exact video-mode event payload is not verified. | Requires protocol verification and a separate implementation; it is unrelated to the current missing/truncated voice reply. |
| HW-01 | **Not implemented** | The intended E12 earphones are not integrated. The phone currently supplies camera, microphone and speaker. | No device commands or protocol have been invented. | Requires manufacturer SDK/protocol documentation and hardware. |

## Troubleshooting history

1. **Local connection was confused with provider response.** When MOSS Live did not appear to run, checks found the backend was not running even though the phone was USB-connected. Starting the local backend and restoring ADB reverse got the app to its listening state. This fixed the local availability problem, not the reply-generation problem.
2. **Speech transcription was confirmed separately.** The user reported an accurate question transcription in chat. That proves the input speech path can work; transcription does not imply a model response was generated.
3. **The realtime API documentation was checked.** The initially shared documentation covered speech transcription/TTS/VL concepts rather than the MOSS Live realtime event lifecycle. The official realtime guide was then consulted. Expected areas included session attach/configuration, media mode, microphone state, input audio, response transcript/audio events, response completion/interruption, and playback acknowledgements.
4. **A successful setup/handshake was not treated as a successful conversation.** Prior checks established that the configured backend could authenticate/configure a session. A separate direct test reportedly transcribed audio but did not yield assistant audio. Handshake success is not evidence that the model generated a complete answer.
5. **Chat delivery and partial persistence were adjusted.** MOSS final transcript events use the same conversation repository/timeline as other chat messages. Interim captions are transient; finalized messages are persisted. Interrupted fragments are not intended to be saved as final assistant answers.
6. **Playback and buffering were hardened.** AudioTrack playback and acknowledgements are tracked; queue limits and bounded reply PCM buffering were added. The latest refactor also marks a turn incomplete when the playback queue rejects audio.
7. **Protocol and Android audio contracts were checked against official references.** `response.audio.context` associates the next binary chunk; `response.audio.done` means provider audio has ended but local playback may remain; `playback.chunk.completed` follows actual full chunk playback; `playback.completed` follows audio-done plus queue drain. `playback.stopped` has `action`, `run_id`, `turn_id`, and optional/recommended `played_audio_ms`; no `chunk_id` is documented. `output.transcript.done` is independent final text keyed by run/turn. Android documents that streaming begins after the start threshold is buffered and that the default is buffer capacity. See [MOSS Live scenario](https://platform.moss.cn/en/docs/scenarios/moss-live/), [MOSS Realtime API reference](https://platform.moss.cn/en/docs/reference/moss-live), and [Android AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack#setStartThresholdInFrames(int)).
8. **Offline verification completed.** Android build, 83 local JVM tests, lint, and instrumentation APK compilation pass. Backend suite: 150 passed, 1 skipped. The baseline Android run had one pre-existing diagnostics-summary expectation mismatch; it was updated for the existing `controls=` field.
9. **Live playback verified after threshold correction.** Two post-change sessions forwarded matching chunk and response completion acknowledgements. Backend remained bound to `127.0.0.1`; the APK was updated with `adb install -r`. No app data was cleared. The microphone session was stopped afterward. The app screenshot showed no active session, but did not expose the newly generated assistant bubbles in the visible portion, so chat presentation still needs confirmation.

## How to isolate the remaining MOSS failure

Use one short, deliberate session only when authorized. Do not log transcript text, audio bytes, API keys, or full provider payloads. Collect event names/counts and timing only.

| Evidence from one session | What it narrows down |
| --- | --- |
| No input audio frames/uplink count | Android microphone capture or phone-to-backend transport |
| Input transcript event arrives, then no response-start/transcript/audio events | Upstream turn generation, session configuration, audio framing or turn detection |
| Response-start/transcript event arrives but no response completion | Upstream interruption, relay close, timeout, or response lifecycle handling |
| Binary audio contexts arrive but no binary chunks | Relay ordering/framing or upstream media delivery |
| Audio chunk reaches Android but no write/playback-head progress | Android AudioTrack/route/playback issue |
| Chunk plays but no acknowledgement is sent/accepted | Playback completion/ack control path |
| Final transcript event is received but there is no saved bubble | Android transcript collector, Room upsert, or timeline refresh |
| Only interim/interrupted transcript arrives | The response was not complete; do not save it as a normal final answer |

The backend event-count log and Android `MossLive` diagnostics should be correlated by session time. Do not infer the cause from the chat bubble alone. If no useful new evidence will be collected, do not repeat a provider session.

## Relevant code

| File | Bug area |
| --- | --- |
| `android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveSessionController.kt` | Provider event handling, transcript finalization, fallback, session cleanup |
| `android/app/src/main/java/com/visualassistant/app/mosslive/MossAudioPlayer.kt` | AudioTrack writes/playback-head tracking and chunk/response acknowledgements |
| `android/app/src/main/java/com/visualassistant/app/mosslive/MossReplyAudioBuffer.kt` | Bounded PCM retention for complete fallback transcription |
| `android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveProtocol.kt` | Android event parsing and protocol/control message creation |
| `android/app/src/main/java/com/visualassistant/app/mosslive/MossLiveTransport.kt` | Phone-to-backend WebSocket transport |
| `android/app/src/main/java/com/visualassistant/app/chat/ChatViewModel.kt` | Final transcript collection and persistence into the chat |
| `backend/app/moss_live.py` | Backend-to-MOSS connection and event relay |
| `backend/.data/moss-live-actions.log` | Sanitized backend event/action counts when present; never place secrets or content here |

## Verification record

- Android (from repository root, documented README environment): `& .\android\gradlew.bat --no-daemon --max-workers=4 --gradle-user-home .\android\.gradle-user-home -p android assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` — **passed**. This compiled the app, ran 83 local JVM tests, ran lint, and compiled the instrumentation APK; it did not run instrumentation tests on a device.
- Backend (from `backend/`): `..\.venv\Scripts\python.exe -m pytest -q` — **150 passed, 1 skipped**. Provider transport is mocked; this does not call MOSS.
- Baseline before edits: backend 150 passed/1 skipped; Android app and instrumentation APK compiled and lint passed, while one existing diagnostics test failed because it expected an old summary string without `controls=`. The updated test now checks the current documented diagnostics fields.
- The app was updated in place using `adb install -r`; no phone setting or app data was cleared. Live sessions were run with the user's authorization. Physical playback completion is established by the AudioTrack playback-head checks and matching provider acknowledgements; whether the user actually heard the answer should be confirmed directly.

## Other project limitations (not current bugs)

- Normal assistant provider calls and MOSS Live are cloud services and may incur charges.
- The backend is a local development service, not production-secured; keep it bound to loopback.
- Android on-device Voice transcribes locally and does not automatically ask the assistant.
- The local book library, OCR, and approved methods are separate from the MOSS Live voice path.
- E12 earphone integration, MOSS camera/video streaming, wake words, speaker identity, and production user/authentication infrastructure are not implemented.

For project architecture and general setup, see [PROJECT_HANDOFF.md](PROJECT_HANDOFF.md), [Android README](README.md), and [backend README](../backend/README.md).
