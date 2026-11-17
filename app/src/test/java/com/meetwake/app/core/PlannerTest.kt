package com.meetwake.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PlannerTest {
    private val mon = LocalDate.of(2026, 11, 16) // a Monday
    private fun at(d: LocalDate, h: Int, m: Int = 0): LocalDateTime = d.atTime(h, m)

    private val cfg = Config(
        work = TimeRange(9 * 60, 18 * 60),
        naps = listOf(TimeRange(14 * 60, 15 * 60)),
    )

    @Test fun listensOnlyInWorkHoursOnWfhDays() {
        val p = Planner(cfg)
        assertFalse(p.isListening(at(mon, 8, 59)))
        assertTrue(p.isListening(at(mon, 9, 0)))
        assertTrue(p.isListening(at(mon, 17, 59)))
        assertFalse(p.isListening(at(mon, 18, 0)))
        assertFalse(p.isListening(at(mon.plusDays(5), 10))) // Saturday
    }

    @Test fun overrideBeatsWeekdays() {
        val sat = mon.plusDays(5)
        val p = Planner(cfg) { d -> if (d == sat) true else if (d == mon) false else null }
        assertTrue(p.isListening(at(sat, 10)))
        assertFalse(p.isListening(at(mon, 10)))
    }

    @Test fun disabledNeverListens() {
        assertFalse(Planner(cfg.copy(enabled = false)).isListening(at(mon, 10)))
    }

    @Test fun nextListenChangeIsShiftBoundary() {
        val p = Planner(cfg)
        assertEquals(at(mon, 9), p.nextListenChange(at(mon, 2)))
        assertEquals(at(mon, 18), p.nextListenChange(at(mon, 9)))
        assertEquals(at(mon.plusDays(1), 9), p.nextListenChange(at(mon, 18)))
    }

    @Test fun ringsWhenNotConfirmedAwake() {
        assertEquals(Verdict.RING, Planner(cfg).verdict(at(mon, 10), null))
    }

    @Test fun awakeSilencesUntilNap() {
        val p = Planner(cfg)
        val awake = at(mon, 10, 30)
        assertEquals(Verdict.AWAKE, p.verdict(at(mon, 11), awake))
        assertEquals(Verdict.RING, p.verdict(at(mon, 14, 20), awake)) // nap re-arms
        assertEquals(Verdict.AWAKE, p.verdict(at(mon, 14, 50), at(mon, 14, 40))) // woke up mid-nap
        assertEquals(Verdict.RING, p.verdict(at(mon, 16), awake)) // not confirmed after the nap
        assertEquals(Verdict.AWAKE, p.verdict(at(mon, 16), at(mon, 15, 5)))
    }

    @Test fun awakeYesterdayDoesNotCountToday() {
        assertEquals(Verdict.RING, Planner(cfg).verdict(at(mon.plusDays(1), 10), at(mon, 17)))
    }

    @Test fun awakeShortlyBeforeShiftCounts() {
        assertEquals(Verdict.AWAKE, Planner(cfg).verdict(at(mon, 10), at(mon, 7, 30)))
        // Answering the evening question at 22:00 must not count as awake next morning.
        assertEquals(Verdict.RING, Planner(cfg).verdict(at(mon.plusDays(1), 10), at(mon, 22)))
    }

    @Test fun meetingOutsideHoursOrNotWfh() {
        val p = Planner(cfg)
        assertEquals(Verdict.OUTSIDE_HOURS, p.verdict(at(mon, 19), null))
        assertEquals(Verdict.NOT_WFH, p.verdict(at(mon.plusDays(6), 10), null)) // Sunday
    }

    @Test fun alarmBeforeShiftForMeetingAtShiftStartStillRings() {
        // Verdict is about the meeting time, so a 9:00 meeting rings at 8:55.
        assertEquals(Verdict.RING, Planner(cfg).verdict(at(mon, 9), null))
    }

    @Test fun overnightShift() {
        val night = Config(work = TimeRange(20 * 60, 4 * 60))
        val p = Planner(night)
        assertTrue(p.isListening(at(mon, 23)))
        assertTrue(p.isListening(at(mon.plusDays(1), 3))) // still Monday's shift
        assertFalse(p.isListening(at(mon.plusDays(1), 5)))
        assertEquals(mon, p.shiftDateFor(at(mon.plusDays(1), 3)))
        // Friday night shift runs into Saturday morning.
        val fri = mon.plusDays(4)
        assertTrue(p.isListening(at(fri.plusDays(1), 2)))
        assertFalse(p.isListening(at(fri.plusDays(1), 21))) // Saturday night is off
    }

    @Test fun awakeChecks() {
        val p = Planner(cfg.copy(awakeCheckMin = 10 * 60 + 30))
        val c1 = p.nextAwakeCheck(at(mon, 8))!!
        assertEquals(at(mon, 10, 30), c1.at)
        assertEquals(CheckKind.MORNING, c1.kind)
        val c2 = p.nextAwakeCheck(c1.at)!!
        assertEquals(at(mon, 15), c2.at)
        assertEquals(CheckKind.NAP_END, c2.kind)
        assertEquals(at(mon.plusDays(1), 10, 30), p.nextAwakeCheck(c2.at)!!.at)
    }

    @Test fun awakeCheckSkippedWhenAlreadyAwake() {
        val p = Planner(cfg)
        val chk = AwakeCheck(at(mon, 10, 30), CheckKind.MORNING, mon)
        assertTrue(p.shouldPromptAwake(chk, chk.at, null))
        assertFalse(p.shouldPromptAwake(chk, chk.at, at(mon, 9, 15)))
        val sun = mon.plusDays(6)
        assertFalse(p.shouldPromptAwake(AwakeCheck(at(sun, 10, 30), CheckKind.MORNING, sun), at(sun, 10, 30), null))
    }

    @Test fun nextShiftAndDaily() {
        val p = Planner(cfg)
        assertEquals(mon.plusDays(1), p.nextShift(at(mon, 22)))
        assertEquals(mon, p.nextShift(at(mon, 7)))
        assertEquals(at(mon, 22), p.nextDaily(at(mon, 21), 22 * 60))
        assertEquals(at(mon.plusDays(1), 22), p.nextDaily(at(mon, 22), 22 * 60))
    }

    @Test fun timeRangeCodec() {
        assertEquals(TimeRange(840, 900), TimeRange.decode("840-900"))
        assertEquals(null, TimeRange.decode("garbage"))
        assertEquals(1440, TimeRange(600, 600).length)
        assertEquals(480, TimeRange(1200, 240).length)
    }
}
