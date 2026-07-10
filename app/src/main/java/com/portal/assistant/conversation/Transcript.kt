package com.portal.assistant.conversation

/**
 * Pure, immutable accumulator that folds the Live API's streaming input/output transcription deltas
 * into an ordered list of conversation [Turn]s for the chat UI. No Android, no I/O — so it's exhaustively
 * unit-tested (the same pure-logic pattern as [ConversationState]).
 *
 * The engine drives turn boundaries explicitly from the conversation phase — it knows when the user
 * (re)starts speaking and when the model takes the turn — so this class never has to *infer* them:
 * [beginUserTurn] / [beginModelTurn] arm a fresh turn, and [appendUser] / [appendModel] grow the active
 * turn of that role, creating one lazily on the first non-empty delta (so no empty bubble appears
 * before any words are spoken).
 */
data class Transcript(
    val turns: List<Turn> = emptyList(),
    private val userTurnId: Long = NONE,
    private val modelTurnId: Long = NONE,
    // Raw, un-stripped text of the active model turn. The model streams markdown (**bold**, "1." lists); we
    // keep the raw accumulation here and store the markdown-stripped form in Turn.text, re-stripping the FULL
    // raw on each delta so a marker split across deltas (e.g. "*" then "*") still strips correctly.
    private val modelRaw: String = "",
) {
    /** Arm a fresh USER turn for the next user delta (called when we (re)enter LISTENING). */
    fun beginUserTurn(): Transcript = copy(userTurnId = NONE)

    /** Arm a fresh MODEL turn for the next model delta (called when the model takes the turn). */
    fun beginModelTurn(): Transcript = copy(modelTurnId = NONE, modelRaw = "")

    fun appendUser(delta: String): Transcript = append(Role.USER, userTurnId, delta)

    /**
     * Append a model-text delta. The turn's display text is the markdown-[Markdown.strip]ped accumulation —
     * the answer is spoken, so the transcript reads as plain prose, not raw markup. Created lazily on the
     * first delta that survives stripping (so leading markup alone doesn't open an empty bubble).
     */
    fun appendModel(delta: String): Transcript {
        if (delta.isEmpty()) return this
        val raw = modelRaw + delta
        val clean = Markdown.strip(raw)
        val idx = if (modelTurnId == NONE) -1 else turns.indexOfLast { it.id == modelTurnId }
        if (idx < 0) {
            if (clean.isEmpty()) return copy(modelRaw = raw) // only markup so far → keep accumulating
            val turn = Turn(Role.MODEL, clean)
            return copy(turns = turns + turn, modelTurnId = turn.id, modelRaw = raw)
        }
        val patched = turns.toMutableList()
        patched[idx] = patched[idx].copy(text = clean) // clean is the full stripped accumulation, not a delta
        return copy(turns = patched, modelRaw = raw)
    }

    private fun append(role: Role, currentId: Long, delta: String): Transcript {
        if (delta.isEmpty()) return this
        val idx = if (currentId == NONE) -1 else turns.indexOfLast { it.id == currentId }
        if (idx < 0) {
            val turn = Turn(role, delta)
            return copy(
                turns = turns + turn,
                userTurnId = if (role == Role.USER) turn.id else userTurnId,
                modelTurnId = if (role == Role.MODEL) turn.id else modelTurnId,
            )
        }
        val patched = turns.toMutableList()
        patched[idx] = patched[idx].copy(text = patched[idx].text + delta)
        return copy(turns = patched)
    }

    private companion object {
        const val NONE = -1L
    }
}
