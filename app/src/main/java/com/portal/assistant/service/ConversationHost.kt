package com.portal.assistant.service

import com.portal.assistant.conversation.Conversation
import com.portal.assistant.conversation.Trigger
import com.portal.commons.DebugLog

/** What [ConversationHost] tells wake about a conversation; [com.portal.assistant.wake.ForegroundWake] in the app. */
interface ConversationWake {
    /** A conversation is about to open the mic — the single slot — so stop listening first. */
    fun conversationStarting()

    /** A conversation ended, or a Trigger was refused before one opened: re-arm if still on screen. */
    fun conversationEnded()
}

/** What [ConversationHost] shows the user outside a conversation: the hub's notice banner and transcript. */
interface ConversationNotices {
    fun postNotice(notice: String)

    fun clearHistory()
}

/**
 * [AssistantService]'s own rules: standby ↔ at most one [Conversation]. A trigger while one is live starts nothing; a
 * refused trigger posts its notice and re-arms wake; every way back to standby (the conversation's own end, [end],
 * [newConversation]) refreshes the notification and re-arms wake. It never decides how a conversation opens or runs:
 * the only refusal rule is the engine's. Every call, and `onEnded`, runs on the main thread.
 */
class ConversationHost(
    private val conversations: (onEnded: () -> Unit) -> Conversation,
    private val wake: ConversationWake,
    private val notices: ConversationNotices,
    private val onActiveChanged: (active: Boolean) -> Unit,
) {
    private var conversation: Conversation? = null

    val live: Boolean get() = conversation != null

    fun start(trigger: Trigger) {
        // Idempotent: a trigger (tap or wake) while a turn is already live is a no-op — a deliberate product
        // choice. This holds in BOTH phases: during SPEAKING (half-duplex, mic muted, no barge-in) and during
        // LISTENING/multi-turn re-listen (a second wake/tap can't start a parallel conversation or "refresh"
        // the turn — the user just keeps talking).
        if (conversation != null) return
        wake.conversationStarting() // free the single mic slot before the conversation opens it
        DebugLog.log("AssistantService START (trigger=${trigger.logLabel}) → conversation")
        val newConversation = conversations {
            // The engine has already torn down (mic released, bar hidden) before this fires.
            DebugLog.log("conversation ended → standby")
            returnToStandby()
        }
        val refusal = newConversation.start(trigger)
        if (refusal != null) {
            notices.postNotice(refusal.notice)
            wake.conversationEnded() // nothing opened — re-arm if still on screen
            return
        }
        conversation = newConversation
        onActiveChanged(true)
    }

    /**
     * End the current turn (if any) but stay in standby. Idempotent. [Conversation.stop] tears down **without**
     * invoking `onEnded`, so this external-stop path runs the same [returnToStandby] cleanup itself.
     */
    fun end() {
        DebugLog.log("AssistantService STOP → standby")
        conversation?.stop() // releases mic + hides bar; does NOT call onEnded
        returnToStandby()
    }

    /**
     * "New conversation": tear the current turn down, then clear the on-screen transcript. Both run on the main
     * thread in order (after the synchronous [Conversation.stop]), so a still-live engine can't repopulate the hub
     * after the clear — the race a UI-thread `stop()` + `clearHistory()` would have.
     */
    fun newConversation() {
        end()
        notices.clearHistory()
    }

    /** The service is going away: stop the conversation but skip [returnToStandby] — its notification is moot. */
    fun close() {
        conversation?.stop()
        conversation = null
    }

    /** Back to standby: drop the conversation, restore the idle notification, re-arm wake. */
    private fun returnToStandby() {
        conversation = null
        onActiveChanged(false)
        wake.conversationEnded()
    }
}
