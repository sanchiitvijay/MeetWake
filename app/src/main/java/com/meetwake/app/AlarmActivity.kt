package com.meetwake.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Full-screen ringing screen, shown over the lock screen. */
class AlarmActivity : Activity() {

    companion object {
        const val ACTION_OPEN_TEAMS = "com.meetwake.app.OPEN_TEAMS"
        const val EXTRA_SUBJECT = "subject"

        fun whenText(c: Context, a: PendingAlarm): String {
            if (a.test) return "This is what a meeting alarm looks like"
            if (a.call) return "Teams is calling you"
            val diffMin = ((a.meetingAt - System.currentTimeMillis()) / 60_000.0).roundToInt()
            return when {
                diffMin > 0 -> "Starts at ${Fmt.time(c, a.meetingAt)} (in $diffMin min)"
                diffMin == 0 -> "Starting now"
                else -> "Started ${-diffMin} min ago (${Fmt.time(c, a.meetingAt)})"
            }
        }
    }

    private val closer: () -> Unit = { runOnUiThread { finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (handleOpenTeams(intent)) return

        val a = RingService.current
        if (a == null) {
            finish()
            return
        }
        RingService.onStopped = closer
        setContentView(build(a))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenTeams(intent)
    }

    private fun handleOpenTeams(i: Intent?): Boolean {
        if (i?.action != ACTION_OPEN_TEAMS) return false
        openTeams(i.getStringExtra(EXTRA_SUBJECT) ?: RingService.current?.subject)
        return true
    }

    /**
     * Stops the alarm, marks you awake and opens Teams (the call's Answer screen or the meeting),
     * so the alarm and the Teams call never fight over the screen or the speaker.
     */
    private fun openTeams(subject: String?) {
        RingService.onStopped = null // keep this activity alive until Teams is opened
        startService(RingService.intent(this, RingService.ACTION_AWAKE))
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { TeamsLink.open(this@AlarmActivity, subject); finish() }
                override fun onDismissCancelled() = finish()
                override fun onDismissError() { TeamsLink.open(this@AlarmActivity, subject); finish() }
            })
        } else {
            TeamsLink.open(this, subject)
            finish()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun build(a: PendingAlarm): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.alarm_bg))
            setPadding(dp(28), dp(48), dp(28), dp(48))
        }
        fun label(s: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
            text = s
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            this.alpha = alpha
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(label("⏰", 64f))
        val kind = when {
            a.test -> "TEST ALARM"
            a.call -> "TEAMS CALL"
            else -> "TEAMS MEETING"
        }
        root.addView(label(kind, 14f, bold = true, alpha = 0.7f).apply { letterSpacing = 0.15f })
        root.addView(label(a.subject, 30f, bold = true))
        root.addView(label(whenText(this, a), 18f, alpha = 0.85f))
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(36)))

        fun button(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 18f
            setTextColor(if (primary) getColor(R.color.alarm_bg) else 0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                if (primary) setColor(0xFFFFFFFF.toInt()) else { setColor(0x00000000); setStroke(dp(2), 0x99FFFFFF.toInt()) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stateListAnimator = null
            setOnClickListener { onClick() }
        }
        fun service(action: String): () -> Unit = {
            startService(RingService.intent(this, action))
            finish()
        }
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(12) } }
        root.addView(button(if (a.call) "Answer in Teams" else "Open Teams", true) { openTeams(a.subject) }, lp())
        root.addView(button("I'm awake — stop alarms for now", false, service(RingService.ACTION_AWAKE)), lp())
        root.addView(button("Snooze ${Store.config(this).snoozeMin} min", false, service(RingService.ACTION_SNOOZE)), lp())
        root.addView(button("Dismiss", false, service(RingService.ACTION_DISMISS)), lp())
        return root
    }

    override fun onDestroy() {
        if (RingService.onStopped === closer) RingService.onStopped = null
        super.onDestroy()
    }
}
