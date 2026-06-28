package com.portal.assistant.conversation.tools

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** One scheduled timer. Pure data — id doubles as the AlarmManager PendingIntent requestCode. */
data class TimerEntry(
    val id: Int,
    val label: String,
    val fireAtMs: Long,
    val durationSec: Int,
)

/**
 * Pure timer-store logic — Android-free so it's unit-tested (same pattern as
 * [com.portal.assistant.system.Geolocation]). The Android shell ([TimerScheduler]) wraps these with the
 * SharedPreferences I/O, the `synchronized` lock, and the AlarmManager/notification side effects.
 */
object Timers {

    /** Parse the persisted JSON array into entries; tolerant of malformed/missing → empty list. */
    fun parse(json: String?): List<TimerEntry> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("id")) return@mapNotNull null
            TimerEntry(
                id = o.optInt("id"),
                label = o.optString("label"),
                fireAtMs = o.optLong("fireAtMs"),
                durationSec = o.optInt("durationSec"),
            )
        }
    }

    fun serialize(list: List<TimerEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("label", e.label)
                    .put("fireAtMs", e.fireAtMs)
                    .put("durationSec", e.durationSec),
            )
        }
        return arr.toString()
    }

    /** Whole seconds until [entry] fires, never negative. */
    fun remainingSec(entry: TimerEntry, nowMs: Long): Long = max(0L, (entry.fireAtMs - nowMs) / 1000L)

    /** Entries that haven't fired yet (drops elapsed ones defensively). */
    fun active(list: List<TimerEntry>, nowMs: Long): List<TimerEntry> = list.filter { it.fireAtMs > nowMs }

    fun withAdded(list: List<TimerEntry>, entry: TimerEntry): List<TimerEntry> = list + entry

    fun withRemoved(list: List<TimerEntry>, id: Int): List<TimerEntry> = list.filterNot { it.id == id }

    /** First entry whose label matches (case-insensitive, trimmed), or null. */
    fun findByLabel(list: List<TimerEntry>, label: String): TimerEntry? {
        val want = label.trim()
        return list.firstOrNull { it.label.equals(want, ignoreCase = true) }
    }
}
