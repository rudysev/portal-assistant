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

    private var engine: AssistantEngine? = null

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
            onWake = { wakeId -> startConversation(Trigger.WakeWord(wakeId)) },
            onSetup = WakeSetupStatus::set,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(active = engine != null)) // resident; comes up fast
        when (intent?.action) {
            ACTION_STOP -> endConversation()

            ACTION_NEW_CONVERSATION -> newConversation()

            ACTION_START -> when (val trigger = decodeTrigger(intent)) {
                null -> DebugLog.log("START ignored — the intent carries no trigger")
                else -> startConversation(trigger)
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
        // Service dying: stop the engine and clear the flags, but NOT via returnToStandby() — its
        // notification update is pointless when the service is going away.
        engine?.stop()
        engine = null
        foregroundWake.close() // tear down the resident wake detector (releases its model)
        DebugLog.log("AssistantService destroyed → mic released")
    }

    private fun startConversation(trigger: Trigger) {
        // Idempotent: a trigger (tap or wake) while a turn is already live is a no-op — a deliberate product
        // choice. This holds in BOTH phases: during SPEAKING (half-duplex, mic muted, no barge-in) and during
        // LISTENING/multi-turn re-listen (a second wake/tap can't start a parallel conversation or "refresh"
        // the turn — the user just keeps talking).
        if (engine != null) return
        foregroundWake.conversationStarting() // free the single mic slot before the conversation opens it
        // Which backend + how it authenticates is resolved in one place ([Backends.resolve]), read per
        // conversation so a change in Settings → Backend applies on the next turn with no restart.
        val backendChoice = Backends.resolve(applicationContext)
        // Retry a failed/stale IP-geo lookup (prewarm is one-shot); guarded internally, a no-op when fresh.
        // Result lands for the NEXT conversation — this one opens with whatever's already cached.
        LocationProvider.refreshIfStale(applicationContext)
        DebugLog.log("AssistantService START (trigger=${trigger.logLabel}) → conversation")
        // A foreground tap opens the mic immediately; portal-wake yields by detecting our recording
        // (no broadcast needed). On a wake trigger portal-wake has already paused for the handoff.
        val newEngine = AssistantEngine.create(applicationContext, backendChoice) {
            // The engine has already torn down (mic released, bar hidden) before this fires.
            DebugLog.log("conversation ended → standby")
            returnToStandby()
        }
        val refusal = newEngine.start(trigger)
        if (refusal != null) {
            ConversationHub.postNotice(refusal.notice)
            foregroundWake.conversationEnded() // nothing opened — re-arm if still on screen
            return
        }
        engine = newEngine
        startForeground(NOTIFICATION_ID, buildNotification(active = true))
    }

    /**
     * Back to resident standby: drop the engine, restore the idle notification, re-arm Foreground wake. Shared by
     * the engine's own `onEnded` (it has already torn down) and [endConversation] (which tears down first).
     */
    private fun returnToStandby() {
        engine = null
        startForeground(NOTIFICATION_ID, buildNotification(active = false))
        foregroundWake.conversationEnded()
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
                Class.forName("com.portal.assistant.audio.MicCapture")
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
        const val ACTION_FOREGROUND = "com.portal.assistant.action.FOREGROUND"
        const val ACTION_BACKGROUND = "com.portal.assistant.action.BACKGROUND"

        // The Trigger's Intent encoding — written by [start], read by [decodeTrigger]; nothing else knows these.
        private const val EXTRA_TRIGGER = "com.portal.assistant.extra.TRIGGER"
        private const val EXTRA_WAKE_ID = "com.portal.assistant.extra.WAKE_ID"
        private const val EXTRA_CHIP_TEXT = "com.portal.assistant.extra.CHIP_TEXT"
        private const val TRIGGER_WAKE_WORD = "wake"
        private const val TRIGGER_MIC_TAP = "tap"
        private const val TRIGGER_SUGGESTION_CHIP = "chip"

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
            ContextCompat.startForegroundService(
                context,
                encodeTrigger(Intent(context, AssistantService::class.java).setAction(ACTION_START), trigger),
            )
        }

        private fun encodeTrigger(intent: Intent, trigger: Trigger): Intent = when (trigger) {
            is Trigger.WakeWord -> intent.putExtra(
                EXTRA_TRIGGER,
                TRIGGER_WAKE_WORD,
            ).putExtra(EXTRA_WAKE_ID, trigger.wakeId)

            Trigger.MicTap -> intent.putExtra(EXTRA_TRIGGER, TRIGGER_MIC_TAP)

            is Trigger.SuggestionChip -> intent.putExtra(
                EXTRA_TRIGGER,
                TRIGGER_SUGGESTION_CHIP,
            ).putExtra(EXTRA_CHIP_TEXT, trigger.text)
        }

        /**
         * The [Trigger] [encodeTrigger] wrote, or null when the Intent doesn't carry a whole one — then nothing starts.
         */
        private fun decodeTrigger(intent: Intent): Trigger? = when (intent.getStringExtra(EXTRA_TRIGGER)) {
            TRIGGER_WAKE_WORD -> intent.getStringExtra(EXTRA_WAKE_ID)?.let(Trigger::WakeWord)

            TRIGGER_MIC_TAP -> Trigger.MicTap

            TRIGGER_SUGGESTION_CHIP -> intent.getStringExtra(EXTRA_CHIP_TEXT)?.takeIf {
                it.isNotBlank()
            }?.let(Trigger::SuggestionChip)

            else -> null
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
