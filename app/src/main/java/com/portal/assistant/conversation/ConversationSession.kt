package com.portal.assistant.conversation

/**
 * Immutable snapshot of the live conversation the UI renders — the semantic model the engine publishes via
 * [ConversationHub.session]. One value (rather than scattered phase/turns flows) so the UI reads a single
 * coherent state. A new conversation usually starts fresh; the foreground mic-tap resumes a finished
 * on-screen one (engine decides — see [AssistantEngine.start]), so [turns] can carry over there.
 *
 * High-frequency signals (audio level, reveal progress) are deliberately NOT here — they'd rebuild this whole
 * value per audio chunk; they live as their own flows / on the latest [Turn] (see [RevealProgress]).
 */
data class ConversationSession(
    /**
     * Bumps once per conversation start. Lets the UI detect a new session; future: persistence key.
     * **`id == 0` is the bootstrap sentinel** — the pre-conversation value before any `startFresh`/
     * `clearHistory` (the greeting screen); real conversations start at 1.
     */
    val id: Long,
    val phase: ConversationHub.UiPhase = ConversationHub.UiPhase.IDLE,
    val turns: List<Turn> = emptyList(),
)
