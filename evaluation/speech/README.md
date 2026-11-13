# On-device speech evaluation

These are proposed acceptance checks for the Android Moonshine Small Streaming
integration. The emulator tests exercise the real model with one public audio
fixture, but they do not establish phone accuracy, user-perceived latency,
power consumption or long-term stability. Record results on the intended phone
before declaring these targets met. The model provider's published benchmarks
are separate measurements and do not predict this app's performance on a phone.

## Test setup and privacy

- Record the app build, Moonshine SDK and model revision, phone model, Android
  version, available storage, battery level, temperature, audio route and whether
  the device is charging. Keep the phone microphone at a normal speaking distance.
- With participants' consent, collect reference speech from at least three
  English speakers with different speaking styles. Use 60 short commands and
  30 conversational utterances in a quiet room, plus 30 utterances with ordinary
  background noise and 30 with a television playing. Include pauses, corrections,
  names and punctuation in the human reference. Document how noise and television
  volume are set so a later run can reproduce them.
- Do not commit recordings or transcripts containing personal information.
  Temporary evaluation material belongs in ignored `evaluation/fixtures/private/`
  and local reports in ignored `evaluation/results/`. Obtain consent, minimize
  what is recorded, and delete the material when it is no longer needed.
- The participant controls real-device permissions and connectivity. Verify
  transcription with the network disconnected after the app is installed; on-device
  Moonshine speech recognition operates 100% offline without network or computer connectivity.

## Accuracy and interaction

For each utterance, save a human reference and the final transcript. Normalize
case, whitespace and punctuation consistently before comparison. Count word
substitutions, deletions and insertions using an alignment, then report
`WER = (substitutions + deletions + insertions) / reference words`, aggregated
by condition and speaker. Also report the fraction of short commands whose
normalized final transcript exactly matches the reference. If intended actions
are evaluated separately, state the mapping and report intent accuracy separately;
there is no automatic command execution in this app.

Initial targets, to be reviewed after a real-device baseline:

| Measure | Initial target | Conditions |
| --- | --- | --- |
| Word error rate | At most 15% | Quiet commands and conversational speech, reported separately |
| Exact command transcription | At least 90% | Quiet short commands |
| End of speech to final message | Measure median and 95th percentile | Includes the two-second pause detection and model loading |

The noisy and television conditions are exploratory: report their WER, complete
misses and unwanted transcriptions separately. Television speech can itself be
recognized; speaker identification and filtering are not implemented.

Measure latency with synchronized, monotonic timestamps: mark the last spoken
word in the audio and the final message appearing in the UI, then subtract. Use
an instrumented test harness or a synchronized recording reviewed afterwards;
do not infer end-of-speech from the SDK's final callback. Report sample count,
median, 95th percentile, worst case and cold model preparation time. The app does
not display or generate interim transcripts. Keep the model version and test
material fixed for comparisons.

Check each of the following on the phone:

1. Speak a short command, a long sentence and several utterances in one session.
   Observe no transcript during speech and one saved message per sequence after
   the pause, without repeated boundary words or duplicates. A detected pause of
   roughly two seconds defines a new sequence.
2. Stop while speaking, restart voice repeatedly, leave the screen, background
   the app and interrupt recording with another microphone-using app. Confirm
   recording stops, the user sees errors or unconfirmed text when appropriate,
   and any offered Save or Discard action works.
3. Deny microphone permission, deny it permanently, restore it through Android
   settings and revoke it between sessions. Confirm the app can recover without
   requiring a reinstall. Check behavior when the microphone route is unavailable
   or storage is too low to prepare the model.
4. Restart the app and reopen a saved conversation. Confirm finalized voice text
   persists, the typed draft remains independent, and unsaved recent messages
   still follow the existing retention policy. Take and review a photo, submit a
   typed demo question, and verify saved conversations still work.

## Resource and stability measurements

Measure memory, CPU and battery on the actual phone after model preparation:

- Record process PSS and native heap after idle startup, after model load, and
  during and after at least 30 minutes of repeated speaking and pauses. Record
  crashes, audio overruns, dropped transcripts and memory growth. Investigate a
  sustained rise rather than treating an emulator-only test as a phone soak.
- Compare a 30-minute active-listening run with a 30-minute idle foreground
  baseline under matching screen brightness, connectivity, temperature and
  charging conditions. Record CPU use, battery percentage or energy estimates,
  and thermal behavior. Repeat if conditions differ materially; these estimates
  are too coarse to support claims from a single short run.
- Capture the start-to-final latency distribution during the soak and note any
  slowdown or Android microphone interruption. Keep audio and transcript contents
  out of routine logs and performance reports.

The automated native-memory test feeds five minutes of silent audio through the
JNI bridge quickly on the emulator. It detects the known unreleased-buffer
regression; it is not a substitute for a real-time phone soak or battery test.
