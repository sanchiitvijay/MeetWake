package com.meetwake.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** The parts of a Teams notification we look at. */
data class NotifInput(
    val title: String,
    val text: String,
    /** Notification.category, e.g. "call", "event", "reminder", "msg". */
    val category: String? = null,
    /** Incoming-call notifications carry a full-screen intent. */
    val hasFullScreenIntent: Boolean = false,
    /** FLAG_ONGOING_EVENT: Teams uses it for the "you're in a call/meeting" notification. */
    val ongoing: Boolean = false,
    val postedAt: LocalDateTime,
)

/** IN_CALL: you are already in a Teams call or meeting, so you're awake and nothing should ring. */
enum class Kind { RING, CANCEL, IGNORE, IN_CALL }

data class Detection(
    val kind: Kind,
    /** When the meeting starts; null means "now". */
    val meetingAt: LocalDateTime?,
    val subject: String,
    val reason: String,
    /** The meeting was moved: replace any alarm already set for the same subject. */
    val reschedule: Boolean = false,
    /** Triggered by an incoming/missed call rather than a meeting. */
    val isCall: Boolean = false,
)

/** Reads a Teams notification and decides whether it is about a meeting, and when that meeting is. */
object MeetingParser {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Notifications about something that is over or not for us. */
    private val IGNORE = listOf(
        "meeting ended", "call ended", "ended the meeting", "ended the call", "left the meeting",
        "declined", "accepted your", "tentatively accepted", "recording is ready", "recording is available",
        "transcript", "recap", "meeting notes", "reacted to", "liked your",
    )

    /** Teams' notification while you're in a call or meeting. */
    private val IN_CALL = rx("""\b(you're now in|you are now in|you're in a|you are in a|in call|ongoing call|call in progress|meeting in progress|tap to return|return to (the )?(call|meeting))\b""")

    private val MEETING_WORD = rx("""\b(meeting|meet|call|event|invite|invitation|webinar|town ?hall|stand-?up|scrum|huddle|sync)\b""")
    private val CANCEL = rx("""\b(cancel(l)?ed|cancel(l)?ing|canceled)\b""")
    private val RESCHEDULE = rx("""\b(rescheduled|updated|moved|time changed|new time)\b""")

    /** Phrases Teams uses for meeting invites, reminders and starts. */
    private val STRONG = listOf(
        rx("""\bmeeting (started|has started|is starting|starting|starts|reminder|invite|invitation|request)\b"""),
        rx("""\bstarted (a|the) (meeting|call)\b"""),
        rx("""\b(starts|starting|begins|beginning) (in|now|soon|at)\b"""),
        rx("""\bmeet now\b"""),
        rx("""\b(invited you|sent you an? (meeting )?invit\w*|scheduled (a|the|an?) \w*\s?meeting|new meeting)\b"""),
        rx("""\bjoin (the |this |our |my )?(meeting|call|teams meeting|now)\b"""),
        rx("""\b(is calling|incoming (video |audio )?call|missed (video |audio )?call|calling you)\b"""),
        rx("""\b(webinar|town ?hall|live event)\b"""),
        rx("""\b(updated|rescheduled|moved) (the )?meeting\b"""),
    )

    /** Looser words used when "chat messages" detection is on. */
    private val BROAD = rx("""\b(meeting|meet|call|calling|join|joining|huddle|stand-?up|scrum|sync up|hop on|jump on)\b""")

    private val CALL_INCOMING = rx("""\b(is calling|incoming|calling you|ringing|missed)\b""")

    fun detect(n: NotifInput, cfg: Config): Detection {
        val subject = n.title.trim().replace(Regex("\\s+"), " ").take(80).ifEmpty { "Teams" }
        val all = (n.title + "\n" + n.text).lowercase()
        fun ignore(why: String) = Detection(Kind.IGNORE, null, subject, why)

        val cat = n.category
        val incoming = cat == "missed_call" || n.hasFullScreenIntent || CALL_INCOMING.containsMatchIn(all)
        // Only Teams' own pinned (ongoing) notification counts: a chat saying "I'm in a call" is about someone else.
        if (!incoming && n.ongoing && (cat == "call" || IN_CALL.containsMatchIn(all))) {
            return Detection(Kind.IN_CALL, null, subject, "you're in a Teams call/meeting")
        }

        IGNORE.firstOrNull { all.contains(it) }?.let { return ignore("not a new meeting (\"$it\")") }

        if (cat == "call" || cat == "missed_call") {
            if (!cfg.ringForCalls) return ignore("calls are turned off in settings")
            return if (incoming) {
                Detection(Kind.RING, null, subject, if (cat == "missed_call") "missed Teams call" else "incoming Teams call", isCall = true)
            } else {
                ignore("call update, not a new call")
            }
        }

        if (CANCEL.containsMatchIn(all) && MEETING_WORD.containsMatchIn(all)) {
            return Detection(Kind.CANCEL, null, subject, "meeting cancelled")
        }

        val strong = STRONG.firstOrNull { it.containsMatchIn(all) }
        val custom = cfg.extraKeywords.map { it.trim().lowercase() }.firstOrNull { it.isNotEmpty() && all.contains(it) }
        val broad = cfg.broadChat && BROAD.containsMatchIn(all)
        val byCategory = cat == "event" || cat == "reminder"
        if (strong == null && custom == null && !broad && !byCategory) return ignore("no meeting keywords")
        if (!cfg.ringForCalls && strong != null && strong.pattern.contains("calling") && !all.contains("meeting")) {
            return ignore("calls are turned off in settings")
        }

        val why = when {
            strong != null -> "meeting notification"
            byCategory -> "Teams event/reminder"
            custom != null -> "keyword \"$custom\""
            else -> "chat mentions a meeting/call"
        }
        val resched = RESCHEDULE.containsMatchIn(all)
        val callish = strong?.pattern?.contains("calling") == true
        return when (val w = TimeParser.parse(all, n.postedAt)) {
            is When.At -> {
                if (w.time.isBefore(n.postedAt.minusMinutes(60))) ignore("meeting time ${w.time} is in the past")
                else Detection(Kind.RING, w.time, subject, "$why at ${w.time.toLocalTime()}", resched)
            }
            is When.DayOnly -> ignore("meeting on ${w.date} but no time given; Teams will remind you nearer the time")
            When.Now, When.Unknown -> Detection(Kind.RING, null, subject, "$why (no time given: ring now)", resched, isCall = callish)
        }
    }
}

sealed class When {
    data class At(val time: LocalDateTime) : When()
    data class DayOnly(val date: LocalDate) : When()
    data object Now : When()
    data object Unknown : When()
}

/** Finds a meeting time in English notification text. Input should be lower-case. */
object TimeParser {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val RELATIVE = rx("""\b(?:in|within)\s+(\d{1,3})\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours)\b""")
    private val RELATIVE_WORDS = rx("""\bin (half an hour|an hour|a minute|a few minutes|a couple of minutes)\b""")
    private val NOW_WORDS = rx("""\b(now|right away|asap|started|has started|is calling|incoming|calling you)\b""")

    private const val MER = """([ap])\.?\s?m\b\.?"""
    private val RANGE = rx("""\b(1[0-2]|0?[1-9])(?:[:.]([0-5]\d))?\s*(?:([ap])\.?\s?m\.?)?\s*(?:-|–|—|to|until)\s*(?:1[0-2]|0?[1-9])(?:[:.][0-5]\d)?\s*$MER""")
    private val CLOCK_12 = rx("""\b(1[0-2]|0?[1-9])(?:[:.]([0-5]\d))?\s*$MER""")
    private val CLOCK_24 = rx("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val NOON = rx("""\b(noon|midday)\b""")
    private val AT_HOUR = rx("""\bat\s+(1[0-2]|0?[1-9])\b(?![:.]?\d|\s*%)""")

    private val TOMORROW = rx("""\b(tomorrow|tmrw|tmr|tomo)\b""")
    private val TODAY = rx("""\b(today|tonight|this (morning|afternoon|evening))\b""")

    private const val MONTHS =
        "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec"
    private val MONTH_DAY = rx("""\b($MONTHS)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""")
    private val DAY_MONTH = rx("""\b(\d{1,2})(?:st|nd|rd|th)?\s+($MONTHS)\b""")
    private val WEEKDAY_FULL = rx("""\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
    private val WEEKDAY_SHORT = rx("""\b(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b(?=[,.]|\s+\d|\s+($MONTHS)\b)""")

    fun parse(text: String, now: LocalDateTime): When {
        RELATIVE.find(text)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2]
            return When.At(if (unit.startsWith("h")) now.plusHours(n) else now.plusMinutes(n))
        }
        RELATIVE_WORDS.find(text)?.let { m ->
            val mins = when (m.groupValues[1]) {
                "half an hour" -> 30L
                "an hour" -> 60L
                "a few minutes" -> 5L
                "a couple of minutes" -> 2L
                else -> 1L
            }
            return When.At(now.plusMinutes(mins))
        }

        val date = findDate(text, now.toLocalDate())
        val time = findTime(text)

        if (time != null) {
            if (date != null) return When.At(date.atTime(time))
            var at = now.toLocalDate().atTime(time)
            if (at.isBefore(now.minusMinutes(60))) at = at.plusDays(1)
            return When.At(at)
        }
        findLooseHour(text, now)?.let { return When.At(if (date != null) date.atTime(it.toLocalTime()) else it) }

        if (date != null) {
            return if (date.isAfter(now.toLocalDate())) When.DayOnly(date) else When.Now
        }
        if (NOW_WORDS.containsMatchIn(text)) return When.Now
        return When.Unknown
    }

    private fun to24(h: Int, mer: String?): Int = when (mer) {
        "a" -> if (h == 12) 0 else h
        "p" -> if (h == 12) 12 else h + 12
        else -> h
    }

    private fun findTime(text: String): LocalTime? {
        val candidates = ArrayList<Pair<Int, LocalTime>>()
        RANGE.find(text)?.let { m ->
            val mer = m.groupValues[3].ifEmpty { m.groupValues[4] }
            val h = to24(m.groupValues[1].toInt(), mer)
            candidates += m.range.first to LocalTime.of(h, m.groupValues[2].ifEmpty { "0" }.toInt())
        }
        CLOCK_12.find(text)?.let { m ->
            val h = to24(m.groupValues[1].toInt(), m.groupValues[3])
            candidates += m.range.first to LocalTime.of(h, m.groupValues[2].ifEmpty { "0" }.toInt())
        }
        if (candidates.isEmpty()) {
            CLOCK_24.find(text)?.let { m ->
                candidates += m.range.first to LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        }
        NOON.find(text)?.let { candidates += it.range.first to LocalTime.NOON }
        return candidates.minByOrNull { it.first }?.second
    }

    /** "let's meet at 3": pick whichever of 3 am / 3 pm comes next. */
    private fun findLooseHour(text: String, now: LocalDateTime): LocalDateTime? {
        val m = AT_HOUR.find(text) ?: return null
        val h = m.groupValues[1].toInt() % 12
        val floor = now.minusMinutes(60)
        return listOf(0L, 12L, 24L, 36L)
            .map { now.toLocalDate().atTime(h, 0).plusHours(it) }
            .first { !it.isBefore(floor) }
    }

    private fun findDate(text: String, today: LocalDate): LocalDate? {
        MONTH_DAY.find(text)?.let { m -> return monthDay(m.groupValues[1], m.groupValues[2].toInt(), today) }
        DAY_MONTH.find(text)?.let { m -> return monthDay(m.groupValues[2], m.groupValues[1].toInt(), today) }
        if (TOMORROW.containsMatchIn(text)) return today.plusDays(1)
        if (TODAY.containsMatchIn(text)) return today
        val wd = WEEKDAY_FULL.find(text)?.groupValues?.get(1) ?: WEEKDAY_SHORT.find(text)?.groupValues?.get(1)
        if (wd != null) {
            val target = weekday(wd) ?: return null
            val diff = Math.floorMod(target.value - today.dayOfWeek.value, 7)
            return today.plusDays(diff.toLong())
        }
        return null
    }

    private fun weekday(s: String): DayOfWeek? = when (s.take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun monthDay(month: String, day: Int, today: LocalDate): LocalDate? {
        val idx = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            .indexOf(month.take(3))
        if (idx < 0 || day !in 1..31) return null
        val d = runCatching { LocalDate.of(today.year, idx + 1, day) }.getOrNull() ?: return null
        return if (ChronoUnit.DAYS.between(d, today) > 7) d.plusYears(1) else d
    }
}
