# Glasses Alerts (WhatsApp Business + Meta Ray-Ban)

Android app that works with the WhatsApp Business app (`com.whatsapp.w4b`) and your
Meta Ray-Ban glasses, connected over Bluetooth like a headset.

- Reads new messages aloud.
- Announces incoming calls. Tap your glasses to answer.
- After a message, tap to speak a reply. The app reads back what it heard, and you tap to send.
  (Or switch to saved quick replies in the settings.)
- Announces voice notes. Tap to play them in your glasses.

## Build it (Android Studio)
1. Install Android Studio (free): https://developer.android.com/studio
2. Download this repo as a ZIP (green Code button > Download ZIP) and unzip it.
3. File > Open, and choose the folder that **contains `app` and `build.gradle.kts`**.
   (If you see another folder with the same name inside, open that inner one.)
4. Let Gradle sync. If it offers to update the Android Gradle Plugin, accept.
5. On your phone: Settings > About phone > tap "Build number" 7 times, then
   Settings > Developer options > turn on USB debugging.
6. Plug the phone in, pick it at the top of Android Studio, press the green Run button.

Note: the Android part of this project was written without being able to compile it here, so if
Android Studio shows a red error, copy it back to Claude and it will be fixed.

## Set it up on the phone
1. Open **Glasses Alerts**, tap step 1, and switch on notification access.
   (If Android says "restricted setting": Settings > Apps > Glasses Alerts >
   three-dot menu > Allow restricted settings, then try again.)
2. Step 2: set the app's battery to **Unrestricted**.
3. Step 3: allow **All files access** (only needed for voice notes).
4. Step 4: allow **Display over other apps**. Without it, Android may block answering a call
   by tap and spoken replies may not start in the background.
5. Step 5: allow the **Microphone** (only needed for spoken replies).
6. Connect your glasses and tap step 6 to hear a test message.
7. Step 7, **Test glasses buttons**: tap and swipe your glasses. They say which button they
   sent, and the "Last glasses button" line updates by itself.
8. Edit your **quick replies** and tap Save.

## How taps work
A single tap on the glasses sends "media next". While something is waiting, that tap is the
main action:
- Call ringing: tap = answer
- After a message ("Tap to reply"): tap = start speaking your reply (a beep means it is listening)
- After it reads back what you said ("Tap to send"): tap = send. Do nothing for 45 seconds to cancel.
- After a voice note ("Tap to play"): tap = play

Every accepted tap gives an instant short beep. Other gestures (previous / play-pause) are
provisional: previous = say it again or next quick reply, play-pause = cancel.
Run step 7 to see what each of your gestures actually sends.

## Spoken replies
- Speak after the second beep. The app waits for a short pause, then reads back "You said: ...".
- It uses your phone's speech recognition, and tries the glasses microphone first.
  Depending on your phone, your own voice may be processed by Google's speech service.
  Customers' messages and audio are never sent anywhere by this app.
- If speaking is unavailable (no microphone permission, no recognizer), it falls back to
  your saved quick replies.

## Good to know
- WhatsApp Business must have notifications turned on in Android settings, and not muted.
- Voice notes only play if WhatsApp Business has already downloaded them (media auto-download on).
  The app looks for the file as soon as the notification arrives, so a tap can play it at once.
- Replies go out as normal replies from the notification, so they appear in your chats.
- This app has no internet permission of its own.
- Debug timing lines are written to Logcat under the tag `GlassesAlerts`.

## Files
- `BUILD_PLAN.md`: design, status and notes for the next round of changes.
- `app/.../logic/`: the decision logic, written without Android code so it can be tested.
- `logic-tests/` and `logic-test/`: tests for that logic (`cd logic-test && gradle run`).
