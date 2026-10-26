package com.portal.assistant.conversation.tools

/**
 * The tools' final-turn port, scoped to the call that makes the request. A tool calls [request] while it runs; the
 * [ToolRunner] passes the request on to [onFinalTurn] only if that call then settles with its own result. A call
 * cancelled mid-run (a barge-in) settles as `{"error":"cancelled"}`, and the model was told so, so its request is
 * dropped. One instance per conversation, shared by the [ToolRegistry] (as `requestFinalTurn`) and the runner.
 *
 * Threading: [request] is called synchronously from a tool's `invoke` on the tool-executor thread, inside [during].
 */
class FinalTurnRequests(private val onFinalTurn: () -> Unit) {

    private val requested = ThreadLocal.withInitial { false }

    /** A tool asks for the final turn. Counts only once its call settles with its own result. */
    fun request() = requested.set(true)

    /** Run one call's [invoke]: its result, and whether it requested the final turn while running. */
    internal fun <T> during(invoke: () -> T): Pair<T, Boolean> {
        requested.set(false)
        try {
            val result = invoke()
            return result to (requested.get() == true)
        } finally {
            requested.set(false)
        }
    }

    /** Pass on a request whose call settled with its own result. */
    internal fun grant() = onFinalTurn()
}
