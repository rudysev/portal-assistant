package com.portal.assistant.conversation.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.portal.assistant.system.AppPrefs
import com.portal.commons.DebugLog

/**
 * The one Android site that touches AlarmManager + the timer SharedPreferences store + the alarm
 * notification. Pure store logic lives in [Timers]; this is the thin shell around it (same split as
 * [com.portal.assistant.system.Geolocation] / `LocationProvider`).
 *
 * **Concurrency.** Tool calls run on the engine's `tool-exec` thread; [onFired] runs on the **main**
 * thread (BroadcastReceiver). `SharedPreferences` is per-call thread-safe, but load→mutate→persist is not
 * atomic — so every store path goes through [mutate] under a process-wide [LOCK]. [mutate] also compacts
 * elapsed entries ([Timers.active]) and cancels the AlarmManager PendingIntent of anything it evicts, so a
 * dropped/elapsed timer never leaves a stray pending wake-up.
 *
 * State is entirely in prefs + AlarmManager, so a fresh instance per conversation is fine. Timers do NOT
 * survive a reboot (AlarmManager clears them); the store only keeps list/cancel honest across process death.
 */
class TimerScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = AppPrefs.prefs(appContext)
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule a timer. Throws [IllegalArgumentException] for an out-of-range duration (the engine wraps it
     * as `{"error":…}`). Returns the created entry.
     */
    fun set(durationSec: Int, label: String): TimerEntry {
        require(durationSec in 1..MAX_DURATION_SEC) { "duration must be between 1 and $MAX_DURATION_SEC seconds" }
        val id = nextId()
        val entry = TimerEntry(
            id = id,
            label = label.trim().ifEmpty { DEFAULT_LABEL },
            fireAtMs = System.currentTimeMillis() + durationSec * 1000L,
            durationSec = durationSec,
        )
        mutate { Timers.withAdded(it, entry) }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.fireAtMs, pendingIntent(entry))
        DebugLog.log("timer set id=$id label='${entry.label}' in ${durationSec}s")
        return entry
    }

    /** Active (not-yet-fired) timers; [mutate] compacts elapsed leftovers and returns the result. */
    fun list(): List<TimerEntry> = mutate { it }

    /**
     * Cancel by [id] (preferred) or [label] (first case-insensitive match). Order matters: cancel the alarm
     * BEFORE removing from the store so a late delivery can't resurrect it. Returns the cancelled entry, or
     * null if nothing matched.
     */
    fun cancel(id: Int?, label: String?): TimerEntry? = synchronized(LOCK) {
        val list = load()
        val target = when {
            id != null -> list.firstOrNull { it.id == id }
            !label.isNullOrBlank() -> Timers.findByLabel(list, label)
            else -> null
        } ?: return null
        alarmManager.cancel(pendingIntent(target))
        mutate { Timers.withRemoved(it, target.id) } // also compacts + cancels any evicted-elapsed alarms
        DebugLog.log("timer cancelled id=${target.id} label='${target.label}'")
        target
    }

    /**
     * The alarm fired (called on the main thread by [TimerAlarmReceiver]). Keep the lock short: under [LOCK]
     * decide whether this timer is still live (a `cancel` may have won the race) and remove it; play the alert
     * OUTSIDE the lock so it can't block a concurrent set/cancel on tool-exec. [onAlertDone] runs once the
     * alert finishes (or immediately if there's nothing to alert) — the receiver uses it to release its
     * wake-lock and finish its goAsync result.
     */
    fun onFired(id: Int, label: String, onAlertDone: () -> Unit = {}) {
        val live = synchronized(LOCK) {
            if (load().none { it.id == id }) return@synchronized false // cancel won the race → no spurious alert
            mutate { Timers.withRemoved(it, id) }
            true
        }
        if (live) fireAlert(id, label, onAlertDone) else onAlertDone()
    }

    // ---- store (all under LOCK) --------------------------------------------------------------------

    /**
     * Load → compact elapsed → apply [transform] → persist, atomically; returns the persisted list. Cancels
     * the AlarmManager PendingIntent of any entry that compaction (not [transform]) evicted, so the store and
     * the alarm set never drift. Reentrant — callers may already hold [LOCK].
     */
    private fun mutate(transform: (List<TimerEntry>) -> List<TimerEntry>): List<TimerEntry> = synchronized(LOCK) {
        val loaded = load()
        val now = System.currentTimeMillis()
        val compacted = Timers.active(loaded, now)
        (loaded - compacted.toSet()).forEach { evicted -> alarmManager.cancel(pendingIntent(evicted)) }
        val next = transform(compacted)
        persist(next)
        TimerStore.publish(next) // mirror to the UI-facing flow (any instance/thread; StateFlow is safe)
        next
    }

    private fun load(): List<TimerEntry> = Timers.parse(prefs.getString(AppPrefs.KEY_TIMERS, null))

    private fun persist(list: List<TimerEntry>) {
        prefs.edit { putString(AppPrefs.KEY_TIMERS, Timers.serialize(list)) }
    }

    private fun nextId(): Int = synchronized(LOCK) {
        val id = prefs.getInt(AppPrefs.KEY_TIMER_NEXT_ID, 1)
        prefs.edit { putInt(AppPrefs.KEY_TIMER_NEXT_ID, id + 1) }
        id
    }

    // ---- alarm + notification ----------------------------------------------------------------------

    private fun pendingIntent(entry: TimerEntry): PendingIntent {
        val intent = Intent(appContext, TimerAlarmReceiver::class.java)
            .putExtra(EXTRA_ID, entry.id)
            .putExtra(EXTRA_LABEL, entry.label)
        return PendingIntent.getBroadcast(
            appContext,
            entry.id, // requestCode = id → one PendingIntent per timer, cancellable by id
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Alert the user. On this Portal the launcher does NOT surface third-party notifications (same reason the
     * app draws the orange bar via SYSTEM_ALERT_WINDOW), so the notification alone is inaudible/invisible —
     * we therefore **play a soft chime explicitly** ([ChimeSound], on the media stream so it follows the
     * user's volume) and post the notification only as a best-effort extra for standard-Android surfaces.
     */
    private fun fireAlert(id: Int, label: String, onAlertDone: () -> Unit) {
        TimerAlarmNotification.post(appContext, id, label)
        DebugLog.log("timer fired id=$id label='$label' → chime + notification")
        ChimeSound.play(onAlertDone) // onAlertDone runs after the chime finishes (receiver releases its wake-lock)
    }

    companion object {
        const val EXTRA_ID = "com.portal.assistant.extra.TIMER_ID"
        const val EXTRA_LABEL = "com.portal.assistant.extra.TIMER_LABEL"

        const val MAX_DURATION_SEC = 86_400 // 24h — guards a mis-parsed "timer for 10 years"
        private const val DEFAULT_LABEL = "timer"

        // Process-wide: the store is shared across every TimerScheduler instance (one per conversation) and
        // the receiver, so the lock must be too — guards load→mutate→persist against the tool-exec/main race.
        private val LOCK = Any()
    }
}
