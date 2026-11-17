package com.meetwake.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class MeetingParserTest {
    // Tuesday 17 Nov 2026, 09:40
    private val now = LocalDateTime.of(2026, 11, 17, 9, 40)
    private val cfg = Config()

    private fun detect(
        title: String, text: String, category: String? = null, fullScreen: Boolean = false,
        c: Config = cfg, ongoing: Boolean = false,
    ) = MeetingParser.detect(NotifInput(title, text, category, fullScreen, ongoing, now), c)

    @Test fun reminderWithRelativeTime() {
        val d = detect("Sprint planning", "Starts in 15 minutes")
        assertEquals(Kind.RING, d.kind)
        assertEquals(now.plusMinutes(15), d.meetingAt)
        assertEquals("Sprint planning", d.subject)
    }

    @Test fun reminderWithTimeRange() {
        val d = detect("Design review", "Meeting reminder · 10:00 AM - 10:30 AM")
        assertEquals(Kind.RING, d.kind)
        assertEquals(LocalDateTime.of(2026, 11, 17, 10, 0), d.meetingAt)
    }

    @Test fun rangeWithSharedMeridiem() {
        val d = detect("1:1", "Meeting invite: 2 - 2:30 pm")
        assertEquals(LocalDateTime.of(2026, 11, 17, 14, 0), d.meetingAt)
    }

    @Test fun inviteForTomorrow() {
        val d = detect("Alex Kim", "Alex Kim invited you to Budget sync tomorrow at 11:30 am")
        assertEquals(Kind.RING, d.kind)
        assertEquals(LocalDateTime.of(2026, 11, 18, 11, 30), d.meetingAt)
    }

    @Test fun inviteWithMonthDay() {
        val d = detect("Quarterly review", "New meeting: Thu, Nov 19 3:00 PM")
        assertEquals(LocalDateTime.of(2026, 11, 19, 15, 0), d.meetingAt)
    }

    @Test fun inviteWithWeekday() {
        val d = detect("Retro", "Priya scheduled a meeting for Friday 16:00")
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 0), d.meetingAt)
    }

    @Test fun meetingStartedRingsNow() {
        val d = detect("Team channel", "Sam started a meeting")
        assertEquals(Kind.RING, d.kind)
        assertNull(d.meetingAt)
    }

    @Test fun meetNow() {
        val d = detect("Dev team", "Meet now")
        assertEquals(Kind.RING, d.kind)
        assertNull(d.meetingAt)
    }

    @Test fun incomingCall() {
        val d = detect("Priya Shah", "Incoming video call", category = "call", fullScreen = true)
        assertEquals(Kind.RING, d.kind)
        assertNull(d.meetingAt)
    }

    @Test fun ongoingCallIgnored() {
        assertEquals(Kind.IGNORE, detect("Priya Shah", "00:12:31", category = "call").kind)
    }

    @Test fun callsCanBeTurnedOff() {
        assertEquals(Kind.IGNORE, detect("Priya", "Incoming call", category = "call", fullScreen = true, c = cfg.copy(ringForCalls = false)).kind)
    }

    @Test fun missedCall() {
        assertEquals(Kind.RING, detect("Priya", "Missed call", category = "missed_call").kind)
    }

    @Test fun chatAskingToJoin() {
        val d = detect("Riya", "hey can you join the call?")
        assertEquals(Kind.RING, d.kind)
    }

    @Test fun chatWithLooseHour() {
        val d = detect("Riya", "let's meet at 3")
        assertEquals(LocalDateTime.of(2026, 11, 17, 15, 0), d.meetingAt)
    }

    @Test fun chatIgnoredWhenBroadOff() {
        assertEquals(Kind.IGNORE, detect("Riya", "hey can we hop on a call", c = cfg.copy(broadChat = false)).kind)
    }

    @Test fun plainChatIgnored() {
        assertEquals(Kind.IGNORE, detect("Riya", "pushed the fix, please review").kind)
    }

    @Test fun customKeyword() {
        assertEquals(Kind.IGNORE, detect("Boss", "urgent: client escalation", c = cfg.copy(broadChat = false)).kind)
        assertEquals(Kind.RING, detect("Boss", "urgent: client escalation", c = cfg.copy(broadChat = false, extraKeywords = listOf("Urgent"))).kind)
    }

    @Test fun endedAndRecordingIgnored() {
        assertEquals(Kind.IGNORE, detect("Standup", "Meeting ended: 15m 20s").kind)
        assertEquals(Kind.IGNORE, detect("Standup", "Recording is ready").kind)
        assertEquals(Kind.IGNORE, detect("Riya", "Riya reacted to your message: join the meeting").kind)
    }

    @Test fun cancelled() {
        assertEquals(Kind.CANCEL, detect("Budget sync", "Canceled: Budget sync tomorrow 11:30 AM").kind)
    }

    @Test fun rescheduled() {
        val d = detect("Budget sync", "Alex updated the meeting: Wed, Nov 18 2:00 PM")
        assertEquals(Kind.RING, d.kind)
        assertEquals(true, d.reschedule)
        assertEquals(LocalDateTime.of(2026, 11, 18, 14, 0), d.meetingAt)
    }

    @Test fun futureDateWithoutTimeIgnored() {
        assertEquals(Kind.IGNORE, detect("Offsite", "Alex invited you to Offsite planning on Nov 25").kind)
    }

    @Test fun pastClockTimeWithinHourStillRings() {
        val d = detect("Standup", "Meeting started at 9:30 AM")
        assertEquals(Kind.RING, d.kind)
        assertEquals(LocalDateTime.of(2026, 11, 17, 9, 30), d.meetingAt)
    }

    @Test fun clockTimeLongPastMeansTomorrow() {
        val d = detect("Standup", "New meeting invite 8:00 AM")
        assertEquals(LocalDateTime.of(2026, 11, 18, 8, 0), d.meetingAt)
    }

    @Test fun relativeHours() {
        assertEquals(now.plusHours(2), detect("Sync", "Meeting starts in 2 hours").meetingAt)
        assertEquals(now.plusMinutes(30), detect("Sync", "starting in half an hour").meetingAt)
    }

    @Test fun ambiguousWordsNotMistakenForDates() {
        // "Mark" must not be read as March, "sat" must not be a Saturday.
        val d = detect("Mark 3", "I sat down, join the call now")
        assertEquals(Kind.RING, d.kind)
        assertNull(d.meetingAt)
    }

    @Test fun ongoingTeamsCallMeansInCall() {
        assertEquals(Kind.IN_CALL, detect("Sprint planning", "00:12:31", category = "call", ongoing = true).kind)
        assertEquals(Kind.IN_CALL, detect("Sprint planning", "Tap to return to the meeting", ongoing = true).kind)
    }

    @Test fun chatSayingInCallIsNotYou() {
        assertEquals(Kind.RING, detect("Riya", "I'm in call with client, join the call after").kind)
    }

    @Test fun incomingCallWhileOngoingFlagIsStillIncoming() {
        val d = detect("Priya Shah", "Incoming call", category = "call", fullScreen = true, ongoing = true)
        assertEquals(Kind.RING, d.kind)
        assertEquals(true, d.isCall)
    }
}
