package com.portal.assistant.conversation

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny queue of side effects to run **once the assistant finishes speaking the current turn**.
 *
 * Some tool side effects silence the assistant's own playback — muting the media stream
 * ([com.portal.assistant.conversation.tools.VolumeController]) or enabling Do Not Disturb
 * ([com.portal.assistant.conversation.tools.DoNotDisturbController]) — so they must not apply until the
 * spoken confirmation has finished. The exact moment is the reducer's turn-end (turnComplete &&
 * playbackIdle && no tools in flight), surfaced as [Action.FireAfterSpeech]; `PcmPlayer` reports drain only
 * after the AudioTrack hardware tail has clocked out, so this is "last sample audibly played", not merely
 * "queue empty" — no magic delay, no clipped confirmation. This replaces the per-controller fixed Handler
 * delays (the old 2.5 s mute / 4 s DND guesses).
 *
 * Threading: [post]/[cancel] run on the tool-executor thread; [fire]/[clear] on the engine's (main) handler.
 * No tool is executing when [fire] runs (turn-end requires `toolsInFlight` empty), so [post] can't race
 * [fire]; the [CopyOnWriteArrayList] covers the tool-thread post vs handler-thread clear regardless.
 *
 * **Interrupt note:** a barge-in keeps the turn in SPEAKING (no [Action.FireAfterSpeech]), so a queued
 * effect defers until the post-interrupt turn ends rather than applying mid-exchange — intentionally more
 * correct than the old wall-clock timer.
 */
class AfterSpeech {

    private val pending = CopyOnWriteArrayList<Runnable>()

    /** Queue [effect] to run at the next turn-end. Returns a handle for targeted [cancel]. */
    fun post(effect: Runnable): Runnable {
        pending.add(effect)
        return effect
    }

    /** Remove a still-queued effect (same-turn reversal, e.g. unmute before the mute applied). */
    fun cancel(handle: Runnable) {
        pending.remove(handle)
    }

    /** Run and clear all queued effects. Called on the engine handler when the turn ends. */
    fun fire() {
        val snapshot = pending.toList()
        pending.clear()
        snapshot.forEach { it.run() }
    }

    /** Drop all queued effects without running them (abnormal teardown). */
    fun clear() = pending.clear()
}
