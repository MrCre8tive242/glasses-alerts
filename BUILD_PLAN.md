## STATUS
Phase: DONE (pending first on-device build)
Next model: Opus (for fixes from real-device test results)
Last updated by: Opus 5.5 (refine)
Current step: n/a

# Glasses Alerts v2: WhatsApp Business calls, quick replies, voice notes

## Goal
Android app for one user (Hervin) who runs WhatsApp Business (`com.whatsapp.w4b`) and wears
Meta Ray-Ban Gen 2 glasses. Glasses = Bluetooth headset. The app:
1. Reads WhatsApp Business messages aloud (v1, done).
2. Announces incoming WhatsApp Business calls and lets the wearer answer/decline with glasses taps.
3. Lets the wearer send pre-set quick replies with glasses taps (no dictation, so no typos reach customers).
4. Plays incoming voice notes through the glasses with one tap.

## Stack
Kotlin, Android Studio project, AGP 8.7.3, Kotlin 2.0.21, minSdk 30, compileSdk/targetSdk 35.
No third-party libraries. Framework APIs only: NotificationListenerService, TextToSpeech,
MediaSession (glasses tap = media button), RemoteInput (reply), MediaPlayer (voice notes).
Pure logic lives in package `com.glassesalerts.logic` with NO Android imports, so it can be
tested on a plain JVM (`logic-test/`).

## File tree
- `app/src/main/java/com/glassesalerts/logic/Classifier.kt`: Classifier, Speech, QuickReplies, QuietHours, ButtonMap, VoiceNotePicker
- `app/src/main/java/com/glassesalerts/logic/StateMachine.kt`: ActionStateMachine, Pending, Effect, Button
- `app/src/main/java/com/glassesalerts/AlertListenerService.kt`: wires everything to Android
- `app/src/main/java/com/glassesalerts/VoiceNoteFinder.kt`: finds newest voice note file in WhatsApp Business media folder
- `app/src/main/java/com/glassesalerts/ButtonLog.kt`: remembers last glasses button for the test screen
- `app/src/main/java/com/glassesalerts/Prefs.kt`: settings
- `app/src/main/java/com/glassesalerts/MainActivity.kt`: settings screen
- `logic-tests/LogicTests.kt` and `logic-test/`: JVM test runner for the logic package

## Interaction design (glasses taps = Bluetooth media keys)
Tap = PLAY/PAUSE (primary). Double tap forward = NEXT (secondary). Triple tap = PREVIOUS (cancel).
- Call rings: "WhatsApp Business call from X. Tap to answer. Double tap to decline." (repeats twice)
- Message arrives: read it, then "Tap to reply." Tap -> "Reply 1 of N: ..." Tap sends, double tap cycles, triple tap cancels.
- Voice note arrives: "Voice message from X. Tap to play." Tap plays it.
- Pending actions expire after 45 s. While nothing is pending the app does NOT capture media buttons.

## Rules for the builder
Do not change the stack, file tree or interfaces. If something cannot work, log it in Handoff Notes.

## Build steps
- [x] 1. Pure logic + tests. Done when: `logic-test` run prints ALL PASSED.
- [x] 2. Prefs, manifest, gradle (minSdk 30, all-files permission).
- [x] 3. Service: classify, calls (answer/decline), speech, de-dup.
- [x] 4. MediaSession tap handling + button test mode.
- [x] 5. Quick replies via RemoteInput.
- [x] 6. Voice note finder + player.
- [x] 7. MainActivity settings UI.
- [x] 8. README + zip.

Test status: step 1 verified (69 checks passed, compiled with kotlinc 2.0.21 from the JetBrains GitHub release).
Steps 2-7 are written but NOT compiled or run: this workspace has no Android SDK and the android.jar download was blocked.

## Refinement targets (for Opus)
- Confirm how Ray-Ban Meta Gen 2 taps arrive (which key codes) using the in-app button test; adjust ButtonMap.
- Android 14+ background-activity limits on firing the WhatsApp "Answer" PendingIntent.
- MediaSession priority vs the Meta AI app (it may steal media buttons).
- Voice note folder path / extension on current WhatsApp Business versions; localized "voice message" text.
- Optional: dictated replies, speech-to-text of voice notes (privacy implications).
- Optional: foreground service for reliability, contact allow/deny lists.

## Handoff Notes
- Deviation: planning and building were both done on Sonnet (user asked for it). No design changes during the build.
- Android-side code (service, finder, UI) has never been compiled. First thing in Android Studio: Sync + Build, fix any compile errors.
  Most likely trouble spots: `ActivityOptions.setPendingIntentBackgroundActivityStartMode` (API 34), `Notification.WearableExtender`, local helper functions named `check`/`button` in MainActivity.
- Answer/Decline: primary source is the CallStyle extras `android.answerIntent` / `android.declineIntent`; fallback is action titles in English/Spanish/Portuguese/French/German. Unverified against the real WhatsApp Business call notification.
- Tap mapping is a guess (tap = play/pause, double tap = next, triple tap = previous). The in-app "Test glasses buttons" screen exists to confirm it on real Ray-Ban Meta Gen 2.
- Replies are quick-reply only (chosen by taps). No dictation, deliberately.
- Voice notes need "All files access". Path assumed: Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes/<yyyyww>/*.opus. Only works if WhatsApp has auto-downloaded the note.
- Voice note detection is text based (microphone emoji or English/ES/PT/FR/DE "voice message" wording); other languages are missed.
- Files most in need of review: AlertListenerService.kt (everything Android-facing), VoiceNoteFinder.kt.
- logic-test/ needs the Gradle Kotlin plugin from Maven Central (blocked in the build workspace). It runs fine on a normal machine: `cd logic-test && gradle run`.

## Refinement Log (Opus 5.5)
1. **Glasses taps could miss the app (high).** Android routes headset buttons to the app that most recently played audio itself; TTS audio plays from the TTS engine's process, not ours. Added `claimMediaButtons()`: plays 250 ms of silence from our own AudioTrack whenever the MediaSession becomes active.
2. **Call answer blocked in background (high).** Added SYSTEM_ALERT_WINDOW ("Display over other apps"), which exempts the app from background-activity-start limits, plus a settings button and status line. After a tap-to-answer, if the ringing notification is still there 5 s later the glasses say "The call did not connect. Please answer on your phone."
3. **Old messages re-read / own reply read back (medium).** New pure `logic/AnnounceFilter.kt` replaces the 30 s time window: per-chat last-text memory, ignores notifications older than 2 min, ignores our own quick reply echoed into the chat for 15 s, forgets a chat when its notification is dismissed. 10 new checks.
4. **Second message in a row skipped (medium).** WhatsApp can set the plain text to "2 new messages"; the service now reads the newest entry of `EXTRA_MESSAGES` first and falls back to `EXTRA_TEXT`.
5. Quiet-hour fields now save while typing (previously only on focus loss, so changes were often lost).
6. Settings screen respects Android 15 edge-to-edge (`fitsSystemWindows`).
7. `tools:ignore="ScopedStorage"` on MANAGE_EXTERNAL_STORAGE so lint does not fail a release build.
8. Added `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.9, required by AGP 8.7) so Android Studio syncs without asking. The wrapper jar could not be generated offline; Android Studio does not need it.

Verification: logic package 79/79 checks pass (kotlinc 2.0.21). Android files compiled with kotlinc without the SDK: zero syntax errors (only expected unresolved Android-class errors). Still not built against the real Android SDK.

## Still open (needs the phone)
- Real tap key codes from Ray-Ban Meta Gen 2 (use in-app button test).
- Whether the Meta AI app grabs taps before us.
- Exact WhatsApp Business call notification shape (answer intent present?).
- Voice note folder path on the current WhatsApp Business version.
- Two voice notes of the same length in a row from one chat are treated as a repeat (text identical). Minor.
