# Visual Assistant: UI and app plan

This plan describes the phone-first product and how to build it in small stages.
It separates the working USB demo from future features. Last updated: 2026-11-06.

## Purpose and design principles

Help an older adult take a picture of an everyday object, ask a question and
understand the next safe action. The first real assistant will use typed questions
and text answers. Spoken interaction and E12 earphones come later.

- Use clear everyday words, large readable text and large touch targets.
- Put the main action near the top; keep secondary actions easy to find.
- Show one task and one instruction at a time. Keep content scrollable at
  large font sizes and on small screens.
- Tell the user what is happening and how to recover from a problem. Do not
  rely on color alone or on icons without text.
- Make demo results unmistakably simulated. Never imply that a demo answer
  understands the picture or that a disabled feature works.
- Protect privacy: do not send a photo or question until the user chooses to
  send it. Explain where it goes at that point.
- Validate with older adults, including people with different vision, dexterity
  and technology experience; large controls alone do not establish usability.

## Intended phone experience

1. **Capture:** show a short instruction, a prominent Take photo button, and
   the phone camera preview. Explain camera permission when needed; offer
   retry or settings only when relevant. Avoid automatic capture.
2. **Review:** show Retake prominently and the photograph for a clarity check.
   Ask for a typed question. The current version offers the existing USB demo:
   its explanation says the answer is simulated and that sending transfers a
   resized copy and question to the local computer.
3. **Response:** show progress during a request; display the simulated label
   and answer or a useful error. Preserve the question on failure so the user
   can choose to retry. Retake keeps the question and clears the old response.
4. **Later, real AI:** add a clearly labelled live choice only after backend
   live-image acceptance. Before sending, disclose that the photo and question
   go to the backend and AI provider. Use a bounded request, a visible cancel
   action, specific error states and no automatic retries. Cancellation cannot
   guarantee that a request already received by the service was not processed.
5. **Later, guided tasks:** show a short instruction, allow the user to say
   when it is done, and request another photo when verification is necessary.
   Do not claim a step is complete without evidence. Conversation state and
   verification are not implemented today.
6. **Later, voice and earphones:** add push-to-talk and spoken responses after
   the real image workflow is tested. The eventual device status and recovery
   screens must follow the actual E12 documentation and observed behavior.

The first live version is single-turn. It must request a clearer photo or
clarification when it lacks evidence, and stop or direct the user to suitable
help for risky tasks. UI wording and prompts cannot guarantee model safety.

## Screen states to design and test

| Situation | User-facing behavior |
| --- | --- |
| Camera starting, capturing or saving | Explain the activity; prevent duplicate capture. |
| Camera permission unavailable | Explain why access is needed and offer the appropriate permission or settings action. |
| Camera or private storage error | Explain the problem and offer a relevant retry. |
| Photo review | Show the photo, Retake and the question field; do not send automatically. |
| Demo request in progress | Show progress and prevent duplicate sends. Retake clears the old response and keeps the question. |
| Demo response | Label the response as simulated and keep the existing explanation that no AI analyzed the photo. |
| Demo connection error | Keep the question and explain the local computer/USB requirement. |
| Future live timeout, cancellation or error | Explain the outcome, allow a deliberate retry when appropriate and prevent an old answer replacing the current state. |
| Future unclear image or unsafe request | Present the model's clarification, new-photo request or stop message; do not infer success. |

## Implementation stages

1. **Reachable actions and an honest demo** (first UI change): move the capture
   and retake actions above large image regions, and label displayed demo answers.
   Keep the existing camera, USB demo, transport and permissions. Build and run
   existing unit tests; add UI checks for action ordering and the demo label.
2. **Review and question experience:** evaluate a clearer visual hierarchy,
   useful progress and error wording, and accessible large-text layouts with
   synthetic UI states. Keep the existing flow working and do not add a fake
   live button. Consider a phone-only simulated experience only as a separate,
   explicitly labelled and tested feature; retain the existing USB demo.
3. **Accessibility and usability:** inspect TalkBack focus and announcements,
   font scaling, small screens, contrast and rotation; observe a small number
   of consenting older adults completing the demo. Prioritize problems they
   actually encounter. Device checks need the owner's involvement and consent.
4. **Real AI integration:** once an API key is available, first verify the
   backend with a non-sensitive real photograph. Then add a labelled live path
   using the existing image preparation where practical. Test the clear remote,
   appliance/error and unclear-photo cases; retain the simulated demo.
5. **Speech, task progress and E12:** introduce these as separate milestones
   after their prerequisites are available. Keep hardware communication behind
   the existing camera interface and do not invent an E12 protocol.

## Boundaries and verification

The current app does not provide real AI answers, voice, persistent task memory
or E12 communication. The USB demo needs the local backend. The backend live
route exists, but its model access and grounding have not been verified.

For each implemented UI stage, run the applicable Gradle build and unit checks;
add focused interface tests where they prove behavior. Instrumented tests need a
device or emulator and are not considered passed merely because the test APK
builds. Any phone installation, permission prompt or real capture stays under
the user's control. Record actual observations and never treat a simulated reply
as an accuracy or task-completion result.
