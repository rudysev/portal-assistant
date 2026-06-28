package com.portal.assistant.conversation

/**
 * Pure policy for the **no-speech grace** — a bounded extension of the LISTENING no-speech timeout when the
 * mic still hears the user at the deadline.
 *
 * Why it exists: the no-speech timer is re-armed only by [ConversationState.Event.UserSpeaking], which the
 * engine derives **solely** from the Gemini server's `inputTranscription` (server VAD endpointing +
 * transcription + network RTT). A follow-up started late in the listening window can therefore finish *after*
 * the local 5s timer fires, dropping the in-progress query. The engine feeds a cheap **local** mic-energy
 * signal (last frame whose gained level cleared [VAD_LEVEL]) into [shouldExtend]: while the user is still
 * talking at a checkpoint, defer the end; the budget ([MAX_GRACES]) bounds the case where energy persists but
 * the server never confirms (i.e. noise), so background noise can't pin the mic. A server transcript resets
 * the budget (the engine re-arms via the normal path), making genuine speech effectively unbounded.
 *
 * Kept pure (no Android, no clock) so it's unit-tested on the JVM; the engine owns the timer + the energy.
 */
object NoSpeechGrace {
    /** [com.portal.commons.PcmLevel.normalized] of the **gained** frame at/above which it counts as speech. */
    const val VAD_LEVEL = 0.10f

    /** Local speech seen within this window of a checkpoint → treat the user as still mid-utterance. */
    const val RECENT_SPEECH_MS = 1_500L

    /** One extension step; energy is re-checked at the next checkpoint. */
    const val GRACE_MS = 1_500L

    /** Cap on *consecutive, server-unconfirmed* extensions (≈ MAX_GRACES × GRACE_MS); reset by a transcript. */
    const val MAX_GRACES = 4

    /** Defer the no-speech end iff local speech was heard within [RECENT_SPEECH_MS] and the budget isn't spent. */
    fun shouldExtend(nowMs: Long, lastSpeechMs: Long, gracesUsed: Int): Boolean = lastSpeechMs > 0L && (nowMs - lastSpeechMs) <= RECENT_SPEECH_MS && gracesUsed < MAX_GRACES
}
