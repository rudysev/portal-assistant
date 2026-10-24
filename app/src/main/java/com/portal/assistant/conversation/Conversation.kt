package com.portal.assistant.conversation

/**
 * One conversation as its host sees it: [AssistantEngine]'s two calls. The host holds this port, not the engine, so
 * its tests drive a fake; the engine's own end comes back through the `onEnded` it was built with.
 */
interface Conversation {
    /** Open it for [trigger]; null once started, or the [Refusal] — then nothing opened and `onEnded` never fires. */
    fun start(trigger: Trigger): Refusal?

    /** Tear it down without firing `onEnded`. Idempotent. */
    fun stop()
}
