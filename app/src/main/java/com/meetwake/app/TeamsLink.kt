package com.meetwake.app

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.widget.Toast

/**
 * Opens the Teams screen a notification pointed to (the incoming-call screen with its Answer
 * button, or the meeting with its Join button), falling back to just opening Teams.
 */
object TeamsLink {
    private const val KEEP_MS = 3 * 3600_000L
    private val intents = HashMap<String, Pair<PendingIntent, Long>>()

    @Synchronized
    fun remember(subject: String, pi: PendingIntent?) {
        if (pi == null) return
        val now = System.currentTimeMillis()
        intents.entries.removeAll { now - it.value.second > KEEP_MS }
        intents[Engine.subjectKey(subject)] = pi to now
    }

    @Synchronized
    private fun get(subject: String): PendingIntent? = intents[Engine.subjectKey(subject)]?.first

    fun open(a: Activity, subject: String?) {
        val pi = subject?.let(::get)
        if (pi != null) {
            try {
                val opts = if (Build.VERSION.SDK_INT >= 34) {
                    @Suppress("DEPRECATION")
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else null
                pi.send(a, 0, null, null, null, null, opts)
                return
            } catch (_: Exception) {
                // Notification was withdrawn; fall back to opening Teams.
            }
        }
        val launch = TeamsListenerService.TEAMS_PACKAGES.firstNotNullOfOrNull { a.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(a, "Microsoft Teams isn't installed", Toast.LENGTH_LONG).show()
        }
    }
}
