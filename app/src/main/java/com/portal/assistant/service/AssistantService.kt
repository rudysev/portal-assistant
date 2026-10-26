package com.portal.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.portal.assistant.conversation.AssistantEngine
import com.portal.assistant.conversation.Conversation
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.conversation.Trigger
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.create
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.LocationProvider
import com.portal.assistant.system.NetworkStatus
import com.portal.assistant.wake.ForegroundWake
import com.portal.assistant.wake.VoskDetector
import com.portal.assistant.wake.WakeModelInstaller
import com.portal.assistant.wake.WakeSetupStatus
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

    // Standby <-> one conversation: the live guard, refusals and every way back to standby. This service only builds
    // the conversation and shows the notification; the host owns the rules (see ConversationHostTest).
    private lateinit var conversationHost: ConversationHost

    // Foreground wake: gen2 "hey jarvis" while the app is on screen (gen1 is portal-wake's). This service only forwards
    // what happened; the module owns the detector, the model download and the retry rules.
    private lateinit var foregroundWake: ForegroundWake

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
        LocationProvider.refreshIfStale(applicationContext)
        val mainHandler = Handler(Looper.getMainLooper())
        foregroundWake = ForegroundWake(
            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            micPermitted = {
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            online = { NetworkStatus.isOnline(this) },
            model = WakeModelInstaller(filesDir),
            detectors = { modelDir, listener -> VoskDetector(applicationContext, modelDir, listener) },
            io = { task ->
                Thread(task).apply {
                    isDaemon = true
                    name = "wake-model-install"
                }.start()
            },
            main = { task -> mainHandler.post(task) },
            later = { delayMs, task -> mainHandler.postDelayed(task, delayMs) },
            onWake = { wakeId -> conversationHost.start(Trigger.WakeWord(wakeId)) },
            onSetup = WakeSetupStatus::set,
        )
        conversationHost = ConversationHost(
            buildConversation = ::buildConversation,
            conversationWake = foregroundWake,
            conversationNotices = object : ConversationNotices {
                override fun postNotice(notice: String) = ConversationHub.postNotice(notice)

                override fun clearHistory() = ConversationHub.clearHistory()
            },
            onActiveChanged = { active -> startForeground(NOTIFICATION_ID, buildNotification(active)) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(active = conversationHost.live)) // resident; comes up fast
        when (intent?.action) {
            ACTION_STOP -> conversationHost.end()

            ACTION_NEW_CONVERSATION -> conversationHost.newConversation()

            ACTION_START -> when (val trigger = TriggerExtras.decode(intent::getStringExtra)) {
                null -> DebugLog.log("START ignored — the intent carries no trigger")
                else -> conversationHost.start(trigger)
            }

            ACTION_FOREGROUND -> foregroundWake.foregrounded()

            ACTION_BACKGROUND -> foregroundWake.backgrounded()

            else -> { // boot / sticky restart
                DebugLog.log("AssistantService standby (resident, warm)")
                prewarm()
            }
        }
        return START_STICKY // stay resident across kills so the next wake hits a warm process
    }

    override fun onDestroy() {
        super.onDestroy()
        conversationHost.close() // stop any conversation, without the standby notification update
        foregroundWake.close() // tear down the resident wake detector (releases its model)
        DebugLog.log("AssistantService destroyed → mic released")
    }

    /**
     * One conversation for the host, built per trigger. Which backend + how it authenticates is resolved in one place
     * ([Backends.resolve]), read per conversation so a change in Settings → Backend applies on the next turn with no
     * restart. A foreground tap opens the mic immediately; portal-wake yields by detecting our recording (no broadcast
     * needed). On a wake trigger portal-wake has already paused for the handoff.
     */
    private fun buildConversation(onEnded: () -> Unit): Conversation {
        val backendChoice = Backends.resolve(applicationContext)
        // Retry a failed/stale IP-geo lookup (prewarm is one-shot); guarded internally, a no-op when fresh.
        // Result lands for the NEXT conversation — this one opens with whatever's already cached.
        LocationProvider.refreshIfStale(applicationContext)
        return AssistantEngine.create(applicationContext, backendChoice, onEnded)
    }

    /**
     * One-shot, off-thread pre-warm of the *conversation code path* so the first "hey jarvis" doesn't
     * pay it. Keeping the process resident (standby) avoids the cold spawn, but the first conversation
     * still cold-loads [AssistantEngine]/the voice backends/OkHttp + builds the shared clients (~1.5 s measured).
     * Here we build the OkHttp clients, class-load the stack (both backends), pre-resolve the selected
     * backend's host DNS ([Backends.warmDns]), and snapshot the installed tool-provider list. **No mic, no
     * overlay, no socket** is opened — pure warm-up, safe in standby.
     */
    private fun prewarm() {
        if (prewarmed) return
        prewarmed = true
        Thread {
            runCatching {
                com.portal.assistant.util.Http.shared // builds the OkHttpClient (dispatcher + pool)
                com.portal.assistant.util.Http.lanVoice // local voice trust-all client
                Class.forName("com.portal.assistant.gemini.GeminiBackend")
                Class.forName("com.portal.assistant.gemini.GeminiWireFormat")
                Class.forName("com.portal.assistant.conversation.backend.WebSocketVoiceBackend")
                Class.forName("com.portal.assistant.local.LocalBackend")
                Class.forName("com.portal.assistant.local.LocalWireFormat")
                Class.forName("com.portal.assistant.conversation.AssistantEngine")
                Class.forName("com.portal.commons.PcmCapture")
                Class.forName("com.portal.assistant.audio.PcmPlayer")
                Backends.warmDns(applicationContext)
                LocationProvider.refreshIfStale(applicationContext) // cache IP-geo
                // pre-scan installed tool providers off the wake path
                com.portal.assistant.conversation.tools.ExternalToolProvider.warm(applicationContext)
                // pre-scan installed music apps off the wake path
                com.portal.assistant.conversation.tools.PackageCatalog.warmMusicApps(applicationContext)
                // synthesize the Listening tone + build its track off the wake path
                com.portal.assistant.conversation.ListeningTonePlayer.prewarm()
                DebugLog.log("prewarm done (classes + http + dns + tools + music + tone)")
            }.onFailure { DebugLog.log("prewarm failed: ${it.message}") }
        }.apply {
            isDaemon = true
            name = "assistant-prewarm"
        }.start()
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
        const val ACTION_FOREGROUND = "com.portal.assistant.action.FOREGROUND"
        const val ACTION_BACKGROUND = "com.portal.assistant.action.BACKGROUND"

        private const val CHANNEL_ID = "assistant_listening"
        private const val NOTIFICATION_ID = 1001

        /** Bring the resident service up (or no-op if already up) without starting a conversation. */
        fun standby(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java).setAction(ACTION_STANDBY),
            )
        }

        /** The app came on screen: arm Foreground wake — call from the Activity's `onResume`. */
        fun onForeground(context: Context) {
            // startForegroundService (not startService): onPause/onResume can fire as the app transitions and
            // the resident service may have been killed — starting a *foreground* service is allowed from that
            // state on API 28/29, a plain startService can throw BackgroundServiceStartNotAllowed.
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java).setAction(ACTION_FOREGROUND),
            )
        }

        /** The app left the screen: pause Foreground wake (release the mic) — call from the Activity's `onPause`. */
        fun onBackground(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java).setAction(ACTION_BACKGROUND),
            )
        }

        /** Start a conversation for [trigger] (a no-op while one is live). */
        fun start(context: Context, trigger: Trigger) {
            val intent = Intent(context, AssistantService::class.java).setAction(ACTION_START)
            TriggerExtras.encode(trigger).forEach(intent::putExtra)
            ContextCompat.startForegroundService(context, intent)
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
