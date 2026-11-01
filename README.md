# Glasses Alerts (WhatsApp Business + Meta Ray-Ban)

Android app that works with the WhatsApp Business app (`com.whatsapp.w4b`) and your
Meta Ray-Ban glasses, connected over Bluetooth like a headset.

- Reads new messages aloud.
- Announces incoming calls. Tap your glasses to answer, double tap to decline.
- After a message, tap to pick a quick reply (you set them), tap again to send.
- Announces voice notes. Tap to play them in your glasses.

## Build it (Android Studio)
1. Install Android Studio (free): https://developer.android.com/studio
2. Unzip this project, then File > Open and choose the `GlassesAlerts` folder.
3. Let Gradle sync. If it offers to update the Android Gradle Plugin, accept.
4. On your phone: Settings > About phone > tap "Build number" 7 times, then
   Settings > Developer options > turn on USB debugging.
5. Plug the phone in, pick it at the top of Android Studio, press the green Run button.

Note: this version was written without being able to compile the Android part, so if
Android Studio shows a red error, copy it back to Claude and it will be fixed.

## Set it up on the phone
1. Open **Glasses Alerts**, tap step 1, and switch on notification access.
   (If Android says "restricted setting": Settings > Apps > Glasses Alerts >
   three-dot menu > Allow restricted settings, then try again.)
2. Step 2: set the app's battery to **Unrestricted**.
3. Step 3: allow **All files access** (only needed for voice notes).
4. Step 4: allow **Display over other apps**. Without it, Android may block answering a call
   by tap. If that happens, the glasses tell you to answer on your phone.
5. Connect your glasses and tap step 5 to hear a test message.
6. Step 6, **Test glasses buttons**: tap and swipe your glasses. They say which button they
   sent. If a tap does not answer a call, tell Claude what the test said and the mapping
   will be changed.
7. Edit your **quick replies** and tap Save.

## How taps work
- Tap = answer a call / start replying and send the shown reply / play the voice note
- Double tap forward = decline a call / next quick reply
- Triple tap = cancel
- Actions expire after 45 seconds. When nothing is waiting, the app does not touch your glasses buttons.

## Good to know
- WhatsApp Business must have notifications turned on in Android settings, and not muted.
- Voice notes only play if WhatsApp Business has already downloaded them (media auto-download on).
- Quick replies go out as normal replies from the notification, so they appear in your chats.
- The app has no internet permission. Nothing leaves your phone.

## Files
- `BUILD_PLAN.md`: design, status and notes for the next round of changes.
- `app/.../logic/`: the decision logic, written without Android code so it can be tested.
- `logic-tests/` and `logic-test/`: tests for that logic (`cd logic-test && gradle run`).
