package com.portal.assistant.conversation.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat

/** The best-effort "timer done" notification [TimerScheduler] posts alongside its chime (see its fireAlert). */
internal object TimerDoneNotification {

    fun post(context: Context, id: Int, label: String) {
        val appContext = context.applicationContext
        val mgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(mgr)
        val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Timer")
            .setContentText("$label is done")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIFICATION_ID_BASE + id, notif)
    }

    private fun ensureChannel(mgr: NotificationManager) {
        val channel = NotificationChannel(CHANNEL_ID, "Timers", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Finished timers"
            // Channel sound is dead config on this Portal (the launcher doesn't surface the notification, so it
            // never plays) — kept for correctness on standard-Android surfaces; the audible alert is ChimeSound.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        mgr.createNotificationChannel(channel) // idempotent
    }

    private const val CHANNEL_ID = "timers"
    private const val NOTIFICATION_ID_BASE = 2000 // distinct from the resident service's 1001
}
