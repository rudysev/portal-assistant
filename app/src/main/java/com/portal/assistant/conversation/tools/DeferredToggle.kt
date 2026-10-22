package com.portal.assistant.conversation.tools

/**
 * One pending "turn it on after the confirmation" effect: report the intended state now, apply it at turn end.
 *
 * Muting the media stream ([VolumeController]) and enabling Do Not Disturb ([DoNotDisturbController]) both
 * silence the assistant's own playback, so each is queued on [AfterSpeech] instead of applied at once. While
 * it waits, [isPending] counts as "on" so the controller tells the model the state it asked for.
 */
class DeferredToggle(private val afterSpeech: AfterSpeech) {

    // The handle queued on [afterSpeech] but not yet applied. @Volatile: scheduled/cancelled on the tool-exec
    // thread, cleared on the engine handler when AfterSpeech fires it.
    @Volatile private var pending: Runnable? = null

    /** True while an effect waits for turn end. */
    val isPending: Boolean get() = pending != null

    /**
     * Queue [effect] to run at the next turn end. Callers check [isPending] first (their "already on" guard), so
     * at most one effect is ever pending.
     */
    fun schedule(effect: () -> Unit) {
        pending = afterSpeech.post {
            effect()
            pending = null
        }
    }

    /** Drop a still-pending effect: an explicit change (unmute, a new level, disable) overrides it. */
    fun cancel() {
        pending?.let { afterSpeech.cancel(it) }
        pending = null
    }

    /**
     * Drop a still-pending effect on conversation teardown so it can't fire into the next one. Tradeoff: on an
     * abnormal/early teardown the model may have confirmed "muted"/"on" but it never applies — fine, the user
     * didn't hear the confirmation and the session is gone.
     */
    fun dispose() = cancel()
}
