package com.meetwake.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** A daily time window in minutes since midnight. end <= start means it crosses midnight; start == end is 24 h. */
data class TimeRange(val start: Int, val end: Int) {
    val length: Int get() = if (end > start) end - start else end + MINUTES_PER_DAY - start

    fun encode() = "$start-$end"

    companion object {
        const val MINUTES_PER_DAY = 1440

        fun decode(s: String): TimeRange? {
            val p = s.split("-")
            if (p.size != 2) return null
            val a = p[0].toIntOrNull() ?: return null
            val b = p[1].toIntOrNull() ?: return null
            if (a !in 0 until MINUTES_PER_DAY || b !in 0 until MINUTES_PER_DAY) return null
            return TimeRange(a, b)
        }
    }
}

/** All user settings. Pure data so the planning logic can be unit tested. */
data class Config(
    val enabled: Boolean = true,
    val wfhDays: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    ),
    val work: TimeRange = TimeRange(9 * 60, 18 * 60),
    val naps: List<TimeRange> = emptyList(),
    val leadMin: Int = 5,
    val eveningPrompt: Boolean = true,
    val eveningPromptMin: Int = 22 * 60,
    val awakeCheck: Boolean = true,
    val awakeCheckMin: Int = 10 * 60 + 30,
    val napEndCheck: Boolean = true,
    val ringForCalls: Boolean = true,
    val broadChat: Boolean = true,
    val extraKeywords: List<String> = emptyList(),
    val snoozeMin: Int = 5,
    val ringTimeoutMin: Int = 10,
    val vibrate: Boolean = true,
    val maxVolume: Boolean = true,
    val ringtoneUri: String? = null,
    val keepConnected: Boolean = false,
    /** People whose Teams messages/calls are ignored. Case-insensitive substring of the sender name. */
    val friends: List<String> = emptyList(),
)

enum class Verdict(val ring: Boolean, val reason: String) {
    RING(true, "ringing"),
    DISABLED(false, "app is turned off"),
    OUTSIDE_HOURS(false, "meeting is outside your work hours"),
    NOT_WFH(false, "not a work-from-home day"),
    AWAKE(false, "you said you're awake"),
}

enum class CheckKind { MORNING, NAP_END }

data class AwakeCheck(val at: LocalDateTime, val kind: CheckKind, val shift: LocalDate)

/**
 * Decides when the app listens and whether an alarm should ring.
 *
 * A "shift" is one work period, identified by the date it starts on (so overnight shifts work).
 * You count as asleep from 3 h before the shift starts and again from the start of each nap,
 * until you confirm you're awake.
 */
class Planner(
    private val cfg: Config,
    private val override: (LocalDate) -> Boolean? = { null },
) {
    companion object {
        /** "I'm awake" taps within this many minutes before a shift starts still count for that shift. */
        const val PRE_SHIFT_MIN = 180L
        private const val DAYS_AHEAD = 9L
    }

    fun shiftStart(d: LocalDate): LocalDateTime = d.atStartOfDay().plusMinutes(cfg.work.start.toLong())
    fun shiftEnd(d: LocalDate): LocalDateTime = shiftStart(d).plusMinutes(cfg.work.length.toLong())

    /** The shift that contains [t], or null when [t] is outside work hours. */
    fun shiftDateFor(t: LocalDateTime): LocalDate? {
        val d = t.toLocalDate()
        for (c in arrayOf(d, d.minusDays(1))) {
            if (!t.isBefore(shiftStart(c)) && t.isBefore(shiftEnd(c))) return c
        }
        return null
    }

    /** The first shift that starts after [t]. */
    fun nextShift(t: LocalDateTime): LocalDate {
        var d = t.toLocalDate().minusDays(1)
        while (!shiftStart(d).isAfter(t)) d = d.plusDays(1)
        return d
    }

    /** The shift in progress, or the next one. */
    fun currentOrNextShift(t: LocalDateTime): LocalDate = shiftDateFor(t) ?: nextShift(t)

    fun isWfhDefault(d: LocalDate) = d.dayOfWeek in cfg.wfhDays
    fun isWfh(d: LocalDate): Boolean = override(d) ?: isWfhDefault(d)

    /** Whether the notification listener should be active at [t]. */
    fun isListening(t: LocalDateTime): Boolean =
        cfg.enabled && shiftDateFor(t)?.let { isWfh(it) } == true

    /** Next time [isListening] may change. */
    fun nextListenChange(t: LocalDateTime): LocalDateTime {
        val base = t.toLocalDate()
        return (-1L..DAYS_AHEAD)
            .flatMap { val s = base.plusDays(it); listOf(shiftStart(s), shiftEnd(s)) }
            .filter { it.isAfter(t) }
            .min()
    }

    /** Start time of every nap that falls inside [shift], with its range. */
    fun napStarts(shift: LocalDate): List<Pair<LocalDateTime, TimeRange>> = cfg.naps.mapNotNull { n ->
        val off = Math.floorMod(n.start - cfg.work.start, TimeRange.MINUTES_PER_DAY)
        if (off < cfg.work.length) shiftStart(shift).plusMinutes(off.toLong()) to n else null
    }

    /** The latest moment at or before [t] from which you are presumed asleep during [shift]. */
    fun sleepMarker(t: LocalDateTime, shift: LocalDate): LocalDateTime {
        var m = shiftStart(shift).minusMinutes(PRE_SHIFT_MIN)
        for ((s, _) in napStarts(shift)) if (!s.isAfter(t) && s.isAfter(m)) m = s
        return m
    }

    fun isAwake(t: LocalDateTime, shift: LocalDate, awakeAt: LocalDateTime?): Boolean =
        awakeAt != null && !awakeAt.isBefore(sleepMarker(t, shift))

    /** Should an alarm for a meeting at [meetingAt] ring, given when you last said you were awake. */
    fun verdict(meetingAt: LocalDateTime, awakeAt: LocalDateTime?): Verdict {
        if (!cfg.enabled) return Verdict.DISABLED
        val shift = shiftDateFor(meetingAt) ?: return Verdict.OUTSIDE_HOURS
        if (!isWfh(shift)) return Verdict.NOT_WFH
        if (isAwake(meetingAt, shift, awakeAt)) return Verdict.AWAKE
        return Verdict.RING
    }

    /** Next "are you awake?" prompt after [t]. */
    fun nextAwakeCheck(t: LocalDateTime): AwakeCheck? {
        val base = t.toLocalDate()
        val list = ArrayList<AwakeCheck>()
        for (i in -1L..DAYS_AHEAD) {
            val s = base.plusDays(i)
            if (cfg.awakeCheck) {
                var off = Math.floorMod(cfg.awakeCheckMin - cfg.work.start, TimeRange.MINUTES_PER_DAY)
                if (off >= TimeRange.MINUTES_PER_DAY - PRE_SHIFT_MIN) off -= TimeRange.MINUTES_PER_DAY
                list += AwakeCheck(shiftStart(s).plusMinutes(off.toLong()), CheckKind.MORNING, s)
            }
            if (cfg.napEndCheck) {
                for ((st, n) in napStarts(s)) list += AwakeCheck(st.plusMinutes(n.length.toLong()), CheckKind.NAP_END, s)
            }
        }
        return list.filter { it.at.isAfter(t) }.minByOrNull { it.at }
    }

    /** Should a due awake check actually be shown? */
    fun shouldPromptAwake(check: AwakeCheck, now: LocalDateTime, awakeAt: LocalDateTime?): Boolean =
        cfg.enabled && isWfh(check.shift) && !isAwake(now, check.shift, awakeAt)

    /** Next occurrence of a daily [minuteOfDay] strictly after [t]. */
    fun nextDaily(t: LocalDateTime, minuteOfDay: Int): LocalDateTime {
        val today = t.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        return if (today.isAfter(t)) today else today.plusDays(1)
    }
}
