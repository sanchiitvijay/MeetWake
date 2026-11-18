package com.meetwake.app

import android.content.Context
import android.content.SharedPreferences
import com.meetwake.app.core.Config
import com.meetwake.app.core.Planner
import com.meetwake.app.core.TimeRange
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A meeting alarm waiting to fire. Times are epoch millis. */
data class PendingAlarm(
    val id: Int,
    val subject: String,
    val meetingAt: Long,
    val fireAt: Long,
    val reason: String,
    val snoozeCount: Int = 0,
    val test: Boolean = false,
    /** Set for incoming/missed Teams calls: the alarm then offers "Answer in Teams". */
    val call: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("subject", subject).put("meetingAt", meetingAt).put("fireAt", fireAt)
        .put("reason", reason).put("snooze", snoozeCount).put("test", test).put("call", call)

    companion object {
        fun fromJson(o: JSONObject) = PendingAlarm(
            o.getInt("id"), o.getString("subject"), o.getLong("meetingAt"), o.getLong("fireAt"),
            o.optString("reason"), o.optInt("snooze"), o.optBoolean("test"), o.optBoolean("call"),
        )
    }
}

data class LogEntry(val at: Long, val title: String, val text: String, val outcome: String)

/** All persistent state, kept in one small SharedPreferences file. */
object Store {
    private const val FILE = "meetwake"
    private const val MAX_LOG = 30

    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val zone: ZoneId get() = ZoneId.systemDefault()
    fun toLocal(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    fun toMillis(t: LocalDateTime): Long = t.atZone(zone).toInstant().toEpochMilli()
    fun now(): LocalDateTime = LocalDateTime.now(zone)

    // ---------- settings ----------

    fun config(c: Context): Config {
        val p = sp(c)
        val d = Config()
        return Config(
            enabled = p.getBoolean("enabled", d.enabled),
            wfhDays = p.getString("wfhDays", null)?.let { s ->
                s.split(",").mapNotNull { it.toIntOrNull()?.takeIf { v -> v in 1..7 }?.let(DayOfWeek::of) }.toSet()
            } ?: d.wfhDays,
            work = p.getString("work", null)?.let(TimeRange::decode) ?: d.work,
            naps = p.getString("naps", null)?.split(";")?.mapNotNull(TimeRange::decode) ?: d.naps,
            leadMin = p.getInt("leadMin", d.leadMin),
            eveningPrompt = p.getBoolean("eveningPrompt", d.eveningPrompt),
            eveningPromptMin = p.getInt("eveningPromptMin", d.eveningPromptMin),
            awakeCheck = p.getBoolean("awakeCheck", d.awakeCheck),
            awakeCheckMin = p.getInt("awakeCheckMin", d.awakeCheckMin),
            napEndCheck = p.getBoolean("napEndCheck", d.napEndCheck),
            ringForCalls = p.getBoolean("ringForCalls", d.ringForCalls),
            broadChat = p.getBoolean("broadChat", d.broadChat),
            extraKeywords = p.getString("extraKeywords", "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            snoozeMin = p.getInt("snoozeMin", d.snoozeMin),
            ringTimeoutMin = p.getInt("ringTimeoutMin", d.ringTimeoutMin),
            vibrate = p.getBoolean("vibrate", d.vibrate),
            maxVolume = p.getBoolean("maxVolume", d.maxVolume),
            ringtoneUri = p.getString("ringtoneUri", null),
            keepConnected = p.getBoolean("keepConnected", d.keepConnected),
            friends = p.getString("friends", "")!!.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun saveConfig(c: Context, cfg: Config) {
        sp(c).edit()
            .putBoolean("enabled", cfg.enabled)
            .putString("wfhDays", cfg.wfhDays.joinToString(",") { it.value.toString() })
            .putString("work", cfg.work.encode())
            .putString("naps", cfg.naps.joinToString(";") { it.encode() })
            .putInt("leadMin", cfg.leadMin)
            .putBoolean("eveningPrompt", cfg.eveningPrompt)
            .putInt("eveningPromptMin", cfg.eveningPromptMin)
            .putBoolean("awakeCheck", cfg.awakeCheck)
            .putInt("awakeCheckMin", cfg.awakeCheckMin)
            .putBoolean("napEndCheck", cfg.napEndCheck)
            .putBoolean("ringForCalls", cfg.ringForCalls)
            .putBoolean("broadChat", cfg.broadChat)
            .putString("extraKeywords", cfg.extraKeywords.joinToString(","))
            .putInt("snoozeMin", cfg.snoozeMin)
            .putInt("ringTimeoutMin", cfg.ringTimeoutMin)
            .putBoolean("vibrate", cfg.vibrate)
            .putBoolean("maxVolume", cfg.maxVolume)
            .putString("ringtoneUri", cfg.ringtoneUri)
            .putBoolean("keepConnected", cfg.keepConnected)
            .putString("friends", cfg.friends.joinToString("\n"))
            .apply()
    }

    fun planner(c: Context, cfg: Config = config(c)): Planner {
        val p = sp(c)
        return Planner(cfg) { d -> overrideKey(d).let { k -> if (p.contains(k)) p.getBoolean(k, true) else null } }
    }

    // ---------- per-day WFH answers ----------

    private fun overrideKey(d: LocalDate) = "ovr_$d"

    fun setWfh(c: Context, d: LocalDate, wfh: Boolean?) {
        val e = sp(c).edit()
        if (wfh == null) e.remove(overrideKey(d)) else e.putBoolean(overrideKey(d), wfh)
        // Drop answers older than a week.
        val cutoff = LocalDate.now(zone).minusDays(7)
        sp(c).all.keys.filter { it.startsWith("ovr_") }.forEach { k ->
            runCatching { LocalDate.parse(k.removePrefix("ovr_")) }.getOrNull()?.let { if (it.isBefore(cutoff)) e.remove(k) }
        }
        e.apply()
    }

    fun hasOverride(c: Context, d: LocalDate) = sp(c).contains(overrideKey(d))

    // ---------- awake state ----------

    fun awakeAt(c: Context): LocalDateTime? = sp(c).getLong("awakeAt", -1L).takeIf { it > 0 }?.let(::toLocal)
    fun setAwake(c: Context, at: LocalDateTime?) {
        sp(c).edit().putLong("awakeAt", at?.let(::toMillis) ?: -1L).apply()
    }

    // ---------- pending alarms ----------

    @Synchronized
    fun alarms(c: Context): List<PendingAlarm> {
        val arr = runCatching { JSONArray(sp(c).getString("alarms", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { PendingAlarm.fromJson(arr.getJSONObject(it)) }.sortedBy { it.fireAt }
    }

    @Synchronized
    private fun saveAlarms(c: Context, list: List<PendingAlarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(c).edit().putString("alarms", arr.toString()).commit()
    }

    @Synchronized
    fun addAlarm(c: Context, a: PendingAlarm) = saveAlarms(c, alarms(c).filter { it.id != a.id } + a)

    @Synchronized
    fun removeAlarm(c: Context, id: Int): PendingAlarm? {
        val all = alarms(c)
        val hit = all.firstOrNull { it.id == id } ?: return null
        saveAlarms(c, all - hit)
        return hit
    }

    @Synchronized
    fun nextId(c: Context): Int {
        val p = sp(c)
        val id = p.getInt("nextId", 100)
        p.edit().putInt("nextId", if (id >= 9_000) 100 else id + 1).commit()
        return id
    }

    // ---------- recently rung (to avoid ringing twice for one meeting) ----------

    @Synchronized
    fun recentRings(c: Context): List<Pair<String, Long>> {
        val arr = runCatching { JSONArray(sp(c).getString("recent", "[]")) }.getOrElse { JSONArray() }
        val cutoff = System.currentTimeMillis() - 2 * 3600_000L
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it.getString("s") to it.getLong("m") }
            .filter { it.second > cutoff }
    }

    @Synchronized
    fun addRecentRing(c: Context, subjectKey: String, meetingAt: Long) {
        val arr = JSONArray()
        (recentRings(c) + (subjectKey to meetingAt)).takeLast(20).forEach { (s, m) -> arr.put(JSONObject().put("s", s).put("m", m)) }
        sp(c).edit().putString("recent", arr.toString()).apply()
    }

    // ---------- seen notifications (dedupe re-posts and updates) ----------

    @Synchronized
    fun markSeen(c: Context, hash: String): Boolean {
        val now = System.currentTimeMillis()
        val obj = runCatching { JSONObject(sp(c).getString("seen", "{}")) }.getOrElse { JSONObject() }
        val fresh = JSONObject()
        obj.keys().forEach { k -> val t = obj.getLong(k); if (now - t < 6 * 3600_000L) fresh.put(k, t) }
        val already = fresh.has(hash)
        if (!already) fresh.put(hash, now)
        sp(c).edit().putString("seen", fresh.toString()).apply()
        return !already
    }

    // ---------- activity log ----------

    @Synchronized
    fun log(c: Context, title: String, text: String, outcome: String) {
        val arr = runCatching { JSONArray(sp(c).getString("log", "[]")) }.getOrElse { JSONArray() }
        val out = JSONArray()
        val start = maxOf(0, arr.length() - (MAX_LOG - 1))
        for (i in start until arr.length()) out.put(arr.get(i))
        out.put(JSONObject().put("at", System.currentTimeMillis()).put("t", title.take(60)).put("x", text.take(120)).put("o", outcome))
        sp(c).edit().putString("log", out.toString()).apply()
    }

    fun logEntries(c: Context): List<LogEntry> {
        val arr = runCatching { JSONArray(sp(c).getString("log", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { LogEntry(it.getLong("at"), it.optString("t"), it.optString("x"), it.optString("o")) }
            .reversed()
    }

    fun clearLog(c: Context) = sp(c).edit().remove("log").apply()
}
