package com.meetwake.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.meetwake.app.core.NotifInput

/**
 * Reads notifications posted by Microsoft Teams. Every other app's notification is dropped
 * on the first line. Outside work hours the service disconnects itself (requestUnbind) and
 * AlarmManager reconnects it when work hours start, so it does not run in the background.
 */
class TeamsListenerService : NotificationListenerService() {

    companion object {
        val TEAMS_PACKAGES = setOf("com.microsoft.teams")

        /** Debug builds also accept `adb shell cmd notification post`, for testing without Teams. */
        private const val SHELL = "com.android.shell"

        @Volatile
        var instance: TeamsListenerService? = null
            private set

        fun component(c: Context) = ComponentName(c, TeamsListenerService::class.java)
    }

    private val debuggable by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    override fun onListenerConnected() {
        instance = this
        if (!Alarms.shouldBeConnected(applicationContext)) {
            runCatching { requestUnbind() }
            return
        }
        // Catch meeting notifications that arrived just before work hours started.
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it, fromScan = true) }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, fromScan = false)

    private fun handle(sbn: StatusBarNotification, fromScan: Boolean) {
        val pkg = sbn.packageName
        val n = sbn.notification ?: return
        if (pkg !in TEAMS_PACKAGES && !(debuggable && isTestSource(pkg, n))) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val input = extract(n, sbn.postTime) ?: return
        TeamsLink.remember(input.title, n.fullScreenIntent ?: n.contentIntent)
        runCatching { Engine.onTeamsNotification(applicationContext, input, fromScan) }
    }

    /** Debug builds only: adb shell notifications, or the fake-call screen's "fake_teams" channel. */
    private fun isTestSource(pkg: String, n: Notification) =
        pkg == SHELL || (pkg == packageName && n.channelId == "fake_teams")

    private fun extract(n: Notification, postTime: Long): NotifInput? {
        val ex = n.extras ?: return null
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG))
            ?.toString().orEmpty()
        val parts = LinkedHashSet<String>()
        fun add(cs: CharSequence?) { cs?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add) }
        add(ex.getCharSequence(Notification.EXTRA_TEXT))
        add(ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeLast(3)?.forEach(::add)
        val last = lastMessage(ex)
        last?.getCharSequence("text")?.let(::add)
        val text = parts.joinToString("\n")
        if (title.isEmpty() && text.isEmpty()) return null
        return NotifInput(
            title = title,
            text = text,
            category = n.category,
            hasFullScreenIntent = n.fullScreenIntent != null,
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            postedAt = Store.toLocal(postTime),
            isMessage = n.category == Notification.CATEGORY_MESSAGE || ex.containsKey(Notification.EXTRA_MESSAGES) ||
                ex.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MessagingStyle") == true,
            senders = listOfNotNull(last?.let(::senderOf)?.toString()?.takeIf { it.isNotBlank() }),
        )
    }

    /** Chat notifications use MessagingStyle; the newest message is the one that matters. */
    @Suppress("DEPRECATION")
    private fun lastMessage(ex: Bundle): Bundle? {
        val arr: Array<Parcelable> = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        return arr.lastOrNull() as? Bundle
    }

    /** Sender name of a MessagingStyle message bundle ("sender" text or "sender_person" Person). */
    @Suppress("DEPRECATION")
    private fun senderOf(msg: Bundle): CharSequence? =
        msg.getCharSequence("sender") ?: (msg.getParcelable<Parcelable>("sender_person") as? android.app.Person)?.name
}
