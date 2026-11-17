package com.meetwake.app

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import com.meetwake.app.core.Detection
import com.meetwake.app.core.Kind
import com.meetwake.app.core.MeetingParser
import com.meetwake.app.core.NotifInput
import com.meetwake.app.core.Verdict
import kotlin.math.abs

/** Turns detected meetings into alarms, and decides at ring time whether to actually ring. */
object Engine {
    private const val MIN = 60_000L

    fun subjectKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * True while you're on any call or meeting (Teams, phone, others). No permission needed. Signals:
     * the phone's call mode, or the microphone open for a voice call (Teams keeps it open even when muted).
     */
    fun inCall(c: Context): Boolean {
        val am = c.getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val callMic = runCatching {
                am.activeRecordingConfigurations.any { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION }
            }.getOrDefault(false)
            if (callMic) return true
        }
        return false
    }

    /** Being in a call proves you're awake: stop any ringing alarm and mark awake. */
    fun onCallJoined(c: Context, why: String) {
        Store.setAwake(c, Store.now())
        Notifs.cancel(c, Notifs.ID_AWAKE)
        if (RingService.isRinging) {
            runCatching { c.startService(RingService.intent(c, RingService.ACTION_CALL_JOINED)) }
            Store.log(c, RingService.current?.subject ?: "Alarm", "", "Alarm stopped: $why")
        }
    }

    /** Entry point from the notification listener. */
    fun onTeamsNotification(c: Context, n: NotifInput, fromScan: Boolean) {
        val cfg = Store.config(c)
        val now = Store.now()
        if (!Store.planner(c, cfg).isListening(now)) return
        if (fromScan && n.postedAt.isBefore(now.minusHours(3))) return

        val det = MeetingParser.detect(n, cfg)
        if (det.kind == Kind.IN_CALL) {
            // The in-call notification can update every second (call timer): act once, not on every update.
            val awake = Store.awakeAt(c)
            if (RingService.isRinging || awake == null || awake.isBefore(now.minusMinutes(15))) {
                onCallJoined(c, det.reason)
                Store.log(c, n.title, "", "You're in a Teams call: marked awake, no alarm")
            }
            return
        }
        if (!Store.markSeen(c, (n.title + "|" + n.text).hashCode().toString())) return

        val outcome = if (fromScan && det.kind == Kind.RING && det.meetingAt == null && n.postedAt.isBefore(now.minusMinutes(10))) {
            "Ignored: older notification found at start of work hours"
        } else {
            apply(c, n, det)
        }
        Store.log(c, n.title, n.text, outcome)
    }

    /** Acts on a detection. Returns a short description of what happened. */
    fun apply(c: Context, n: NotifInput, det: Detection): String = when (det.kind) {
        Kind.IGNORE -> "Ignored: ${det.reason}"
        Kind.IN_CALL -> {
            onCallJoined(c, det.reason)
            "You're in a Teams call: marked awake, no alarm"
        }
        Kind.CANCEL -> cancelMatching(c, n)
        Kind.RING -> schedule(c, det)
    }

    private fun cancelMatching(c: Context, n: NotifInput): String {
        val blob = subjectKey(n.title + " " + n.text)
        val hits = Store.alarms(c).filter { !it.test && subjectKey(it.subject).let { k -> k.isNotEmpty() && blob.contains(k) } }
        hits.forEach { cancelAlarm(c, it.id) }
        return if (hits.isEmpty()) "Meeting cancelled (no alarm was set for it)"
        else "Meeting cancelled: removed alarm for ${hits.joinToString { it.subject }}"
    }

    private fun schedule(c: Context, det: Detection): String {
        val cfg = Store.config(c)
        val planner = Store.planner(c, cfg)
        val now = Store.now()
        val nowMs = System.currentTimeMillis()
        val meetingAt = det.meetingAt ?: now
        val isNow = !meetingAt.isAfter(now.plusMinutes(1))

        val v = planner.verdict(meetingAt, Store.awakeAt(c))
        if (v == Verdict.DISABLED || v == Verdict.OUTSIDE_HOURS) return "No alarm: ${v.reason}"
        if (isNow && !v.ring) return "No alarm: ${v.reason}"

        val meetMs = Store.toMillis(meetingAt)
        val key = subjectKey(det.subject)
        val pending = Store.alarms(c).filter { !it.test }
        if (det.reschedule) {
            pending.filter { subjectKey(it.subject) == key }.forEach { cancelAlarm(c, it.id) }
        } else {
            pending.firstOrNull { subjectKey(it.subject) == key && abs(it.meetingAt - meetMs) <= 10 * MIN }
                ?.let { return "Already set for ${Fmt.time(c, it.fireAt)}" }
        }
        val window = if (isNow) 30 * MIN else 10 * MIN
        if (Store.recentRings(c).any { it.first == key && abs(it.second - meetMs) <= window }) {
            return "Already alerted you for this meeting"
        }
        if (isNow && RingService.isRinging) return "Alarm already ringing"
        if (isNow && inCall(c)) {
            Store.setAwake(c, now)
            return "No alarm: you're already in a call"
        }

        val fireAt = maxOf(nowMs + 1_500, meetMs - cfg.leadMin * MIN)
        if (fireAt - nowMs > 8 * 24 * 60 * MIN) return "No alarm: more than a week away"

        val a = PendingAlarm(Store.nextId(c), det.subject, meetMs, fireAt, det.reason, call = det.isCall)
        Store.addAlarm(c, a)
        Alarms.scheduleMeeting(c, a)
        if (fireAt - nowMs > MIN) Notifs.showUpcoming(c, a)
        return if (isNow) "Ringing now (${det.reason})"
        else "Alarm set for ${Fmt.dayTime(c, fireAt)}" + if (!v.ring) " (will be skipped if still: ${v.reason})" else ""
    }

    /** Called by AlarmManager when a meeting alarm is due. */
    fun onAlarmFired(c: Context, id: Int) {
        val a = Store.removeAlarm(c, id) ?: return
        Notifs.cancelUpcoming(c, id)
        if (!a.test && inCall(c)) {
            Store.setAwake(c, Store.now())
            Store.log(c, a.subject, "", "Alarm skipped: you're already in a call")
            return
        }
        if (!a.test) {
            val v = Store.planner(c).verdict(Store.toLocal(a.meetingAt), Store.awakeAt(c))
            if (!v.ring) {
                Store.log(c, a.subject, "", "Alarm skipped: ${v.reason}")
                return
            }
            Store.addRecentRing(c, subjectKey(a.subject), a.meetingAt)
        }
        RingService.start(c, a)
    }

    fun snooze(c: Context, a: PendingAlarm) {
        val cfg = Store.config(c)
        val s = a.copy(id = Store.nextId(c), fireAt = System.currentTimeMillis() + cfg.snoozeMin * MIN, snoozeCount = a.snoozeCount + 1)
        Store.addAlarm(c, s)
        Alarms.scheduleMeeting(c, s)
        Notifs.showUpcoming(c, s)
    }

    fun cancelAlarm(c: Context, id: Int) {
        Store.removeAlarm(c, id)
        Alarms.cancelMeeting(c, id)
        Notifs.cancelUpcoming(c, id)
    }

    fun testAlarm(c: Context, inSeconds: Int = 10) {
        val at = System.currentTimeMillis() + inSeconds * 1000L
        val a = PendingAlarm(Store.nextId(c), "Test alarm", at, at, "test", test = true)
        Store.addAlarm(c, a)
        Alarms.scheduleMeeting(c, a)
    }
}
