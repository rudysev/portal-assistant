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
import com.portal.assistant.conversation.ModelSetup
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.NetworkStatus
import com.portal.assistant.system.WakeModelInstaller
import com.portal.assistant.ui.UiVisibility
import com.portal.commons.DebugLog
import com.portal.commons.audio.WakeDetectors
import com.portal.commons.audio.WakeMatcher
import com.portal.commons.audio.WakeMicConfig
import com.portal.commons.audio.WakeMicEngine
import com.portal.commons.audio.WakeWord
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

    // On-device "hey jarvis" detector, **gen2 (API 29+) only** and active only while this app is foreground.
    // On Android 10 a background mic is OS-silenced, but a resumed foreground app records fine — so hands-free
    // wake works here whenever the assistant is on screen. Built lazily on first foreground detection and kept
    // resident (paused, not unloaded) so re-arming is instant. Gen1 (API 28) skips this entirely and stays
    // portal-wake-only; when this runs, portal-wake yields by *detecting* our recording (no-signal handoff).
    private var detector: WakeMicEngine? = null

    // WakeMicEngine invokes onWake on the capture thread; start/pause belong on main — hop before handoff.
    private val mainHandler = Handler(Looper.getMainLooper())

    // The gen2 wake model is downloaded at runtime (not shipped in the APK — dead weight on gen1). Fetched
    // once on first foreground detection, then the detector loads it from disk. See [WakeModelInstaller].
    private val modelInstaller by lazy { WakeModelInstaller(filesDir) }

    @Volatile private var modelDownloading = false

    // Bounds the delete+re-download recovery to once per process, so a genuinely unloadable model can't loop.
    private var modelReloadTried = false

    // Hard stop for this process: set after repeated download-install failures or a second load failure, so
    // enterDetection stops re-fetching the model / rebuilding a doomed mic-grabbing detector on every foreground.
    @Volatile private var wakeSetupFailed = false
    private var modelDownloadAttempts = 0 // main-thread; failed downloads counted toward MAX_MODEL_DOWNLOAD_ATTEMPTS

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

            // Foreground wake detection (gen2 hands-free): arm while foreground+idle, pause on background.
            ACTION_FOREGROUND -> if (engine == null) enterDetection()

            ACTION_BACKGROUND -> exitDetection()

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
        detector?.close() // tear down the resident wake detector (releases its model)
        detector = null
        DebugLog.log("AssistantService destroyed → mic released")
    }

    private fun startConversation(source: String, initialText: String? = null) {
        // Idempotent: a trigger (tap or wake) while a turn is already live is a no-op — a deliberate product
        // choice. This holds in BOTH phases: during SPEAKING (half-duplex, mic muted, no barge-in) and during
        // LISTENING/multi-turn re-listen (a second wake/tap can't start a parallel conversation or "refresh"
        // the turn — the user just keeps talking).
        if (engine != null) return
        exitDetection() // stop the foreground detector's capture before the conversation opens its mic (single slot)
        running = true
        startForeground(NOTIFICATION_ID, buildNotification(active = true))
        DebugLog.log("AssistantService START (trigger=$source) → conversation")
        // A foreground tap opens the mic immediately; portal-wake yields by detecting our recording
        // (no broadcast needed). On a wake trigger portal-wake has already paused for the handoff.
        // Which backend + how it authenticates is resolved in one place ([Backends.resolve]), read per
        // conversation so a change in Settings → Backend applies on the next turn with no restart.
        val choice = Backends.resolve(applicationContext)
        engine = AssistantEngine(applicationContext, choice) {
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
        // Re-arm foreground wake detection after a turn ends, but only if the app is still on screen; if it's
        // backgrounded the mic stays free so portal-wake (gen1) can reclaim it.
        if (UiVisibility.inForeground) enterDetection()
    }

    /**
     * Start (or resume) foreground "hey jarvis" detection. Built lazily on first use — the wake model loads
     * once and stays resident (see [exitDetection]). Only meaningful while idle (`engine == null`); the
     * [onStartCommand] `ACTION_FOREGROUND` guard enforces that. On a match, routes through the exact same
     * entry point as the portal-wake hand-off ([start]), so a fired wake needs no new plumbing.
     */
    private fun enterDetection() {
        // Gen2 (API 29+) ONLY: A10 silences a background mic, so this foreground detector is the wake path here;
        // gen1 (API 28) is portal-wake's, where a second detector would just fight it for the single mic slot.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // Gave up for this process (the model can't be installed or loaded) — don't re-fetch/rebuild every foreground.
        if (wakeSetupFailed) return
        // Don't build the detector until we can actually record — otherwise a pre-grant onResume
        // loads the model for a capture that can only fail. Re-armed after the first conversation (which is
        // what prompts the RECORD_AUDIO grant) via returnToStandby.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            DebugLog.log("foreground detection skipped — RECORD_AUDIO not granted yet")
            return
        }
        // The model isn't bundled (dead weight on gen1) — download it once on first gen2 use, then arm.
        if (!modelInstaller.isInstalled()) {
            ensureModelThenArm()
            return
        }
        val d = detector ?: WakeMicEngine(
            context = applicationContext,
            config = WakeMicConfig(
                wakeWords = listOf(FOREGROUND_WAKE_WORD),
                detectors = listOf(WakeDetectors.vosk(modelDir = modelInstaller.modelDir())),
                onDetectorUnavailable = { handleModelLoadFailure() },
                onWake = { event ->
                    mainHandler.post {
                        exitDetection()
                        start(applicationContext, "wake:${event.wakeId}")
                    }
                },
                onError = { DebugLog.log("foreground detector error: $it") },
            ),
        ).also { detector = it }
        // start() returns false only when a prior capture thread is wedged and the rebuild-retry was also
        // refused — rare, but log it so a silently-off detector is diagnosable from debug.txt.
        if (!d.start()) DebugLog.log("foreground detector start refused (capture wedged)")
    }

    /**
     * First-run gen2: the wake model isn't in the APK, so fetch it once (off-thread), publishing progress for
     * the idle-home setup indicator ([ConversationHub.setModelSetup]), then re-enter detection to arm. Guarded
     * so a burst of onResume/returnToStandby calls kicks off only one download. Skipped quietly when offline —
     * a later foreground attempt with network still succeeds (the device needs network for Gemini anyway).
     */
    private fun ensureModelThenArm() {
        if (modelDownloading) return
        if (!NetworkStatus.isOnline(this)) {
            DebugLog.log("wake model not downloaded and offline — foreground detection off for now")
            return
        }
        modelDownloading = true
        DebugLog.log("wake model missing → downloading (gen2 first-run)")
        ConversationHub.setModelSetup(ModelSetup.Downloading(0f))
        Thread {
            val ok = modelInstaller.install { p -> ConversationHub.setModelSetup(ModelSetup.Downloading(p)) }
            mainHandler.post {
                modelDownloading = false
                if (ok) {
                    modelDownloadAttempts = 0
                    ConversationHub.setModelSetup(ModelSetup.Idle)
                    // Arm now if still foreground + idle; otherwise the next onResume re-enters.
                    if (UiVisibility.inForeground && engine == null) enterDetection()
                } else {
                    ConversationHub.setModelSetup(ModelSetup.Failed)
                    // Bound the retries: a transient network failure gets a few more foregrounds, but a
                    // permanently-failing install (bad layout / silently-truncated no-length response) stops
                    // re-fetching the model every time. Offline doesn't count — it returns before attempting.
                    if (++modelDownloadAttempts >= MAX_MODEL_DOWNLOAD_ATTEMPTS) {
                        wakeSetupFailed = true
                        DebugLog.log("wake model download failed $modelDownloadAttempts× — giving up this session")
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "wake-model-install"
        }.start()
    }

    /**
     * Pause foreground detection: releases the mic (so a conversation can take it, or portal-wake can reclaim
     * when we background) but **keeps the model resident** so re-arming is instant. `pause()` is idempotent.
     */
    private fun exitDetection() {
        detector?.pause()
    }

    /**
     * The downloaded model failed to load — corrupt, downloaded partially past [WakeModelInstaller.isInstalled]'s
     * gate, or an incompatible format. Tear down the failed detector (it opened the mic to buffer while the
     * model loaded), delete the bad model so a fresh copy is fetched, and re-arm — but only **once** per process,
     * so a genuinely unloadable model can't loop re-downloading on every foreground. Main thread.
     */
    private fun handleModelLoadFailure() {
        if (wakeSetupFailed) return // already gave up this session — don't re-delete or re-enter on a double onUnavailable
        detector?.close() // release the mic/capture the failed detector opened
        detector = null
        if (modelReloadTried) {
            // The re-downloaded model ALSO failed to load — a fresh copy won't help (incompatible/corrupt). Give
            // up for this process AND delete it, so enterDetection (guarded by wakeSetupFailed) stops rebuilding a
            // doomed detector that grabs the mic each foreground, and we don't re-download the model again either.
            wakeSetupFailed = true
            modelInstaller.delete()
            ConversationHub.setModelSetup(ModelSetup.Failed)
            DebugLog.log("wake model load failed again — giving up foreground detection this session")
            return
        }
        modelReloadTried = true
        DebugLog.log("wake model load failed → deleting for re-download")
        modelInstaller.delete()
        if (UiVisibility.inForeground && engine == null) enterDetection()
    }

    /**
     * One-shot, off-thread pre-warm of the *conversation code path* so the first "hey jarvis" doesn't
     * pay it. Keeping the process resident (standby) avoids the cold spawn, but the first conversation
     * still cold-loads [AssistantEngine]/[LiveClient]/[LocalBackend]/OkHttp + builds the shared clients (~1.5 s measured).
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
                Class.forName("com.portal.assistant.gemini.LiveClient")
                Class.forName("com.portal.assistant.conversation.backend.local.LocalBackend")
                Class.forName("com.portal.assistant.conversation.AssistantEngine")
                Class.forName("com.portal.assistant.audio.MicCapture")
                Class.forName("com.portal.assistant.audio.PcmPlayer")
                Backends.warmDns(applicationContext)
                com.portal.assistant.system.LocationProvider.refreshIfStale(applicationContext) // cache IP-geo
                com.portal.assistant.conversation.tools.ExternalToolProvider.warm(applicationContext) // pre-scan installed tool providers off the wake path
                com.portal.assistant.conversation.tools.PackageCatalog.warmMusicApps(applicationContext) // pre-scan installed music apps off the wake path
                DebugLog.log("prewarm done (classes + http + dns + tools + music)")
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
        const val EXTRA_SOURCE = "com.portal.assistant.extra.SOURCE"
        const val EXTRA_INITIAL_TEXT = "com.portal.assistant.extra.INITIAL_TEXT"

        /** Conversation trigger source: a foreground tap (portal-wake yields by detection). */
        const val SOURCE_TAP = "tap"

        /** Conversation trigger source: a tapped suggestion chip — sends its text as the first turn. */
        const val SOURCE_CHIP = "chip"

        /** The foreground detector's wake word — "hey jarvis" at the strict floor, matching portal-wake's
         *  built-in jarvis default so accuracy is identical. Non-null: the phrase is a valid two-word phrase. */
        private val FOREGROUND_WAKE_WORD =
            WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = WakeMatcher.STRICT_MIN_CONF)!!

        // Failed wake-model downloads tolerated this process before giving up (bounds the re-fetch loop against
        // a permanently-failing install; a transient network blip still gets a few foreground retries).
        private const val MAX_MODEL_DOWNLOAD_ATTEMPTS = 3

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

        /** Arm foreground "hey jarvis" detection — call from the Activity's `onResume`. */
        fun onForeground(context: Context) {
            // startForegroundService (not startService): onPause/onResume can fire as the app transitions and
            // the resident service may have been killed — starting a *foreground* service is allowed from that
            // state on API 28/29, a plain startService can throw BackgroundServiceStartNotAllowed.
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java).setAction(ACTION_FOREGROUND),
            )
        }

        /** Pause foreground detection (release the mic) — call from the Activity's `onPause`. */
        fun onBackground(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java).setAction(ACTION_BACKGROUND),
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
