package com.meetwake.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.meetwake.app.core.Config
import com.meetwake.app.core.Kind
import com.meetwake.app.core.MeetingParser
import com.meetwake.app.core.NotifInput
import com.meetwake.app.core.Planner
import com.meetwake.app.core.TimeRange
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Status, setup checklist and all settings on one scrolling screen. */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private val reqNotif = 1
    private val reqRingtone = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Alarms.rescheduleAll(this)
        render()
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        root.removeAllViews()
        val cfg = Store.config(this)
        val p = Store.planner(this, cfg)
        val now = Store.now()

        root.addView(text("MeetWake", 28f, R.color.text, bold = true).apply { setPadding(dp(4), dp(8), 0, 0) })
        root.addView(text("Wakes you up when a Microsoft Teams meeting comes in", 14f, R.color.sub).apply { setPadding(dp(4), 0, 0, dp(4)) })

        statusCard(cfg, p, now)
        setupCard()
        alarmsCard()
        daysCard(cfg)
        hoursCard(cfg)
        awakeCard(cfg)
        alarmCard(cfg)
        detectionCard(cfg)
        testCard()
        logCard()

        root.addView(text(
            "Works offline: MeetWake only reacts to notifications Teams already shows on this phone. " +
                "Outside your work hours it disconnects and uses no battery.",
            12f, R.color.sub,
        ).apply { setPadding(dp(4), dp(20), dp(4), 0) })
    }

    private fun statusCard(cfg: Config, p: Planner, now: LocalDateTime) {
        val card = card(null)
        val granted = Perms.listenerGranted(this)
        val (dot, line) = when {
            !cfg.enabled -> R.color.warn to "MeetWake is turned off"
            !granted -> R.color.warn to "Needs notification access — see Setup below"
            p.isListening(now) -> R.color.ok to "Listening for Teams meetings until ${Fmt.time(this, p.shiftEnd(p.shiftDateFor(now)!!))}"
            else -> R.color.sub to (nextListening(p, now)?.let { "Paused — starts listening ${Fmt.dayTime(this, it)}" }
                ?: "Paused — no work-from-home days coming up")
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(dot)) }
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
            addView(text(line, 16f, R.color.text, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        card.switchRow("MeetWake enabled", null, cfg.enabled) { v -> update { it.copy(enabled = v) } }
        card.divider()

        val cur = p.currentOrNextShift(now)
        val next = cur.plusDays(1)
        for (d in listOf(cur, next)) {
            val sub = if (Store.hasOverride(this, d)) "Your answer for this day" else "From your WFH days"
            card.switchRow("Working from home ${dayLabel(d)}", sub, p.isWfh(d)) { v ->
                Store.setWfh(this, d, v); Alarms.rescheduleAll(this); render()
            }
        }
        card.divider()

        val awake = p.isAwake(now, cur, Store.awakeAt(this))
        if (awake) {
            card.row("You're marked awake", "Re-arm", "Alarms stay quiet until your next nap or shift") {
                Store.setAwake(this, null); render()
            }
        } else {
            card.row("Alarms are armed", "I'm awake", "Tap when you're up and alarms stop until your next nap") {
                Store.setAwake(this, Store.now()); Notifs.cancel(this, Notifs.ID_AWAKE); render()
            }
        }
    }

    private fun nextListening(p: Planner, now: LocalDateTime): LocalDateTime? {
        var d = p.currentOrNextShift(now)
        repeat(14) {
            if (p.isWfh(d) && p.shiftStart(d).isAfter(now)) return p.shiftStart(d)
            d = d.plusDays(1)
        }
        return null
    }

    private fun setupCard() {
        data class Item(val ok: Boolean, val title: String, val sub: String, val fix: () -> Unit)
        val items = buildList {
            add(Item(Perms.listenerGranted(this@MainActivity), "Notification access",
                "Needed to see Teams notifications. Only Teams is read. If the switch is greyed out: " +
                    "App info → ⋮ → Allow restricted settings, then try again.") { openListenerSettings() })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Item(Perms.notificationsGranted(this@MainActivity), "Show notifications",
                    "Needed to show the alarm and the questions.") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif) })
            }
            if (Build.VERSION.SDK_INT >= 34) {
                add(Item(Perms.fullScreenGranted(this@MainActivity), "Full-screen alarms",
                    "Lets the alarm take over the lock screen.") {
                    start(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                })
            }
            if (!Alarms.canExact(this@MainActivity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Item(false, "Exact alarms", "Needed so alarms ring on time.") {
                    start(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                })
            }
            add(Item(Perms.batteryUnrestricted(this@MainActivity), "Battery: unrestricted (recommended)",
                "Stops some phones from killing the app. MeetWake has no background work outside your hours.") {
                @Suppress("BatteryLife")
                start(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            })
        }
        val missing = items.filter { !it.ok }
        val card = card("Setup")
        if (!Perms.teamsInstalled(this)) {
            card.row("Microsoft Teams not found", null, "Install Teams and turn on its notifications.", R.color.warn)
            card.divider()
        }
        if (missing.isEmpty()) {
            card.row("✓ Everything is set up", null, null, R.color.ok)
            return
        }
        missing.forEachIndexed { i, it ->
            if (i > 0) card.divider()
            card.row(it.title, "Allow", it.sub) { it.fix() }
        }
        card.divider()
        card.row("Open app info", "Open", "For \"Allow restricted settings\" on sideloaded apps") {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun alarmsCard() {
        val list = Store.alarms(this)
        val card = card("Upcoming alarms")
        if (list.isEmpty()) {
            card.row("No meeting alarms set", null, "They appear here when a Teams meeting is detected.")
            return
        }
        list.forEachIndexed { i, a ->
            if (i > 0) card.divider()
            val sub = if (a.test) "Rings ${Fmt.dayTime(this, a.fireAt)}"
            else "Rings ${Fmt.dayTime(this, a.fireAt)} · meeting ${Fmt.time(this, a.meetingAt)}"
            card.row(a.subject, "Cancel", sub) { Engine.cancelAlarm(this, a.id); render() }
        }
    }

    private fun daysCard(cfg: Config) {
        val card = card("Work-from-home days")
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        val first = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
        for (i in 0 until 7) {
            val day = first.plus(i.toLong())
            val on = day in cfg.wfhDays
            chips.addView(TextView(this).apply {
                text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3)
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(if (on) R.color.on_accent else R.color.text))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(getColor(if (on) R.color.accent else R.color.chip)) }
                setOnClickListener { update { c -> c.copy(wfhDays = if (on) c.wfhDays - day else c.wfhDays + day) } }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = if (i < 6) dp(5) else 0 })
        }
        card.addView(chips)
        card.switchRow("Ask me every evening", "A notification asks if you're working from home the next day", cfg.eveningPrompt) { v ->
            update { it.copy(eveningPrompt = v) }
        }
        if (cfg.eveningPrompt) {
            card.row("Ask at", Fmt.minutes(this, cfg.eveningPromptMin)) {
                pickTime(cfg.eveningPromptMin) { m -> update { it.copy(eveningPromptMin = m) } }
            }
        }
    }

    private fun hoursCard(cfg: Config) {
        val card = card("Work hours")
        card.row("Start", Fmt.minutes(this, cfg.work.start), "Teams is only read during work hours on WFH days") {
            pickTime(cfg.work.start) { m -> update { it.copy(work = TimeRange(m, it.work.end)) } }
        }
        card.divider()
        card.row("End", Fmt.minutes(this, cfg.work.end) + if (cfg.work.end <= cfg.work.start) " (next day)" else "") {
            pickTime(cfg.work.end) { m -> update { it.copy(work = TimeRange(it.work.start, m)) } }
        }
        card.divider()
        cfg.naps.forEachIndexed { i, n ->
            card.row("Nap ${i + 1}", "${Fmt.minutes(this, n.start)} – ${Fmt.minutes(this, n.end)}", "Alarms re-arm when the nap starts") {
                AlertDialog.Builder(this).setTitle("Nap ${i + 1}")
                    .setItems(arrayOf("Change time", "Remove")) { _, which ->
                        if (which == 0) pickRange(n) { r -> update { c -> c.copy(naps = c.naps.toMutableList().also { it[i] = r }) } }
                        else update { c -> c.copy(naps = c.naps.filterIndexed { j, _ -> j != i }) }
                    }.show()
            }
            card.divider()
        }
        card.row("+ Add a nap period", null, "e.g. an afternoon nap: alarms ring again during it") {
            pickRange(TimeRange(14 * 60, 15 * 60)) { r -> update { it.copy(naps = it.naps + r) } }
        }
    }

    private fun awakeCard(cfg: Config) {
        val card = card("Are-you-awake check")
        card.switchRow("Ask if I'm awake", "Tap \"I'm awake\" on it and alarms stop until your next nap", cfg.awakeCheck) { v ->
            update { it.copy(awakeCheck = v) }
        }
        if (cfg.awakeCheck) {
            card.row("Ask at", Fmt.minutes(this, cfg.awakeCheckMin)) {
                pickTime(cfg.awakeCheckMin) { m -> update { it.copy(awakeCheckMin = m) } }
            }
        }
        if (cfg.naps.isNotEmpty()) {
            card.divider()
            card.switchRow("Ask again when a nap ends", null, cfg.napEndCheck) { v -> update { it.copy(napEndCheck = v) } }
        }
    }

    private fun alarmCard(cfg: Config) {
        val card = card("Alarm")
        card.row("Ring before the meeting", "${cfg.leadMin} min") {
            pickNumber("Minutes before the meeting", 0, 60, cfg.leadMin) { v -> update { it.copy(leadMin = v) } }
        }
        card.divider()
        card.row("Snooze length", "${cfg.snoozeMin} min") {
            pickNumber("Snooze minutes", 1, 30, cfg.snoozeMin) { v -> update { it.copy(snoozeMin = v) } }
        }
        card.divider()
        card.row("Ring for up to", "${cfg.ringTimeoutMin} min", "If untouched it snoozes and rings again (3 times)") {
            pickNumber("Ring for (minutes)", 1, 30, cfg.ringTimeoutMin) { v -> update { it.copy(ringTimeoutMin = v) } }
        }
        card.divider()
        card.row("Sound", ringtoneName(cfg.ringtoneUri)) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cfg.ringtoneUri?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI),
                reqRingtone)
        }
        card.divider()
        card.switchRow("Vibrate", null, cfg.vibrate) { v -> update { it.copy(vibrate = v) } }
        card.switchRow("Full alarm volume", "Turns alarm volume to max while ringing, then puts it back", cfg.maxVolume) { v ->
            update { it.copy(maxVolume = v) }
        }
    }

    private fun detectionCard(cfg: Config) {
        val card = card("What counts as a meeting")
        card.row("Always", null, "Meeting invites, reminders (\"starts in 5 min\"), \"meeting started\", Meet now")
        card.divider()
        card.switchRow("Teams calls", "Incoming and missed calls", cfg.ringForCalls) { v -> update { it.copy(ringForCalls = v) } }
        card.switchRow("Chat messages about a meeting", "e.g. \"can you join the call?\" — may ring for casual mentions too", cfg.broadChat) { v ->
            update { it.copy(broadChat = v) }
        }
        card.divider()
        card.row("Extra keywords", cfg.extraKeywords.joinToString().ifEmpty { "None" }, "Comma-separated, e.g. standup, client") {
            val input = EditText(this).apply {
                setText(cfg.extraKeywords.joinToString(", "))
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "standup, client, urgent"
            }
            AlertDialog.Builder(this).setTitle("Extra keywords").setView(padded(input))
                .setPositiveButton("Save") { _, _ ->
                    update { it.copy(extraKeywords = input.text.toString().split(",").map(String::trim).filter(String::isNotEmpty)) }
                }.setNegativeButton("Cancel", null).show()
        }
        card.divider()
        card.switchRow("Stay connected outside work hours",
            "Off (lightest): the listener disconnects outside your work hours", cfg.keepConnected) { v ->
            update { it.copy(keepConnected = v) }
        }
    }

    private fun testCard() {
        val card = card("Try it")
        card.row("Test alarm", "Ring in 10 s", "Lock the phone to see the full-screen alarm") {
            Engine.testAlarm(this)
            Toast.makeText(this, "Alarm rings in 10 seconds", Toast.LENGTH_SHORT).show()
            render()
        }
        card.divider()
        card.row("Test a Teams notification", "Try", "Type what a notification says and see what MeetWake would do") { testDialog() }
    }

    private fun logCard() {
        val entries = Store.logEntries(this)
        val card = card("Recent Teams notifications")
        if (entries.isEmpty()) {
            card.row("Nothing yet", null, "Teams notifications seen during work hours show up here")
            return
        }
        entries.take(15).forEachIndexed { i, e ->
            if (i > 0) card.divider()
            val head = listOf(e.title, e.text.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(": ")
            card.row(head.ifEmpty { "(no text)" }, null, "${Fmt.dayTime(this, e.at)} · ${e.outcome}")
        }
        card.divider()
        card.row("Clear list", "Clear") { Store.clearLog(this); render() }
    }

    // ------------------------------------------------------------------ dialogs

    private fun testDialog() {
        val title = EditText(this).apply { hint = "Title (e.g. Sprint planning)"; setText("Sprint planning") }
        val body = EditText(this).apply { hint = "Text (e.g. Starts in 15 minutes)"; setText("Meeting starts in 15 minutes") }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(body) }
        AlertDialog.Builder(this).setTitle("Test a Teams notification").setView(padded(box))
            .setPositiveButton("Check") { _, _ ->
                val n = NotifInput(title.text.toString(), body.text.toString(), postedAt = Store.now())
                val cfg = Store.config(this)
                val det = MeetingParser.detect(n, cfg)
                val msg = StringBuilder()
                when (det.kind) {
                    Kind.IGNORE -> msg.append("Would be ignored: ${det.reason}")
                    Kind.CANCEL -> msg.append("Would cancel a matching alarm (meeting cancelled).")
                    Kind.IN_CALL -> msg.append("You're in a Teams call: any ringing alarm stops and you're marked awake.")
                    Kind.RING -> {
                        val at = det.meetingAt
                        if (at == null) msg.append("Would ring right away.\n\nWhy: ${det.reason}")
                        else msg.append("Meeting at ${Fmt.dayTime(this, at)}.\nAlarm at ${Fmt.dayTime(this, at.minusMinutes(cfg.leadMin.toLong()))}.\n\nWhy: ${det.reason}")
                        val v = Store.planner(this, cfg).verdict(at ?: Store.now(), Store.awakeAt(this))
                        if (!v.ring) msg.append("\n\nRight now it would not ring: ${v.reason}.")
                    }
                }
                val d = AlertDialog.Builder(this).setTitle("Result").setMessage(msg).setNegativeButton("Close", null)
                if (det.kind != Kind.IGNORE) {
                    d.setPositiveButton("Do it for real") { _, _ ->
                        Toast.makeText(this, Engine.apply(this, n, det), Toast.LENGTH_LONG).show()
                        render()
                    }
                }
                d.show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickTime(min: Int, cb: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> cb(h * 60 + m) }, min / 60, min % 60, DateFormat.is24HourFormat(this)).show()
    }

    private fun pickRange(r: TimeRange, cb: (TimeRange) -> Unit) {
        Toast.makeText(this, "Nap starts at…", Toast.LENGTH_SHORT).show()
        pickTime(r.start) { s ->
            Toast.makeText(this, "Nap ends at…", Toast.LENGTH_SHORT).show()
            pickTime(r.end) { e -> if (e != s) cb(TimeRange(s, e)) }
        }
    }

    private fun pickNumber(title: String, min: Int, max: Int, cur: Int, cb: (Int) -> Unit) {
        val np = NumberPicker(this).apply { minValue = min; maxValue = max; value = cur.coerceIn(min, max); wrapSelectorWheel = false }
        AlertDialog.Builder(this).setTitle(title).setView(np)
            .setPositiveButton("OK") { _, _ -> cb(np.value) }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ results

    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqRingtone && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val value = uri?.takeIf { it != Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString()
            update { it.copy(ringtoneUri = value) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqNotif && !Perms.notificationsGranted(this) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        render()
    }

    // ------------------------------------------------------------------ helpers

    private fun update(f: (Config) -> Config) {
        Store.saveConfig(this, f(Store.config(this)))
        Alarms.rescheduleAll(this)
        render()
    }

    private fun openListenerSettings() {
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, TeamsListenerService.component(this).flattenToString())
        } else null
        if (detail == null || !start(detail)) start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun start(i: Intent): Boolean = try {
        startActivity(i); true
    } catch (_: Exception) {
        Toast.makeText(this, "Couldn't open that settings page on this phone", Toast.LENGTH_SHORT).show(); false
    }

    private fun ringtoneName(uri: String?): String {
        if (uri == null) return "Default alarm"
        return runCatching { RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this) }.getOrNull() ?: "Custom"
    }

    private fun dayLabel(d: LocalDate): String {
        val today = LocalDate.now(Store.zone)
        val fmt = d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        return when (d) {
            today -> "today ($fmt)"
            today.plusDays(1) -> "tomorrow ($fmt)"
            else -> "on $fmt"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun card(title: String?): LinearLayout {
        if (title != null) {
            root.addView(text(title.uppercase(), 12f, R.color.sub, bold = true).apply {
                letterSpacing = 0.08f
                setPadding(dp(4), dp(22), 0, dp(8))
            })
        } else {
            root.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(getColor(R.color.card)) }
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        root.addView(card)
        return card
    }

    private fun LinearLayout.divider() {
        addView(View(context).apply { setBackgroundColor(getColor(R.color.divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun selectable(v: View) {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.background = a.getDrawable(0)
        a.recycle()
    }

    private fun LinearLayout.row(title: String, value: String?, sub: String? = null, titleColor: Int = R.color.text, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(10), 0, dp(10))
        }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(title, 16f, titleColor))
        if (sub != null) left.addView(text(sub, 13f, R.color.sub).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            row.addView(text(value, 15f, if (onClick != null) R.color.accent else R.color.sub, bold = onClick != null).apply {
                setPadding(dp(12), 0, 0, 0)
                gravity = Gravity.END
            })
        }
        if (onClick != null) {
            selectable(row)
            row.setOnClickListener { onClick() }
        }
        addView(row)
    }

    private fun LinearLayout.switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(10), 0, dp(10))
        }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(title, 16f, R.color.text))
        if (sub != null) left.addView(text(sub, 13f, R.color.sub).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(context).apply {
            isChecked = checked
            setPadding(dp(12), 0, 0, 0)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        selectable(row)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        addView(row)
    }
}
