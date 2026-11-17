package com.meetwake.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.text.format.DateFormat
import com.meetwake.app.core.AwakeCheck
import com.meetwake.app.core.CheckKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything that talks to AlarmManager. */
object Alarms {
    const val ACTION_RING = "com.meetwake.app.RING"
    const val ACTION_TICK = "com.meetwake.app.TICK"
    const val ACTION_EVENING = "com.meetwake.app.EVENING"
    const val ACTION_AWAKE_CHECK = "com.meetwake.app.AWAKE_CHECK"
    const val ACTION_WFH_ANSWER = "com.meetwake.app.WFH_ANSWER"
    const val ACTION_AWAKE_YES = "com.meetwake.app.AWAKE_YES"
    const val ACTION_DISMISS_PROMPT = "com.meetwake.app.DISMISS_PROMPT"
    const val ACTION_CANCEL_ALARM = "com.meetwake.app.CANCEL_ALARM"

    const val EXTRA_ID = "id"
    const val EXTRA_DATE = "date"
    const val EXTRA_WFH = "wfh"
    const val EXTRA_KIND = "kind"

    private const val RC_TICK = 1
    private const val RC_EVENING = 2
    private const val RC_AWAKE = 3

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun broadcast(c: Context, action: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            c, requestCode,
            Intent(c, EventReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun canExact(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(c).canScheduleExactAlarms()

    private fun setExact(c: Context, at: Long, pi: PendingIntent) {
        val am = am(c)
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun ringIntent(c: Context, id: Int) = broadcast(c, ACTION_RING, id) { putExtra(EXTRA_ID, id) }

    /** Meeting alarms use setAlarmClock: the most reliable kind, it wakes the phone even in Doze. */
    fun scheduleMeeting(c: Context, a: PendingAlarm) {
        val am = am(c)
        val pi = ringIntent(c, a.id)
        val show = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(a.fireAt, show), pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.fireAt, pi)
        }
    }

    fun cancelMeeting(c: Context, id: Int) = am(c).cancel(ringIntent(c, id))

    /** Re-plans every timer. Cheap; called on boot, settings changes and each tick. */
    fun rescheduleAll(c: Context) {
        val cfg = Store.config(c)
        val planner = Store.planner(c, cfg)
        val now = Store.now()

        setExact(c, Store.toMillis(planner.nextListenChange(now)), broadcast(c, ACTION_TICK, RC_TICK))

        val evening = broadcast(c, ACTION_EVENING, RC_EVENING)
        if (cfg.enabled && cfg.eveningPrompt) setExact(c, Store.toMillis(planner.nextDaily(now, cfg.eveningPromptMin)), evening)
        else am(c).cancel(evening)

        val check: AwakeCheck? = if (cfg.enabled) planner.nextAwakeCheck(now) else null
        if (check != null) {
            setExact(c, Store.toMillis(check.at), broadcast(c, ACTION_AWAKE_CHECK, RC_AWAKE) {
                putExtra(EXTRA_KIND, check.kind.name); putExtra(EXTRA_DATE, check.shift.toString())
            })
        } else {
            am(c).cancel(broadcast(c, ACTION_AWAKE_CHECK, RC_AWAKE))
        }

        // Alarm registrations are lost on reboot, so put them all back.
        val stale = System.currentTimeMillis() - 10 * 60_000L
        for (a in Store.alarms(c)) {
            if (a.fireAt < stale) {
                Store.removeAlarm(c, a.id); Notifs.cancelUpcoming(c, a.id)
            } else {
                scheduleMeeting(c, a)
            }
        }

        applyListenerState(c)
    }

    fun shouldBeConnected(c: Context): Boolean {
        val cfg = Store.config(c)
        return cfg.enabled && (cfg.keepConnected || Store.planner(c, cfg).isListening(Store.now()))
    }

    /** Connects the notification listener only while it is needed. */
    fun applyListenerState(c: Context) {
        if (!Perms.listenerGranted(c)) return
        try {
            if (shouldBeConnected(c)) NotificationListenerService.requestRebind(TeamsListenerService.component(c))
            else TeamsListenerService.instance?.requestUnbind()
        } catch (_: Exception) {
        }
    }
}

/** Receives our own alarms and notification button taps. Not exported. */
class EventReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val now = Store.now()
        when (intent.action) {
            Alarms.ACTION_RING -> Engine.onAlarmFired(c, intent.getIntExtra(Alarms.EXTRA_ID, -1))
            Alarms.ACTION_TICK -> Alarms.rescheduleAll(c)
            Alarms.ACTION_EVENING -> {
                val p = Store.planner(c)
                val shift = p.nextShift(now)
                Notifs.showWfhPrompt(c, shift, p.isWfh(shift))
                Alarms.rescheduleAll(c)
            }
            Alarms.ACTION_AWAKE_CHECK -> {
                val kind = runCatching { CheckKind.valueOf(intent.getStringExtra(Alarms.EXTRA_KIND)!!) }.getOrDefault(CheckKind.MORNING)
                val shift = runCatching { LocalDate.parse(intent.getStringExtra(Alarms.EXTRA_DATE)) }.getOrNull()
                    ?: Store.planner(c).currentOrNextShift(now)
                if (Store.planner(c).shouldPromptAwake(AwakeCheck(now, kind, shift), now, Store.awakeAt(c))) {
                    Notifs.showAwakePrompt(c, kind)
                }
                Alarms.rescheduleAll(c)
            }
            Alarms.ACTION_WFH_ANSWER -> {
                val d = runCatching { LocalDate.parse(intent.getStringExtra(Alarms.EXTRA_DATE)) }.getOrNull() ?: return
                Store.setWfh(c, d, intent.getBooleanExtra(Alarms.EXTRA_WFH, true))
                Notifs.cancel(c, Notifs.ID_WFH)
                Alarms.rescheduleAll(c)
            }
            Alarms.ACTION_AWAKE_YES -> {
                Store.setAwake(c, now)
                Notifs.cancel(c, Notifs.ID_AWAKE)
            }
            Alarms.ACTION_DISMISS_PROMPT -> Notifs.cancel(c, Notifs.ID_AWAKE)
            Alarms.ACTION_CANCEL_ALARM -> Engine.cancelAlarm(c, intent.getIntExtra(Alarms.EXTRA_ID, -1))
        }
    }
}

/** System events that wipe or shift alarms. Exported because the system sends them. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Alarms.rescheduleAll(c)
        }
    }
}

object Perms {
    fun listenerGranted(c: Context): Boolean =
        c.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(TeamsListenerService.component(c))

    fun notificationsGranted(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun fullScreenGranted(c: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun batteryUnrestricted(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun teamsInstalled(c: Context): Boolean = TeamsListenerService.TEAMS_PACKAGES.any {
        runCatching { c.packageManager.getPackageInfo(it, 0) }.isSuccess
    }
}

object Fmt {
    private fun pattern(c: Context) = if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a"
    fun time(c: Context, ms: Long): String = time(c, Store.toLocal(ms))
    fun time(c: Context, t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern(pattern(c), Locale.getDefault()))
    fun minutes(c: Context, minuteOfDay: Int): String = time(c, LocalDate.now().atStartOfDay().plusMinutes(minuteOfDay.toLong()))

    fun dayTime(c: Context, t: LocalDateTime): String {
        val today = LocalDate.now(Store.zone)
        val day = when (t.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        }
        return "$day ${time(c, t)}"
    }

    fun dayTime(c: Context, ms: Long) = dayTime(c, Store.toLocal(ms))
}
