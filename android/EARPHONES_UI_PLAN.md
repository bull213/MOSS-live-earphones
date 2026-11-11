# Earphones AI Assistant — Android UI Implementation Plan

Status: proposed design / implementation specification, 2026-11-06. Grounded in `PROJECT_HANDOFF.md` and earlier product goals. Not a claim that the UI is implemented or that AI/hardware integration works.

## Product experience

The product helps older adults complete everyday tasks independently. Core interaction: **See → Ask → Guide → Check**. The phone is the camera/microphone/speaker now; the E12 earphones become an alternative input/output source later. Design for voice-first, phone-accessible fallback, one actionable instruction at a time, user confirmation and optional new photo. Do not create a generic chatbot with a crowded transcript.

## Non-negotiable constraints

- No OpenAI key or real E12 required for UI work. No fake AI claims.
- Keep the existing Jetpack Compose + CameraX phone-capture functionality, cache rules, backend demo route and camera abstraction intact.
- Add a clearly labeled, deterministic **UI Preview** or **Simulated Guidance** mode, separated from future live AI. Simulated responses must never suggest that the uploaded photograph was analyzed.
- No accounts, payments, continuous video, always-on recording, proprietary Bluetooth assumptions or custom firmware.
- Do not reset the Git tree or modify phone settings. Respect users' control of camera captures and permissions.

## Navigation / screens

1. **Home (`HomeScreen`)** — headline “What would you like help with?”; one dominant “Get help” button. Brief example sentence. Current camera source label, e.g., “Using phone camera”. Optional “Continue task” only when active. Small Settings link. Do not display vendor jargon or protocol settings.
2. **Camera (`CaptureScreen`)** — reuse existing CameraX preview/shutter and error states. Show short framing instruction and clearly labeled shutter. Do not auto-capture until explicit action.
3. **Photo review (`PhotoReviewScreen`)** — large captured image, two obvious actions: “Use photo” and “Retake”. Preserve existing photo cache and retake cleanup semantics. Tell users that a photo is not sent to an AI service until they submit a request in live mode.
4. **Question (`QuestionScreen`)** — thumbnail, “What would you like help with?”, multiline text field, “Get guidance” button. Leave room for a later “Speak” action; do not show it as functioning before implementation. A typed question is the MVP fallback.
5. **Guidance (`GuidanceScreen`)** — large heading “Try this next” and one short step (not a full chatbot transcript); primary “I’ve done it” button; secondary “Take another photo” and “Ask a follow-up” actions; “End task”. User confirmation does not count as visual verification. Optional “Repeat aloud” only when TTS is implemented.
6. **Needs better view (`RetakePromptScreen`, can be a state within Guidance)** — “I need a clearer photo to help with that”; “Take another photo”. No guessed advice.
7. **Completion (`TaskCompleteScreen`, can be a state within Guidance)** — “All done?” with “Finish” and “Keep working”. Avoid claiming success merely because a button was tapped.
8. **Settings (`SettingsScreen`)** — minimal local display preferences, e.g., larger text and slower speech only when audio is implemented; privacy information. No E12 pairing UI until protocol known.

## The only critical flow to build now

Home → Camera → Photo review → Question → Simulated Guidance → (Another photo / Ask follow-up / Finish) → Home.

The default app can remain explicitly in demo/development mode while there is no AI key. Future live mode uses the same screens and a real assistant provider. Source and assistant transport are independent decisions.

## Suggested Compose components

- `PrimaryActionButton` (height 56–64dp, visible text label)
- `AppScreenHeader` (large title; simple back action)
- `SourceStatus` (“Using phone camera”, later “Earphones connected”)
- `CapturedPhotoCard`
- `GuidanceStepCard`
- `TaskActionRow` or vertically stacked actions
- `SimulatedModeBanner` (persistent and explicit)
- `AppErrorPanel` (plain-language issue + recovery action)

Use large readable typography: around 28–32sp heading, 18–20sp body, 18sp primary button. Respect system text scaling; content must scroll at large font sizes. Meet at least 48dp touch targets (prefer larger). Ensure strong contrast, native Compose semantics/TalkBack, and labels that do not rely on icons or color alone. No infantilizing visual motifs or mandatory tutorials.

## Implementation strategy respecting existing code

- Inspect actual `CameraViewModel`, `CaptureStore`, `CameraBinding`, `CameraRoute`, `CameraScreen`, `PhoneDemoAssistant` and transport classes first. `PROJECT_HANDOFF.md` is a snapshot; README and source are authoritative.
- Reuse current camera and photo-review components rather than replace the camera architecture.
- Add one small UI state/controller (`Idle`, `Capturing`, `Reviewing`, `EnteringQuestion`, `Waiting`, `ShowingStep`, `NeedsNewPhoto`, `Error`, `Finished`). The Android UI should consume an assistant service abstraction (not direct network calls from composables).
- Implement `SimulatedGuidanceProvider` locally with fixed clearly labeled guidance strings **independent of the actual photo contents**. Keep the USB demo route and relevant tests unchanged. Do not pretend to interpret a photo.
- When live API is available, add a separate `LiveGuidanceProvider` wired to the existing `/v1/turns` endpoint after checking actual response `reply`/`action` semantics; do not invent response enum values.
- When E12 protocol arrives, implement its camera source behind existing `CameraSource` / `CameraBinding`. No user-facing Bluetooth screens now.
- Never store real images or conversation history beyond what is required for the current task without explicit user consent.

## Build milestones (small, testable)

**UI-1: Design system + Home + navigation.** Preserve existing capture screen. Confirm home action opens camera, back navigation works, large text does not clip.

**UI-2: Photo review + Question experience.** Reuse camera, cache and retake. Add typed question input and clear “Get guidance” state. Confirm a user-controlled real capture displays, retake works; no new network dependence.

**UI-3: One-step Guidance with clearly simulated responses.** Add static demonstration of a task step, “I’ve done it”, request another photo, follow-up, finish, loading and missing-photo/error states. Keep the mode banner visible. Test the basic flow once on phone with user participation.

**UI-4: Polish only critical UX issues.** Large type/contrast/TalkBack labels; handle rotation and app pause without losing or exposing user photos; minimize permission prompts. No broad testing campaign.

**Later (not now):** live AI adapter → push-to-talk/TTS → bounded multi-turn guidance → real E12 protocol and camera/audio → evaluation with users.

## Smallest acceptance check

1. On Android phone, tap Get help, take a real photo, review it, and type a question.
2. See an unmistakably simulated one-step guidance screen and choose another photo, follow-up, or finish.
3. Close/reopen app or rotate it without a crash or exposing photos in the gallery.

Important: These tests prove the *interface* only, not image recognition or E12 support.

## Suggested first instruction to coding assistant

Read `PROJECT_HANDOFF.md`, `README.md`, and inspect the existing Android UI/camera classes. Preserve current project files and tests. Implement **UI-1** only: a readable Home screen with one “Get help” action that navigates into the current working camera screen, plus a simple visual design system. Do not add new network calls or simulate image interpretation. Run the minimal relevant build/test; ask the user to operate the phone for manual camera checks. Stop and summarize exactly which files changed before proceeding to UI-2.
