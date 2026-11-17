package com.meetwake.app

import android.app.Activity
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.TextView

/**
 * Debug-only stand-in for a Teams call, driven from adb:
 *   am start -n com.meetwake.app/.FakeCallActivity --es kind mic|mode|both
 *   am start -n com.meetwake.app/.FakeCallActivity --ez stop true
 *   am start -n com.meetwake.app/.FakeCallActivity --ez rearm true   (clears "I'm awake")
 *   am start -n com.meetwake.app/.FakeCallActivity --es post "Title|Text"  (fake Teams notification whose tap opens Settings)
 */
class FakeCallActivity : Activity() {
    private var rec: AudioRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Fake Teams call"; textSize = 24f })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        if (i.getBooleanExtra("rearm", false)) { Store.setAwake(this, null); finish(); return }
        if (i.getBooleanExtra("stop", false)) { finish(); return }
        i.getStringExtra("post")?.let { postFake(it); finish(); return }
        val kind = i.getStringExtra("kind") ?: "both"
        if (kind == "mode" || kind == "both") getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        if (kind == "mic" || kind == "both") {
            val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
                .also { it.startRecording() }
        }
    }

    private fun postFake(spec: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("fake_teams", "Fake Teams", android.app.NotificationManager.IMPORTANCE_LOW))
        val target = android.app.PendingIntent.getActivity(
            this, 99, Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = spec.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        nm.notify(spec.hashCode(), android.app.Notification.Builder(this, "fake_teams")
            .setSmallIcon(R.drawable.ic_stat_alarm).setContentTitle(title).setContentText(text)
            .setContentIntent(target).build())
    }

    override fun onDestroy() {
        rec?.run { stop(); release() }
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }
}
