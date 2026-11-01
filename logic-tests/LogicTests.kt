package com.glassesalerts.logic

import kotlin.system.exitProcess

private var failures = 0
private var total = 0

private fun check(name: String, ok: Boolean) {
    total++
    if (!ok) {
        failures++
        println("FAIL: $name")
    }
}

private fun msg(title: String, text: String, group: Boolean = false) =
    Classifier.Input(title = title, text = text, isGroupChat = group)

fun main() {
    // ---- Classifier ----
    check("plain message", Classifier.classify(msg("Maria", "Is my order ready?"), false) ==
        Classified(Kind.MESSAGE, "Maria", "Is my order ready?"))
    check("group ignored by default", Classifier.classify(msg("Team", "hi", group = true), false).kind == Kind.IGNORE)
    check("group included when enabled", Classifier.classify(msg("Team", "hi", group = true), true).kind == Kind.MESSAGE)
    check("summary text ignored", Classifier.classify(msg("Maria", "3 new messages"), false).kind == Kind.IGNORE)
    check("summary flag ignored", Classifier.classify(Classifier.Input("Maria", "hi", isGroupSummary = true), false).kind == Kind.IGNORE)
    check("ongoing ignored", Classifier.classify(Classifier.Input("Maria", "hi", isOngoing = true), false).kind == Kind.IGNORE)
    check("app name title ignored", Classifier.classify(msg("WhatsApp Business", "Backup in progress"), false).kind == Kind.IGNORE)
    check("checking messages ignored", Classifier.classify(msg("Maria", "Checking for new messages"), false).kind == Kind.IGNORE)
    check("empty text ignored", Classifier.classify(msg("Maria", ""), false).kind == Kind.IGNORE)
    check("title count stripped", Classifier.cleanTitle("Maria (3 messages)") == "Maria")
    check("title count stripped msg", Classifier.classify(msg("Maria (2 messages)", "hello"), false).sender == "Maria")
    check("voice emoji", Classifier.classify(msg("Maria", "🎤 0:23"), false) == Classified(Kind.VOICE_NOTE, "Maria", ""))
    check("voice words", Classifier.isVoiceNote("Voice message (0:23)"))
    check("voice words with emoji prefix", Classifier.isVoiceNote("✅ Voice message"))
    check("voice spanish", Classifier.isVoiceNote("Mensaje de voz (0:10)"))
    check("normal text not voice", !Classifier.isVoiceNote("I left you a voice message earlier"))
    check("call with answer", Classifier.classify(Classifier.Input("Maria", "Incoming voice call", isCall = true, hasAnswerAction = true), false) ==
        Classified(Kind.INCOMING_CALL, "Maria", "voice"))
    check("video call", Classifier.classify(Classifier.Input("Maria", "Incoming video call", isCall = true, hasAnswerAction = true), false).detail == "video")
    check("call without answer ignored", Classifier.classify(Classifier.Input("Maria", "Ongoing call", isCall = true), false).kind == Kind.IGNORE)
    check("call blank title", Classifier.classify(Classifier.Input("", "Incoming voice call", isCall = true, hasAnswerAction = true), false).sender == "an unknown caller")

    // ---- Speech ----
    check("speech message", Speech.message("Maria", "Hi", true) == "WhatsApp Business message from Maria. Hi")
    check("speech message no body", Speech.message("Maria", "Hi", false) == "New WhatsApp Business message from Maria.")
    check("speech truncates long body", Speech.message("M", "x".repeat(1000), true).length < 400)
    check("speech call", Speech.call("Maria", "voice") == "WhatsApp Business call from Maria.")
    check("speech video call", Speech.call("Maria", "video") == "WhatsApp Business video call from Maria.")

    // ---- QuickReplies / QuietHours / ButtonMap / VoiceNotePicker ----
    check("replies parse trims and drops blanks", QuickReplies.parse(" A \n\n B\n  \nC ") == listOf("A", "B", "C"))
    check("replies parse max count", QuickReplies.parse((1..20).joinToString("\n") { "r$it" }).size == QuickReplies.MAX_REPLIES)
    check("replies parse max length", QuickReplies.parse("x".repeat(500)).first().length == QuickReplies.MAX_LENGTH)
    check("replies blank is empty", QuickReplies.parse("  \n ").isEmpty())
    check("default replies not empty", QuickReplies.parse(QuickReplies.DEFAULT_RAW).size == 4)

    check("quiet overnight late", QuietHours.contains(23, 22, 7))
    check("quiet overnight early", QuietHours.contains(3, 22, 7))
    check("quiet overnight outside", !QuietHours.contains(12, 22, 7))
    check("quiet same-day inside", QuietHours.contains(13, 12, 14))
    check("quiet same-day end exclusive", !QuietHours.contains(14, 12, 14))
    check("quiet start==end off", !QuietHours.contains(5, 5, 5))

    check("button play/pause", ButtonMap.map(85) == Button.PRIMARY)
    check("button headset hook", ButtonMap.map(79) == Button.PRIMARY)
    check("button next", ButtonMap.map(87) == Button.SECONDARY)
    check("button previous", ButtonMap.map(88) == Button.CANCEL)
    check("button unknown", ButtonMap.map(24) == null)

    val files = listOf(
        FileInfo("/a/old.opus", 1_000, 10),
        FileInfo("/a/new.opus", 100_000, 10),
        FileInfo("/a/newer.OPUS", 110_000, 10),
        FileInfo("/a/empty.opus", 120_000, 0),
        FileInfo("/a/pic.jpg", 130_000, 10),
    )
    check("picker newest audio", VoiceNotePicker.pick(files, 90_000)?.path == "/a/newer.OPUS")
    check("picker none recent", VoiceNotePicker.pick(files, 500_000) == null)
    check("picker slack includes slightly earlier", VoiceNotePicker.pick(files, 115_000)?.path == "/a/newer.OPUS")

    // ---- State machine ----
    var now = 0L
    var replies = listOf("Yes", "No", "Later")
    val sm = ActionStateMachine({ replies }, { now })

    // call
    val callFx = sm.onIncomingCall("Maria", "voice")
    check("call announces", callFx.first() == Effect.Speak("WhatsApp Business call from Maria.", flush = true))
    check("call pending", sm.current is Pending.Call)
    check("call answer", sm.onButton(Button.PRIMARY) == listOf(Effect.AnswerCall))
    check("call cleared after answer", !sm.hasPending())

    sm.onIncomingCall("Maria", "voice")
    check("call decline", sm.onButton(Button.SECONDARY) == listOf(Effect.DeclineCall))

    sm.onIncomingCall("Maria", "voice")
    check("call cancel does nothing", sm.onButton(Button.CANCEL).isEmpty() && !sm.hasPending())

    sm.onIncomingCall("Maria", "voice")
    sm.onCallEnded()
    check("call ended clears", !sm.hasPending())

    // message -> reply flow
    check("message hint", sm.onMessage("Maria", "k1", canReply = true) == listOf(Effect.Speak(Speech.REPLY_HINT)))
    check("tap starts selection", sm.onButton(Button.PRIMARY) == listOf(Effect.Speak(Speech.replyOption(0, 3, "Yes"), flush = true)))
    check("next cycles", sm.onButton(Button.SECONDARY) == listOf(Effect.Speak(Speech.replyOption(1, 3, "No"), flush = true)))
    sm.onButton(Button.SECONDARY)
    check("cycle wraps", sm.onButton(Button.SECONDARY) == listOf(Effect.Speak(Speech.replyOption(0, 3, "Yes"), flush = true)))
    sm.onButton(Button.SECONDARY)
    check("send selected", sm.onButton(Button.PRIMARY) == listOf(Effect.SendReply("k1", "No")))
    check("cleared after send", !sm.hasPending())

    sm.onMessage("Maria", "k2", true)
    sm.onButton(Button.PRIMARY)
    check("cancel in selection", sm.onButton(Button.CANCEL) == listOf(Effect.Speak("Cancelled.")) && !sm.hasPending())

    sm.onMessage("Maria", "k3", true)
    check("secondary on message dismisses", sm.onButton(Button.SECONDARY).isEmpty() && !sm.hasPending())

    check("cannot reply -> no pending", sm.onMessage("Maria", "k4", canReply = false).isEmpty() && !sm.hasPending())
    replies = emptyList()
    check("no replies configured -> no pending", sm.onMessage("Maria", "k5", true).isEmpty() && !sm.hasPending())
    replies = listOf("Yes", "No", "Later")

    // priority rules
    sm.onIncomingCall("Maria", "voice")
    check("message does not replace call", sm.onMessage("Bob", "k6", true).isEmpty() && sm.current is Pending.Call)
    check("voice does not replace call", sm.onVoiceNote("Bob").isEmpty() && sm.current is Pending.Call)
    sm.onButton(Button.CANCEL)
    sm.onMessage("Maria", "k7", true)
    sm.onButton(Button.PRIMARY)
    check("message does not hijack selection", sm.onMessage("Bob", "k8", true).isEmpty() && sm.current is Pending.Select)
    sm.clear()

    // voice note
    check("voice hint", sm.onVoiceNote("Maria") == listOf(Effect.Speak(Speech.VOICE_HINT)))
    check("voice play", sm.onButton(Button.PRIMARY) == listOf(Effect.PlayVoiceNote("Maria")))
    sm.onVoiceNote("Maria")
    check("voice secondary dismisses", sm.onButton(Button.SECONDARY).isEmpty() && !sm.hasPending())

    // expiry
    sm.onIncomingCall("Maria", "voice")
    now += 46_000
    check("pending expires", !sm.hasPending() && sm.onButton(Button.PRIMARY).isEmpty())

    // replies shrinking while selecting
    sm.onMessage("Maria", "k9", true)
    sm.onButton(Button.PRIMARY)
    sm.onButton(Button.SECONDARY)
    sm.onButton(Button.SECONDARY)
    replies = listOf("Only")
    check("index clamped if replies shrink", sm.onButton(Button.PRIMARY) == listOf(Effect.SendReply("k9", "Only")))

    // ---- AnnounceFilter ----
    val f = AnnounceFilter()
    check("filter new message", f.shouldAnnounce("c1", "Hi", postedAt = 1_000_000, now = 1_000_500))
    check("filter same text repost", !f.shouldAnnounce("c1", "Hi", 1_000_000, 1_005_000))
    check("filter new text same chat", f.shouldAnnounce("c1", "Are you open?", 1_006_000, 1_006_100))
    check("filter old notification", !f.shouldAnnounce("c2", "Old", postedAt = 1_000_000, now = 1_200_000))
    check("filter unknown time allowed", f.shouldAnnounce("c3", "Hey", postedAt = 0, now = 9_999_999))
    f.noteSentReply("c1", "Yes, thank you.", now = 1_010_000)
    check("filter own reply exact", !f.shouldAnnounce("c1", "Yes, thank you.", 1_010_000, 1_011_000))
    f.noteSentReply("c1", "One moment please.", now = 1_020_000)
    check("filter own reply prefixed", !f.shouldAnnounce("c1", "You: One moment please.", 1_020_000, 1_021_000))
    check("filter other chat unaffected", f.shouldAnnounce("c4", "One moment please.", 1_021_000, 1_021_500))
    check("filter reply window ends", f.shouldAnnounce("c1", "Thanks!", 1_040_000, 1_040_100))
    f.forget("c1")
    check("filter forget allows repeat", f.shouldAnnounce("c1", "Thanks!", 1_050_000, 1_050_100))

    println("$total checks, $failures failed")
    if (failures == 0) println("ALL PASSED") else exitProcess(1)
}
