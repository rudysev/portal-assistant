package com.portal.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.conversation.tools.TimerStore
import com.portal.assistant.service.AssistantService
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.LocationProvider
import com.portal.assistant.ui.ConversationScreen
import com.portal.assistant.ui.SettingsScreen
import com.portal.assistant.ui.UiVisibility
import com.portal.assistant.ui.theme.AssistantTheme
import kotlinx.coroutines.delay

// The failure/permission banner clears itself after this long (the user can also tap it). Hoisted to the
// activity so the timer keeps running even while Settings is open (e.g. when granting mic permission) —
// otherwise it would reset every time the screen remounts.
private const val NOTICE_AUTO_DISMISS_MS = 7000L

/**
 * The foreground chat UI. Opened by the user only (a wake-triggered turn never launches this — that's
 * the orange bar). It observes the live conversation via [ConversationHub], so opening the app mid-turn
 * shows the in-flight transcript and keeps following it. Voice-only: the mic button starts a turn —
 * *resuming* the previous one when a finished transcript is still on screen, else fresh — the "+" button
 * clears to a new conversation, and the X ends the current one. While the app is in the foreground the
 * engine suppresses the orange overlay (the in-UI state replaces it) — see [UiVisibility.inForeground].
 */
class MainActivity : ComponentActivity() {

    /** A tapped suggestion awaiting the mic-permission result, so the grant callback knows to send its text. */
    private var pendingChipText: String? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val chip = pendingChipText
            pendingChipText = null
            if (granted) {
                if (chip != null) {
                    AssistantService.start(this, source = AssistantService.SOURCE_CHIP, initialText = chip)
                } else {
                    AssistantService.start(this, source = AssistantService.SOURCE_TAP)
                }
            } else {
                ConversationHub.postNotice("Microphone access is needed to talk to Jarvis. Enable it in your device settings.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.importProvisionedKey(applicationContext) // pick up an install-script key before the UI reads it
        // Kick the IP-geo lookup off at launch (setup.sh opens the app at install) so the cache is warm long
        // before the first "what's the weather" — async + self-guarding, a no-op once a fix is stored.
        LocationProvider.refreshIfStale(applicationContext)
        // Bring the resident service up at launch (setup.sh opens the app at install) so prewarm — the
        // installed-provider scan, Live-host DNS, and class-load — runs while the user is on the idle home,
        // seconds before the first tap. Mirrors the boot-time standby; a no-op if the service is already warm.
        // (prewarm is async, so this only helps with real lead time like app-open→tap, not an instant trigger.)
        AssistantService.standby(this)
        clearStaleTranscriptIfIdle() // before first composition → no stale-transcript flash on a cold (re)start
        setContent {
            AssistantTheme {
                var showSettings by remember { mutableStateOf(false) }
                BackHandler(enabled = showSettings) { showSettings = false }

                // Keep the screen awake while a conversation is *live* (any non-IDLE phase) so a long model
                // answer or a listening pause never lets the display sleep mid-conversation — and while
                // Settings is open, since that's an interactive screen the user is actively configuring. Only
                // the idle conversation home releases the flag, so the system screensaver / Portal photo frame
                // takes over there. Collected once here so the flag tracks state on both screens.
                val session by ConversationHub.session.collectAsStateWithLifecycle()
                LaunchedEffect(session.phase, showSettings) {
                    if (session.phase == ConversationHub.UiPhase.IDLE && !showSettings) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // Auto-dismiss the notice here (above the screen switch) so the timer survives opening
                // Settings — the banner itself only renders on the conversation screen.
                val notice by ConversationHub.notice.collectAsStateWithLifecycle()
                LaunchedEffect(notice) {
                    if (notice != null) {
                        delay(NOTICE_AUTO_DISMISS_MS)
                        ConversationHub.clearNotice()
                    }
                }

                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    ConversationScreen(
                        phase = session.phase,
                        turns = session.turns,
                        audioLevel = ConversationHub.audioLevel,
                        notice = notice,
                        onDismissNotice = { ConversationHub.clearNotice() },
                        onMicTap = ::onMicTap,
                        onSuggestion = ::onChipTap,
                        onEnd = { AssistantService.stop(this) },
                        onNewConversation = ::onNewConversation,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UiVisibility.inForeground = true // engine hides the orange bar; in-UI state takes over
        clearStaleTranscriptIfIdle()
        TimerStore.refresh(this) // seed the home-screen timer cards from the persisted store
    }

    /**
     * Open to a fresh greeting unless a conversation is actually live: a *finished* transcript from a prior
     * conversation shouldn't reappear when you come back. A LIVE conversation (phase != IDLE, e.g. started by
     * "hey jarvis" while backgrounded) is left intact so opening mid-turn still shows it. The `turns` guard
     * avoids bumping the session id when there's nothing to clear. Safe to clear directly — Activity
     * lifecycle + engine/service all run on the main thread.
     *
     * Tradeoff (by design): a conversation that ran entirely in the **background** isn't readable after it
     * ends — reopening clears it; you can only open *into* one that's still live.
     */
    private fun clearStaleTranscriptIfIdle() {
        val s = ConversationHub.session.value
        if (s.phase == ConversationHub.UiPhase.IDLE && s.turns.isNotEmpty()) ConversationHub.clearHistory()
    }

    override fun onPause() {
        super.onPause()
        UiVisibility.inForeground = false // backgrounded again → the orange bar returns
    }

    /**
     * "New conversation": end any live turn and clear the transcript back to the greeting. Routed through
     * the service so the stop + clear happen in order on one thread (a live engine can't repopulate the hub
     * after the clear). Clears instantly on one tap (no confirm/undo) — an accepted choice for a glance
     * display where the action is deliberate.
     */
    private fun onNewConversation() = AssistantService.newConversation(this)

    private fun onMicTap() {
        pendingChipText = null // a plain mic tap is not a chip
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            AssistantService.start(this, source = AssistantService.SOURCE_TAP)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * A tapped suggestion: start a fresh conversation that *sends the chip's text* as the first turn (so the
     * model actually answers it), rather than just opening the mic. Same mic-permission gate as [onMicTap] —
     * the multi-turn follow-up still needs the mic.
     */
    private fun onChipTap(text: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            AssistantService.start(this, source = AssistantService.SOURCE_CHIP, initialText = text)
        } else {
            pendingChipText = text
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
