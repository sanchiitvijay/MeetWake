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
    /** Who sent the latest chat message (MessagingStyle sender), when Teams provides it. */
    val senders: List<String> = emptyList(),
    /** Teams marked it as a chat message (CATEGORY_MESSAGE or MessagingStyle). */
    val isMessage: Boolean = false,
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

    /** Notifications about something that is over or not for us (English, Hinglish, Hindi). */
    private val IGNORE = listOf(
        "meeting ended", "call ended", "ended the meeting", "ended the call", "left the meeting",
        "declined", "accepted your", "tentatively accepted", "recording is ready", "recording is available",
        "transcript", "recap", "meeting notes", "reacted to", "liked your",
        "meeting khatam", "call khatam", "meeting ho gayi", "meeting ho gyi", "meeting over",
        "मीटिंग खत्म", "मीटिंग समाप्त", "कॉल खत्म", "कॉल समाप्त",
    )

    /** Teams' notification while you're in a call or meeting. */
    private val IN_CALL = rx("""\b(you're now in|you are now in|you're in a|you are in a|in call|ongoing call|call in progress|meeting in progress|tap to return|return to (the )?(call|meeting))\b""")

    private val MEETING_WORD = rx("""\b(meeting|meet|call|event|invite|invitation|webinar|town ?hall|stand-?up|scrum|huddle|sync)\b|मीटिंग|कॉल|बैठक""")
    private val CANCEL = rx("""\b(cancel(l)?ed|cancel(l)?ing|canceled|cancel (ho|kar|kr)\w*|postpone(d)?)\b|रद्द|कैंसल""")
    private val RESCHEDULE = rx("""\b(rescheduled|updated|moved|time changed|new time)\b""")

    /** Phrases Teams uses for meeting invites, reminders and starts. */
    private val STRONG = listOf(
        rx("""\bmeeting (started|has started|is starting|starting|starts|reminder|invite|invitation|request)\b"""),
        rx("""\bstarted (a|the) (meeting|call)\b"""),
        rx("""\b(starts|starting|begins|beginning) (in|now|soon|at)\b"""),
        rx("""\bmeet now\b"""),
        rx("""\b(invited you|sent you an? (meeting )?invit\w*|scheduled (a|the|an?) \w*\s?meeting|new meeting)\b"""),
        rx("""\bjoin (the |this |our |my )?(meeting|call|teams meeting|now|link|bridge)\b"""),
        rx("""\b(please|pls|plz|kindly|can you|could you|can u) (join|hop on|jump on|come on)\b"""),
        rx("""\b(waiting|everyone is waiting|we're waiting|we are waiting|all are waiting) (for|on) (you|u)\b"""),
        rx("""\b(you're|you are|u r|ur) (needed|required|expected) (in|on) (the )?(call|meeting)\b"""),
        rx("""\bmeeting (link|is live|has begun|is on|chal rahi|chal rhi|shuru|start ho|chalu)\b"""),
        rx("""\b(dial|dialing) in\b"""),
        // Hinglish
        rx("""\b(meeting|call) join ?(karo|kro|kar lo|krlo|karlo|kariye|kijiye|kar)\b"""),
        rx("""\b(meeting|call) (pe|par|me|mein|mai|mei) (aao|aa jao|aaja|aa jaiye|aaiye|aa|join)\b"""),
        rx("""\b(jaldi|jldi|jaldi se) (join|aao|aa jao|aaja|call|online)\b"""),
        rx("""\bjoin (karo|kro|kar lo|krlo|karlo|kariye|kijiye)\b"""),
        rx("""\b(sab|sabhi|everyone) (wait|intezaar|intezar) (kar rahe|kr rhe|kar rhe)\b"""),
        rx("""\b(is calling|incoming (video |audio )?call|missed (video |audio )?call|calling you)\b"""),
        rx("""\b(webinar|town ?hall|live event)\b"""),
        rx("""\b(updated|rescheduled|moved) (the )?meeting\b"""),
    )

    /** Hindi (Devanagari) phrases that clearly ask you to a meeting/call. Plain substrings: \b doesn't work for Devanagari. */
    private val STRONG_HI = listOf(
        "मीटिंग शुरू", "मीटिंग प्रारंभ", "मीटिंग आरंभ", "मीटिंग में आओ", "मीटिंग में आइए", "मीटिंग जॉइन", "मीटिंग ज्वाइन",
        "आमंत्रित", "कॉल कर रहे हैं", "इनकमिंग कॉल", "मिस्ड कॉल", "जल्दी आओ", "जल्दी जुड़ो", "जल्दी जॉइन",
        "कॉल पर आओ", "कॉल पे आओ", "कॉल में आओ", "जॉइन करो", "ज्वाइन करो", "जुड़ जाओ", "जुड़िए", "इंतज़ार कर रहे", "इंतजार कर रहे",
    )

    /** Looser words used when "chat messages" detection is on (English + Hinglish). */
    private val BROAD = rx(
        """\b(meeting|meet|call|calling|join|joining|huddle|stand-?up|scrum|sync up|hop on|jump on|""" +
            """are you there|you there|r u there|where are you|where r u|urgent|asap|quick (call|chat|sync)|""" +
            """can we talk|can you talk|got a (min|minute|sec|second)|ping me|call me|connect now|teams link|""" +
            // Hinglish
            """call (karo|kro|kar|karna|krna|karte|lo|uthao|utha|pe|par)|phone (uthao|utha|karo|kro)|""" +
            """baat (karni|krni|karte|karo|kro|karna)|(kahan|kaha|kidhar|kidar) (ho|hai|h)|""" +
            """online (aao|aa jao|aaja|ho|aa)|uth (jao|ja)|utho|jaago|jago|abhi aao|free ho|jaldi|jldi|connect (karo|kro|karte))\b"""
    )

    /** Hindi (Devanagari) words for chat detection. */
    private val BROAD_HI = listOf(
        "मीटिंग", "कॉल", "बैठक", "बात करनी", "बात करो", "बात करते", "कहाँ हो", "कहां हो", "ऑनलाइन", "उठो", "उठ जाओ",
        "जल्दी", "फ्री हो", "फोन उठाओ", "फ़ोन उठाओ", "जुड़", "जॉइन", "ज्वाइन", "अर्जेंट",
    )

    private val CALL_INCOMING = rx("""\b(is calling|incoming|calling you|ringing|missed)\b""")

    /**
     * The friend from [Config.friends] who sent this, if any. Case-insensitive substring match against
     * the chat sender (or the title when Teams gives no sender) and any "Name: message" line prefix.
     */
    fun friendMatch(n: NotifInput, friends: List<String>): String? {
        val wanted = friends.map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty() || !isChatMessage(n)) return null
        val names = ArrayList<String>()
        if (n.senders.isNotEmpty()) names += n.senders else names += n.title
        n.text.lineSequence().forEach { line ->
            val i = line.indexOf(':')
            if (i in 1..40) names += line.substring(0, i)
        }
        return wanted.firstOrNull { f -> names.any { it.contains(f, ignoreCase = true) } }
    }

    /** Teams' own meeting notifications (reminders, invites, starts): never treated as a chat message. */
    private val MEETING_SYSTEM = listOf(
        rx("""\bmeeting (started|has started|is starting|starting|starts|reminder|invite|invitation|request|updated|canceled|cancelled)\b"""),
        rx("""\bstarted (a|the) meeting\b"""),
        rx("""\b(starts|starting|begins|beginning) (in|now|soon|at)\b"""),
        rx("""\b(invited you|sent you an? (meeting )?invit\w*|scheduled (a|the|an?) \w*\s?meeting|new meeting|meet now)\b"""),
        rx("""\b(updated|rescheduled|moved|canceled|cancelled) (the )?meeting\b|^(canceled|cancelled|updated):"""),
        rx("""मीटिंग शुरू|मीटिंग प्रारंभ|मीटिंग आरंभ|आमंत्रित"""),
    )

    /**
     * Is this a chat message (where the friends list applies)? Meeting reminders, invites, starts and
     * calls never are. When Teams gives no type, anything that doesn't read like a meeting notification counts.
     */
    fun isChatMessage(n: NotifInput): Boolean {
        val cat = n.category
        if (cat == "event" || cat == "reminder" || cat == "call" || cat == "missed_call" || n.ongoing) return false
        if (n.isMessage || cat == "msg" || n.senders.isNotEmpty()) return true
        val all = (n.title + "\n" + n.text).lowercase()
        return MEETING_SYSTEM.none { it.containsMatchIn(all) }
    }

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

        friendMatch(n, cfg.friends)?.let { return ignore("chat message from a friend (\"$it\")") }

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
            ?: STRONG_HI.firstOrNull { all.contains(it) }?.let { rx(Regex.escape(it)) }
        val custom = cfg.extraKeywords.map { it.trim().lowercase() }.firstOrNull { it.isNotEmpty() && all.contains(it) }
        val broad = cfg.broadChat && (BROAD.containsMatchIn(all) || BROAD_HI.any { all.contains(it) })
        val byCategory = cat == "event" || cat == "reminder"
        if (strong == null && custom == null && !broad && !byCategory) return ignore("no meeting keywords")
        if (!cfg.ringForCalls && strong != null && strong.pattern.contains("is calling") && !all.contains("meeting")) {
            return ignore("calls are turned off in settings")
        }

        val why = when {
            strong != null -> "meeting notification"
            byCategory -> "Teams event/reminder"
            custom != null -> "keyword \"$custom\""
            else -> "chat mentions a meeting/call"
        }
        val resched = RESCHEDULE.containsMatchIn(all)
        val callish = strong?.pattern?.contains("is calling") == true || all.contains("कॉल कर रहे")
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

/** Finds a meeting time in English, Hinglish or Hindi notification text. Input should be lower-case. */
object TimeParser {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val RELATIVE = rx("""\b(?:in|within)\s+(\d{1,3})\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours)\b""")
    private val RELATIVE_WORDS = rx("""\bin (half an hour|an hour|a minute|a few minutes|a couple of minutes)\b""")
    private val NOW_WORDS = rx("""\b(now|right away|asap|started|has started|is calling|incoming|calling you|abhi|abi|turant|jaldi|jldi)\b|अभी|तुरंत|जल्दी""")
    private val RELATIVE_HI = rx("""\b(\d{1,3})\s*(min|mins|minute|minutes|minat|mint|ghante|ghanta|ghnte|hr|hrs|hour|hours)\s*(me|mein|mai|mei|baad|bad)\b""")
    private val RELATIVE_DEV = rx("""(\d{1,3})\s*(मिनट|घंटे|घंटा)\s*(में|बाद)""")
    private val BAJE = rx("""\b(1[0-2]|0?[1-9])(?:[:.]([0-5]\d))?\s*(?:baje|bje|बजे)""")
    private val AM_HINT = rx("""\b(subah|subha|savere|morning)\b|सुबह""")
    private val PM_HINT = rx("""\b(dopahar|dopehar|dupahar|shaam|sham|raat|evening|afternoon)\b|दोपहर|शाम|रात""")

    private const val MER = """([ap])\.?\s?m\b\.?"""
    private val RANGE = rx("""\b(1[0-2]|0?[1-9])(?:[:.]([0-5]\d))?\s*(?:([ap])\.?\s?m\.?)?\s*(?:-|–|—|to|until)\s*(?:1[0-2]|0?[1-9])(?:[:.][0-5]\d)?\s*$MER""")
    private val CLOCK_12 = rx("""\b(1[0-2]|0?[1-9])(?:[:.]([0-5]\d))?\s*$MER""")
    private val CLOCK_24 = rx("""\b([01]?\d|2[0-3]):([0-5]\d)\b(?!\s*(?:baje|bje|बजे))""")
    private val NOON = rx("""\b(noon|midday)\b""")
    private val AT_HOUR = rx("""\bat\s+(1[0-2]|0?[1-9])\b(?![:.]?\d|\s*%)""")

    private val TOMORROW = rx("""\b(tomorrow|tmrw|tmr|tomo|kal)\b|कल""")
    private val TODAY = rx("""\b(today|tonight|this (morning|afternoon|evening)|aaj)\b|आज""")

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
        (RELATIVE_HI.find(text) ?: RELATIVE_DEV.find(text))?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2]
            val hours = unit.startsWith("gh") || unit.startsWith("h") || unit.startsWith("घ")
            return When.At(if (hours) now.plusHours(n) else now.plusMinutes(n))
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
        val time = findBaje(text) ?: findTime(text)

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

    /** "10 baje" / "shaam 5 baje" / "5 बजे". Without a morning/evening hint returns null (see findLooseHour). */
    private fun findBaje(text: String): LocalTime? {
        val m = BAJE.find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return when {
            PM_HINT.containsMatchIn(text) -> LocalTime.of(if (h == 12) 12 else h + 12, min)
            AM_HINT.containsMatchIn(text) -> LocalTime.of(if (h == 12) 0 else h, min)
            else -> null
        }
    }

    /** "let's meet at 3" / "3 baje": pick whichever of 3 am / 3 pm comes next. */
    private fun findLooseHour(text: String, now: LocalDateTime): LocalDateTime? {
        val m = AT_HOUR.find(text) ?: BAJE.find(text) ?: return null
        val h = m.groupValues[1].toInt() % 12
        val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val floor = now.minusMinutes(60)
        return listOf(0L, 12L, 24L, 36L)
            .map { now.toLocalDate().atTime(h, min).plusHours(it) }
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
