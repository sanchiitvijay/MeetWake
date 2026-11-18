package com.meetwake.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class HindiAndFriendsTest {
    // Tuesday 17 Nov 2026, 09:40
    private val now = LocalDateTime.of(2026, 11, 17, 9, 40)
    private val cfg = Config()

    private fun detect(title: String, text: String, c: Config = cfg, senders: List<String> = emptyList(), category: String? = null) =
        MeetingParser.detect(NotifInput(title, text, category = category, postedAt = now, senders = senders), c)

    // ---------- English chat phrases ----------

    @Test fun englishChatPhrases() {
        for (t in listOf("please join", "everyone is waiting for you", "are you there?", "urgent, ping me", "quick call?", "you're needed in the meeting")) {
            assertEquals(t, Kind.RING, detect("Riya", t).kind)
        }
    }

    @Test fun strongEnglishWorksEvenWithChatOff() {
        val off = cfg.copy(broadChat = false)
        assertEquals(Kind.RING, detect("Riya", "please join, we are waiting for you", off).kind)
        assertEquals(Kind.IGNORE, detect("Riya", "are you there?", off).kind)
    }

    // ---------- Hinglish ----------

    @Test fun hinglishStrong() {
        val off = cfg.copy(broadChat = false)
        for (t in listOf("meeting join karo", "call pe aao", "jaldi join karo", "meeting shuru ho gayi", "sab wait kar rahe hai")) {
            assertEquals(t, Kind.RING, detect("Amit", t, off).kind)
        }
    }

    @Test fun hinglishChat() {
        for (t in listOf("kahan ho?", "call karo jab free ho", "baat karni hai", "online aao", "phone uthao", "utho bhai")) {
            assertEquals(t, Kind.RING, detect("Amit", t).kind)
        }
    }

    @Test fun hinglishPlainChatIgnored() {
        assertEquals(Kind.IGNORE, detect("Amit", "lunch ke baad review kar lena").kind)
    }

    @Test fun hinglishEndedIgnored() {
        assertEquals(Kind.IGNORE, detect("Amit", "meeting khatam, thanks").kind)
    }

    @Test fun hinglishCancel() {
        assertEquals(Kind.CANCEL, detect("Budget sync", "aaj ki meeting cancel ho gayi").kind)
    }

    // ---------- Hindi (Devanagari) ----------

    @Test fun hindiStrong() {
        val off = cfg.copy(broadChat = false)
        for (t in listOf("मीटिंग शुरू हो गई है", "जल्दी जुड़ो", "कॉल पर आओ", "सब इंतज़ार कर रहे हैं")) {
            assertEquals(t, Kind.RING, detect("अमित", t, off).kind)
        }
    }

    @Test fun hindiChat() {
        for (t in listOf("कहाँ हो?", "कॉल करो", "बात करनी है")) {
            assertEquals(t, Kind.RING, detect("अमित", t).kind)
        }
    }

    @Test fun hindiEndedAndCancelled() {
        assertEquals(Kind.IGNORE, detect("Standup", "मीटिंग खत्म हो गई").kind)
        assertEquals(Kind.CANCEL, detect("Standup", "आज की मीटिंग रद्द").kind)
    }

    // ---------- Hinglish / Hindi times ----------

    @Test fun relativeHinglish() {
        assertEquals(now.plusMinutes(10), detect("Amit", "10 min me meeting join karo").meetingAt)
        assertEquals(now.plusHours(1), detect("Amit", "1 ghante baad meeting hai, join karo").meetingAt)
        assertEquals(now.plusMinutes(5), detect("अमित", "5 मिनट में मीटिंग शुरू").meetingAt)
    }

    @Test fun bajeWithHint() {
        assertEquals(LocalDateTime.of(2026, 11, 17, 17, 0), detect("Amit", "shaam 5 baje call karo").meetingAt)
        assertEquals(LocalDateTime.of(2026, 11, 18, 11, 0), detect("Amit", "kal subah 11 baje meeting join karo").meetingAt)
        assertEquals(LocalDateTime.of(2026, 11, 17, 15, 30), detect("अमित", "दोपहर 3:30 बजे मीटिंग शुरू").meetingAt)
    }

    @Test fun bajeWithoutHintPicksNext() {
        // 9:40 now: "11 baje" means 11:00 today.
        assertEquals(LocalDateTime.of(2026, 11, 17, 11, 0), detect("Amit", "11 baje call pe aao").meetingAt)
    }

    @Test fun abhiMeansNow() {
        assertNull(detect("Amit", "abhi call pe aao").meetingAt)
        assertNull(detect("अमित", "अभी कॉल पर आओ").meetingAt)
    }

    // ---------- friends ----------

    @Test fun friendByTitleIsIgnored() {
        val c = cfg.copy(friends = listOf("rahul"))
        val d = detect("Rahul Sharma", "bhai call karo", c)
        assertEquals(Kind.IGNORE, d.kind)
        assertEquals(true, d.reason.contains("friend"))
    }

    @Test fun friendMatchIsCaseInsensitiveSubstring() {
        val c = cfg.copy(friends = listOf("  SHARMA "))
        assertEquals(Kind.IGNORE, detect("Rahul Sharma", "join the call", c).kind)
        assertEquals(Kind.RING, detect("Priya Verma", "join the call", c).kind)
    }

    @Test fun groupChatUsesActualSenderNotGroupTitle() {
        val c = cfg.copy(friends = listOf("Rahul"))
        // Group titled with Rahul's name, but the message is from Priya: must still ring.
        assertEquals(Kind.RING, detect("Rahul, Priya, Amit", "join the call", c, senders = listOf("Priya Verma")).kind)
        // Same group, message from Rahul: ignored.
        assertEquals(Kind.IGNORE, detect("Rahul, Priya, Amit", "join the call", c, senders = listOf("Rahul Sharma")).kind)
    }

    @Test fun friendInNamePrefixLine() {
        val c = cfg.copy(friends = listOf("rahul"))
        assertEquals(Kind.IGNORE, detect("Weekend plans", "Rahul: call pe aa jao", c).kind)
    }

    @Test fun friendNameInsideMessageDoesNotCount() {
        val c = cfg.copy(friends = listOf("rahul"))
        assertEquals(Kind.RING, detect("Priya", "Rahul said join the call now", c).kind)
    }

    @Test fun friendCallStillRings() {
        val c = cfg.copy(friends = listOf("rahul"))
        assertEquals(Kind.RING, detect("Rahul Sharma", "Incoming call", c, category = "call").kind)
    }

    @Test fun meetingReminderWithFriendNameStillRings() {
        val c = cfg.copy(friends = listOf("rahul"))
        // Reminder whose meeting title contains the friend's name.
        assertEquals(Kind.RING, detect("Rahul's team sync", "Meeting starts in 10 minutes", c).kind)
        assertEquals(Kind.RING, detect("Rahul's team sync", "10:00 AM - 10:30 AM", c, category = "event").kind)
        // Invite sent by the friend.
        assertEquals(Kind.RING, detect("Rahul Sharma", "Rahul Sharma invited you to Budget sync tomorrow at 11:30 am", c).kind)
        assertEquals(Kind.RING, detect("Rahul Sharma", "Rahul started a meeting", c).kind)
    }

    @Test fun friendMessageMarkedAsMessageIsIgnored() {
        val c = cfg.copy(friends = listOf("rahul"))
        // Teams marked it as a chat, so even meeting-like words in it are the friend chatting.
        val n = NotifInput("Rahul Sharma", "meeting starts in 5 min, join na", category = "msg", postedAt = now, isMessage = true)
        assertEquals(Kind.IGNORE, MeetingParser.detect(n, c).kind)
    }

    @Test fun hindiFriendName() {
        val c = cfg.copy(friends = listOf("अमित"))
        assertEquals(Kind.IGNORE, detect("अमित कुमार", "कॉल करो", c).kind)
    }

    @Test fun noFriendsMeansNoFiltering() {
        assertEquals(Kind.RING, detect("Rahul", "join the call", cfg.copy(friends = listOf("", "   "))).kind)
    }
}
