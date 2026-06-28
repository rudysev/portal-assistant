package com.portal.assistant.conversation.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.portal.commons.DebugLog
import java.io.File

/**
 * Receives the AlarmManager broadcast when a timer fires — independent of any live conversation, even if
 * the process was killed and respawned for delivery. Hands off to [TimerScheduler.onFired], which plays the
 * chime and removes the timer from the store. Triggered only by an explicit PendingIntent, so no
 * `<intent-filter>` is needed (the manifest entry is enough).
 *
 * The chime plays asynchronously (~2.3 s) after [onReceive] returns. On a warm process the resident service
 * keeps us alive, but a cold-started receiver could be torn down and clip the sound — so we [goAsync] and
 * hold a partial wake-lock until the chime signals completion, then finish.
 */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (DebugLog.file == null) DebugLog.file = File(context.getExternalFilesDir(null), "debug.txt")
        val id = intent?.getIntExtra(TimerScheduler.EXTRA_ID, -1) ?: -1
        if (id < 0) return
        val label = intent?.getStringExtra(TimerScheduler.EXTRA_LABEL).orEmpty()
        DebugLog.log("timer alarm received id=$id label='$label'")

        val pending = goAsync()
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "portal:timer-chime")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS) // safety auto-release if the callback never fires
        TimerScheduler(context).onFired(id, label) {
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            pending.finish()
        }
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 6_000L // chime is ~2.3s; generous cap
    }
}
