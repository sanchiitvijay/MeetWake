package com.meetwake.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import org.json.JSONObject

/** Plays the alarm sound and vibration while a meeting alarm rings. Runs only while ringing. */
class RingService : Service() {

    companion object {
        const val ACTION_START = "com.meetwake.app.ring.START"
        const val ACTION_DISMISS = "com.meetwake.app.ring.DISMISS"
        const val ACTION_SNOOZE = "com.meetwake.app.ring.SNOOZE"
        const val ACTION_AWAKE = "com.meetwake.app.ring.AWAKE"
        const val ACTION_CALL_JOINED = "com.meetwake.app.ring.CALL_JOINED"
        private const val EXTRA_ALARM = "alarm"
        private const val MAX_AUTO_SNOOZES = 3

        @Volatile
        var isRinging = false
            private set

        @Volatile
        var current: PendingAlarm? = null
            private set

        /** AlarmActivity listens so it can close when the alarm stops from the notification. */
        @Volatile
        var onStopped: (() -> Unit)? = null

        fun start(c: Context, a: PendingAlarm) {
            c.startForegroundService(
                Intent(c, RingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ALARM, a.toJson().toString())
            )
        }

        fun intent(c: Context, action: String) = Intent(c, RingService::class.java).setAction(action)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { onTimeout() }
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var savedVolume = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var extraMeetings = 0

    /** Checks every 2 s, only while ringing, whether you've joined a call. */
    private val modePoll = object : Runnable {
        override fun run() {
            if (!isRinging) return
            if (Engine.inCall(this@RingService)) onCallJoined() else handler.postDelayed(this, 2_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val a = intent.getStringExtra(EXTRA_ALARM)?.let { runCatching { PendingAlarm.fromJson(JSONObject(it)) }.getOrNull() }
                if (a == null) {
                    if (!isRinging) stopSelf()
                    return START_NOT_STICKY
                }
                val first = !isRinging
                val prev = current
                if (prev == null || first) {
                    current = a
                    extraMeetings = 0
                } else if (!prev.subject.startsWith(a.subject)) {
                    extraMeetings++
                    current = prev.copy(subject = prev.subject.substringBefore(" + ") + " + $extraMeetings more")
                }
                startInForeground(current!!)
                if (first && !a.test && Engine.inCall(this)) {
                    // Never ring over a call you're already on.
                    Store.setAwake(this, Store.now())
                    Store.log(this, a.subject, "", "Alarm skipped: you're already in a call")
                    finish()
                    return START_NOT_STICKY
                }
                if (first) startRinging()
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, Store.config(this).ringTimeoutMin * 60_000L)
            }
            ACTION_SNOOZE -> {
                current?.let { Engine.snooze(this, it) }
                finish()
            }
            ACTION_AWAKE -> {
                Store.setAwake(this, Store.now())
                Notifs.cancel(this, Notifs.ID_AWAKE)
                finish()
            }
            ACTION_DISMISS -> finish()
            ACTION_CALL_JOINED -> finish()
            else -> if (!isRinging) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(a: PendingAlarm) {
        val n = buildNotification(a)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifs.ID_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifs.ID_RING, n)
        }
    }

    private fun buildNotification(a: PendingAlarm): Notification {
        val full = PendingIntent.getActivity(
            this, 2,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun act(label: String, action: String, rc: Int) = Notification.Action.Builder(
            null, label, PendingIntent.getService(this, rc, intent(this, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ).build()
        val snooze = Store.config(this).snoozeMin
        val openTeams = PendingIntent.getActivity(
            this, 3,
            Intent(this, AlarmActivity::class.java).setAction(AlarmActivity.ACTION_OPEN_TEAMS)
                .putExtra(AlarmActivity.EXTRA_SUBJECT, a.subject)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, Notifs.CH_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(getColor(R.color.accent))
            .setContentTitle(if (a.test) "Test alarm" else "Teams meeting: ${a.subject}")
            .setContentText(AlarmActivity.whenText(this, a))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, if (a.call) "Answer in Teams" else "Open Teams", openTeams).build())
            .addAction(act("I'm awake", ACTION_AWAKE, 30))
            .addAction(act("Snooze $snooze min", ACTION_SNOOZE, 31))
            .build()
    }

    private fun onCallJoined() {
        if (!isRinging) return
        Store.setAwake(this, Store.now())
        Store.log(this, current?.subject ?: "Alarm", "", "Alarm stopped: you joined a call")
        finish()
    }

    private fun watchCalls(audio: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = AudioManager.OnModeChangedListener { mode ->
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) handler.post { onCallJoined() }
            }
            audio.addOnModeChangedListener(mainExecutor, l)
            modeListener = l
        }
        handler.postDelayed(modePoll, 2_000)
    }

    private fun startRinging() {
        isRinging = true
        val cfg = Store.config(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meetwake:ring")
            .apply { acquire((cfg.ringTimeoutMin + 1) * 60_000L) }

        val audio = getSystemService(AudioManager::class.java)
        watchCalls(audio)
        val vol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = when {
            cfg.maxVolume -> max
            vol == 0 -> (max + 1) / 2 // a muted alarm stream would make the alarm useless
            else -> vol
        }
        if (target != vol) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
                savedVolume = vol
            } catch (_: Exception) {
            }
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sources = listOfNotNull(
            cfg.ringtoneUri?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            Settings.System.DEFAULT_RINGTONE_URI,
            Settings.System.DEFAULT_NOTIFICATION_URI,
        )
        for (uri in sources) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                break
            } catch (_: Exception) {
                mp.release()
            }
        }

        if (cfg.vibrate) {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            }
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                } else {
                    @Suppress("DEPRECATION") v.vibrate(effect, attrs)
                }
                vibrator = v
            } catch (_: Exception) {
            }
        }
    }

    private fun onTimeout() {
        val a = current
        if (a != null && !a.test && a.snoozeCount < MAX_AUTO_SNOOZES) {
            Engine.snooze(this, a)
        } else if (a != null) {
            Notifs.showInfo(this, "Missed meeting alarm", a.subject)
        }
        finish()
    }

    private fun stopRinging() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(modePoll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener?.let { l -> runCatching { getSystemService(AudioManager::class.java).removeOnModeChangedListener(l) } }
        }
        modeListener = null
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (savedVolume >= 0) {
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
        }
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        isRinging = false
        current = null
    }

    private fun finish() {
        stopRinging()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onStopped?.invoke()
    }

    override fun onDestroy() {
        if (isRinging) {
            stopRinging()
            onStopped?.invoke()
        }
        super.onDestroy()
    }
}
