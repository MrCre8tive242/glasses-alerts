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

## Refinement Log 2 (Sonnet 5.5, after first real-device test)
Device findings: single tap on Ray-Ban Meta Gen 2 sends MEDIA_NEXT (fixed in the previous commit). Messages, calls and voice notes all reached the app. Complaints: long delay before a voice note plays, late call announcement, slow response after a tap, and a request for spoken replies.
1. Instant feedback: every accepted tap plays a short beep (ToneGenerator) and cuts off any speech in progress, so the answer to a tap is heard first.
2. Calls: announcement is one sentence ("...call from X. Tap to answer."). Calls are now announced even if WhatsApp posts the ring notification before attaching its Answer button; the button is picked up when the notification updates.
3. Voice notes: the file is looked up as soon as the notification arrives (background thread, up to 25 s), so a tap plays it at once. The finder now looks only at the newest 3 week-folders instead of walking the whole tree.
4. Spoken replies: new `DictationService` (short microphone foreground service plus an invisible 1-pixel overlay, glasses microphone routed when connected, SpeechRecognizer, offline preferred). State machine gained Dictating and Confirm states: tap -> speak -> "You said ... Tap to send" -> tap sends, no tap in 45 s cancels. Falls back to quick replies if speaking is unavailable. Setting: "Reply by speaking" (default on).
5. Settings screen refreshes itself every second (the "last glasses button" line no longer needs a manual refresh) and has a microphone permission step.
6. Timing lines are logged under the tag GlassesAlerts (notification age, button codes, voice note search time).

Verification: logic package 102/102 checks pass. Android files are syntax-checked only; never built against the SDK here.

Open risks (need the phone):
- Background microphone: Android may refuse mic access to a service started from the background. The overlay and foreground service are the workaround; if "I did not catch that" keeps happening, check Logcat for "recognizer error" and "glasses microphone routed".
- Glasses microphone quality over Bluetooth is lower than the phone mic.
- Whether the call delay was the late Answer button or Android delaying the notification: Logcat shows "notification is N ms old".
- Other gestures (double tap, triple tap, swipes) still unmeasured.

## Refinement Log 3 (Sonnet 5.5)
Device finding: only double taps worked. Ray-Ban Meta sends single tap = play/pause (85), double tap = next (87), triple tap = previous (88). The earlier "measured" tap was a double tap, and play/pause had been mapped to cancel. Now: 85/79/126/127/87 = primary, 88/86 = cancel (also declines a ringing call; at "Tap to send" it goes back to "Tap to speak again"), 89/90 = secondary. Still to confirm on the phone: that a single tap really arrives as 85 (use the live "Last glasses button" line).

## Refinement Log 4 (Sonnet 5.5)
Device finding: with the button test running, only media next (double tap) and media previous (triple tap) reach the phone. The single tap never arrives, so it cannot be used. Prompts now say "Double tap to ..." everywhere; double tap = main action, triple tap = cancel / decline. Play/pause keys stay mapped as primary in case other headsets send them.
Idea if single tap is wanted later (untested): keep a silent audio stream playing while an offer is pending, so the Bluetooth stack treats the phone as "playing" and may forward the glasses' play/pause.

## Refinement Log 5 (Sonnet 5.5)
Device findings: (1) a voice note could be played but there was no way to reply afterwards; (2) spoken reply: the microphone opened and closed within about a second, then "I did not catch that".
1. After a voice note finishes playing the app offers "Double tap to reply" (the voice note notification is kept for its reply action).
2. Dictation: an error within 2.5 s and before any speech is treated as technical, not silence. Try 1 uses the glasses microphone; on a quick failure try 2 uses the phone microphone; a second quick failure reports "unavailable" so the app falls back to quick replies instead of saying "I did not catch that". Removed EXTRA_PREFER_OFFLINE (an offline language pack that is not installed fails immediately). Recognizer is destroyed and recreated per try.
3. Diagnostics: the settings screen shows "Last spoken reply" (try number, which microphone, error code and name, time in ms). Same line goes to Logcat (tag GlassesAlerts).
Open: if both tries fail with the same quick error, the likely cause is Android refusing the microphone to a background service (error 7 or 5 within a second). Next step would be a short transparent Activity to record in the foreground.

## Refinement Log 6 (Opus 5.5, full review)
Review findings and fixes:
1. **Microphone (likely cause of "mic opens and closes in a second").** Android decides whether a foreground service may use the microphone at the moment it starts, and allows it from the background only when the app has a visible window. The invisible overlay was added after the service started, so it did not count. The overlay is now added by AlertListenerService first, and DictationService starts 250 ms later once the window is up. DictationService no longer manages the overlay.
2. **Possible crash.** startForeground with the microphone type throws (instead of returning) when Android refuses; that would have crashed the whole app, including notifications. Now caught, logged in "Last spoken reply", and reported as unavailable (falls back to quick replies).
3. **Quick-reply list was unreachable past reply 1** on these glasses (no "next" gesture). Triple tap now steps to the next reply, and cancels after the last. Prompts say so.
4. **Settings screen gave wrong gesture instructions** (single tap / double tap forward). Fixed to double / triple tap.
5. **Shorter spoken prefixes** for less waiting: "Message from X", "Voice message from X", "Call from X. Double tap to answer."
6. After a spoken reply, media buttons are re-claimed (the microphone switched audio routes), and the read-back waits 300 ms so its start is not cut off.
7. New **Test spoken reply** button (step 7): listens once with the app in the foreground and says back what it heard. Tells foreground-vs-background microphone problems apart.
8. Removed a leftover `overlay: View?` field in DictationService whose import had been removed (would not have compiled).
Verification: logic 108/108 checks pass; Android files syntax-checked and import-checked; still not built against the SDK here.
