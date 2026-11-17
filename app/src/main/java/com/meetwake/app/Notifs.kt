package com.meetwake.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.meetwake.app.core.CheckKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
    }
}

object Notifs {
    const val CH_RING = "ring"
    const val CH_PROMPT = "prompts"
    const val CH_INFO = "info"

    const val ID_RING = 1
    const val ID_WFH = 2
    const val ID_AWAKE = 3
    const val ID_INFO = 4
    private const val ID_UPCOMING_BASE = 10_000

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun ensureChannels(c: Context) {
        val ring = NotificationChannel(CH_RING, "Meeting alarm (ringing)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while a meeting alarm rings. The app plays the sound itself."
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val prompt = NotificationChannel(CH_PROMPT, "Questions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "\"WFH tomorrow?\" and \"Are you awake?\""
        }
        val info = NotificationChannel(CH_INFO, "Upcoming alarms", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Quiet notice when a meeting alarm is set, with a Cancel button."
        }
        nm(c).createNotificationChannels(listOf(ring, prompt, info))
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(c: Context, label: String, pi: PendingIntent) =
        Notification.Action.Builder(null, label, pi).build()

    private fun base(c: Context, channel: String) = Notification.Builder(c, channel)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setColor(c.getColor(R.color.accent))
        .setContentIntent(openApp(c))

    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())

    fun showWfhPrompt(c: Context, date: LocalDate, defaultWfh: Boolean) {
        val day = date.format(dayFmt)
        val n = base(c, CH_PROMPT)
            .setContentTitle("Working from home on $day?")
            .setContentText(
                if (defaultWfh) "Meeting alarms will be ON unless you say No."
                else "Meeting alarms will be OFF unless you say Yes."
            )
            .setAutoCancel(true)
            .addAction(action(c, "Yes, WFH", Alarms.broadcast(c, Alarms.ACTION_WFH_ANSWER, 10) {
                putExtra(Alarms.EXTRA_DATE, date.toString()); putExtra(Alarms.EXTRA_WFH, true)
            }))
            .addAction(action(c, "No", Alarms.broadcast(c, Alarms.ACTION_WFH_ANSWER, 11) {
                putExtra(Alarms.EXTRA_DATE, date.toString()); putExtra(Alarms.EXTRA_WFH, false)
            }))
            .build()
        notify(c, ID_WFH, n)
    }

    fun showAwakePrompt(c: Context, kind: CheckKind) {
        val n = base(c, CH_PROMPT)
            .setContentTitle(if (kind == CheckKind.NAP_END) "Nap over — are you up?" else "Are you awake?")
            .setContentText("Teams meeting alarms stay ON until you tap \"I'm awake\".")
            .setAutoCancel(true)
            .addAction(action(c, "I'm awake", Alarms.broadcast(c, Alarms.ACTION_AWAKE_YES, 12)))
            .addAction(action(c, "Not yet", Alarms.broadcast(c, Alarms.ACTION_DISMISS_PROMPT, 13)))
            .build()
        notify(c, ID_AWAKE, n)
    }

    fun showUpcoming(c: Context, a: PendingAlarm) {
        val n = base(c, CH_INFO)
            .setContentTitle("Meeting alarm at ${Fmt.time(c, a.fireAt)}")
            .setContentText("${a.subject} · ${if (a.snoozeCount > 0) "snoozed" else "meeting " + Fmt.time(c, a.meetingAt)}")
            .setWhen(a.fireAt)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .addAction(action(c, "Cancel alarm", Alarms.broadcast(c, Alarms.ACTION_CANCEL_ALARM, 20_000 + a.id) {
                putExtra(Alarms.EXTRA_ID, a.id)
            }))
            .build()
        notify(c, ID_UPCOMING_BASE + a.id, n)
    }

    fun cancelUpcoming(c: Context, id: Int) = nm(c).cancel(ID_UPCOMING_BASE + id)

    fun showInfo(c: Context, title: String, text: String) {
        notify(c, ID_INFO, base(c, CH_PROMPT).setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }

    fun cancel(c: Context, id: Int) = nm(c).cancel(id)

    private fun notify(c: Context, id: Int, n: Notification) {
        try { nm(c).notify(id, n) } catch (_: SecurityException) { /* notifications permission not granted */ }
    }
}
