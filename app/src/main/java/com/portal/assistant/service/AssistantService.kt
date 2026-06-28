package com.portal.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.portal.assistant.BuildConfig
import com.portal.assistant.conversation.AssistantEngine
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.system.AppPrefs
import com.portal.commons.DebugLog
import java.io.File

/**
 * Resident foreground service that (a) **stays warm** so the portal-wake hand-off doesn't pay a cold
 * process spawn, and (b) hosts one [AssistantEngine] conversation on demand.
 *
 * **Why resident.** Measurement showed the dominant "hey jarvis" → orange-bar latency was the *cold*
 * start of this process after the wake fired. A warm process makes the same hand-off far faster. So this
 * service is started at boot ([AssistantBootReceiver]) and after each conversation it returns to
 * **standby** (mic released, no bar) instead of `stopSelf` —
 * keeping the JVM/classes resident for the next wake. [START_STICKY] re-warms it if the system kills it.
 *
 * **Mic discipline is unchanged.** Standby holds NO mic and shows NO bar; the mic is acquired only while a
 * conversation is live (the engine owns it) and released the instant the conversation ends, so portal-wake
 * reclaims by *detecting* it's free (no done-signal). The orange bar is up only during LISTENING.
 */
class AssistantService : Service() {

    private var engine: AssistantEngine? = null

    @Volatile private var prewarmed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.file = File(getExternalFilesDir(null), "debug.txt")
        // Pick up a key dropped by the install script (import-once; no-op if none). Lets a non-technical
        // user be set up at install with no typing — they can still change it in Settings later.
        AppPrefs.importProvisionedKey(applicationContext)
        // Warm the IP-geo cache on every service creation, regardless of which action started it — so a
        // "hey jarvis what's the weather" as the FIRST trigger after install (which arrives via ACTION_START,
        // not the standby branch that runs prewarm) still has a location to inject. Async + self-guarding.
        com.portal.assistant.system.LocationProvider.refreshIfStale(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(active = engine != null)) // resident; comes up fast
        when (intent?.action) {
            ACTION_STOP -> endConversation()

            ACTION_NEW_CONVERSATION -> newConversation()

            ACTION_START -> startConversation(
                intent.getStringExtra(EXTRA_SOURCE) ?: "?",
                intent.getStringExtra(EXTRA_INITIAL_TEXT),
            )

            else -> { // boot / sticky restart
                DebugLog.log("AssistantService standby (resident, warm)")
                prewarm()
            }
        }
        return START_STICKY // stay resident across kills so the next wake hits a warm process
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service dying: stop the engine and clear the flags, but NOT via returnToStandby() — its
        // notification update is pointless when the service is going away.
        engine?.stop()
        engine = null
        running = false
        DebugLog.log("AssistantService destroyed → mic released")
    }

    private fun startConversation(source: String, initialText: String? = null) {
        // Idempotent: a trigger (tap or wake) while a turn is already live is a no-op — a deliberate product
        // choice. This holds in BOTH phases: during SPEAKING (half-duplex, mic muted, no barge-in) and during
        // LISTENING/multi-turn re-listen (a second wake/tap can't start a parallel conversation or "refresh"
        // the turn — the user just keeps talking).
        if (engine != null) return
        running = true
        startForeground(NOTIFICATION_ID, buildNotification(active = true))
        DebugLog.log("AssistantService START (trigger=$source) → conversation")
        // A foreground tap opens the mic immediately; portal-wake yields by detecting our recording
        // (no broadcast needed). On a wake trigger portal-wake has already paused for the handoff.
        // BYOD: prefer the user's own key (Settings → API key); fall back to the baked dev key. Read per
        // conversation so a key changed in Settings applies on the next turn with no restart.
        val key = AppPrefs.apiKey(applicationContext) ?: BuildConfig.GEMINI_API_KEY
        engine = AssistantEngine(applicationContext, key) {
            // The engine has already torn down (mic released, bar hidden) before this fires.
            DebugLog.log("conversation ended → standby")
            returnToStandby()
        }.also {
            // Continue the on-screen conversation ONLY on a foreground mic-tap; every other source (wake,
            // tapped chip, and any future one) starts fresh — the safe default. A tapped chip also passes its
            // text as the first turn (initialText). If trigger sources grow, prefer a typed source over this
            // string check.
            it.start(resume = source == SOURCE_TAP, initialText = initialText)
        }
    }

    /**
     * Back to resident standby: drop the engine, clear [running], restore the idle notification. Shared by
     * the engine's own `onEnded` (it has already torn down) and [endConversation] (which tears down first).
     */
    private fun returnToStandby() {
        engine = null
        running = false
        startForeground(NOTIFICATION_ID, buildNotification(active = false))
    }

    /**
     * One-shot, off-thread pre-warm of the *conversation code path* so the first "hey jarvis" doesn't
     * pay it. Keeping the process resident (standby) avoids the cold spawn, but the first conversation
     * still cold-loads [AssistantEngine]/[LiveClient]/OkHttp + builds the shared client (~1.5 s measured).
     * Here we build the OkHttp client, class-load the stack, pre-resolve the Live host's DNS, and snapshot
     * the installed tool-provider list. **No mic, no overlay, no socket** is opened — pure warm-up, safe in standby.
     */
    private fun prewarm() {
        if (prewarmed) return
        prewarmed = true
        Thread {
            runCatching {
                com.portal.assistant.util.Http.shared // builds the OkHttpClient (dispatcher + pool)
                Class.forName("com.portal.assistant.gemini.LiveClient")
                Class.forName("com.portal.assistant.conversation.AssistantEngine")
                Class.forName("com.portal.assistant.audio.MicCapture")
                Class.forName("com.portal.assistant.audio.PcmPlayer")
                java.net.InetAddress.getByName("generativelanguage.googleapis.com") // warm DNS
                com.portal.assistant.system.LocationProvider.refreshIfStale(applicationContext) // cache IP-geo
                com.portal.assistant.conversation.tools.ExternalToolProvider.warm(applicationContext) // pre-scan installed tool providers off the wake path
                DebugLog.log("prewarm done (classes + http + dns + tools)")
            }.onFailure { DebugLog.log("prewarm failed: ${it.message}") }
        }.apply {
            isDaemon = true
            name = "assistant-prewarm"
        }.start()
    }

    /**
     * End the current turn (if any) but keep the service resident & warm. Idempotent. `engine.stop()` tears
     * the engine down **without** invoking its `onEnded` callback, so this external-stop path runs the same
     * [returnToStandby] cleanup itself.
     */
    private fun endConversation() {
        DebugLog.log("AssistantService STOP → standby")
        engine?.stop() // releases mic + hides bar; does NOT call the onEnded above
        returnToStandby()
    }

    /**
     * "New conversation": tear the current turn down, then clear the on-screen transcript. Both run here on
     * the main thread in order (after the synchronous [AssistantEngine.stop]), so a still-live engine can't
     * repopulate the hub after the clear — the race a UI-thread `stop()` + `clearHistory()` would have.
     */
    private fun newConversation() {
        endConversation()
        ConversationHub.clearHistory()
    }

    private fun buildNotification(active: Boolean): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Assistant", NotificationManager.IMPORTANCE_MIN),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (active) "Listening" else "Jarvis ready")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.portal.assistant.action.START"
        const val ACTION_STOP = "com.portal.assistant.action.STOP"
        const val ACTION_NEW_CONVERSATION = "com.portal.assistant.action.NEW_CONVERSATION"
        const val ACTION_STANDBY = "com.portal.assistant.action.STANDBY"
        const val EXTRA_SOURCE = "com.portal.assistant.extra.SOURCE"
        const val EXTRA_INITIAL_TEXT = "com.portal.assistant.extra.INITIAL_TEXT"

        /** Conversation trigger source: a foreground tap (portal-wake yields by detection). */
        const val SOURCE_TAP = "tap"

        /** Conversation trigger source: a tapped suggestion chip — sends its text as the first turn. */
        const val SOURCE_CHIP = "chip"

        private const val CHANNEL_ID = "assistant_listening"
        private const val NOTIFICATION_ID = 1001

        /** True while a conversation is live — lets the Activity toggle and start be idempotent. */
        @Volatile
        var running = false
            private set

        /** Bring the resident service up (or no-op if already up) without starting a conversation. */
        fun standby(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java).setAction(ACTION_STANDBY),
            )
        }

        fun start(context: Context, source: String, initialText: String? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SOURCE, source)
                    .apply { initialText?.let { putExtra(EXTRA_INITIAL_TEXT, it) } },
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))
        }

        /** End any live turn and clear the transcript back to the greeting (the "New conversation" button). */
        fun newConversation(context: Context) {
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_NEW_CONVERSATION))
        }
    }
}
