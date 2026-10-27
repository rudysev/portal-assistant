package com.portal.assistant.conversation.tools

import android.content.Context
import com.portal.assistant.audio.Hush
import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Runs one conversation's tools: the [ToolSet], the [AfterSpeech] queue, the tool executor and each batch's
 * bookkeeping. The engine maps the reducer's tool actions onto it (`ExecuteTools` → [run], `FireAfterSpeech` →
 * [turnEnded]), hands it the reducer's tools in flight after every event ([keepOnly]) and disposes it on teardown.
 *
 * **The batch promise:** every [run] delivers exactly one list, once — one result per call, in call order — on
 * [deliver], whatever was cancelled and when. A call cancelled before it started or interrupted while running is
 * `{"error":"cancelled"}`; a call that threw is `{"error": <message>}`. Each call settles exactly once, whichever of
 * "finished" and "cancelled" comes first, and a cancel counts it down, so a batch can neither stall nor complete
 * twice. Nothing is delivered after [dispose].
 *
 * **Final turn:** a call whose [ToolOutput] carries a final-turn instruction settles as a result with the instruction
 * added to its response and [ToolResult.finalTurn] set. Only a call that settles with its own output is marked: one
 * that settled as cancelled, even after its tool asked, or that threw, ends nothing.
 *
 * Threading: [run], [keepOnly], [turnEnded] and [dispose] are called on the engine's handler, which is also
 * [deliver] in production — so the [disposed] check in a delivery can't race [dispose]. Calls run on
 * [toolExecutor], one after another in call order.
 */
class ToolRunner(
    private val toolSet: ToolSet,
    private val afterSpeech: AfterSpeech,
    private val toolExecutor: ExecutorService,
    private val deliver: Executor,
) {
    /** One call of a batch still held: its future, cancelled by [keepOnly], and the batch slot it settles. */
    private class HeldCall(val future: Future<*>, val settleCancelled: () -> Unit)

    /** Calls that haven't settled yet, by call id. */
    private val held = ConcurrentHashMap<String, HeldCall>()

    @Volatile private var disposed = false

    fun declarations(): List<JSONObject> = toolSet.declarations()

    fun externalPromptLines(): List<String> = toolSet.externalPromptLines()

    /** Start one batch: [calls] run in order on the tool executor; [onResults] gets the ordered list, once. */
    fun run(calls: List<FunctionCall>, onResults: (List<ToolResult>) -> Unit) {
        if (disposed) return
        val batch = calls.toList()
        val pending = AtomicInteger(batch.size)
        val results = AtomicReferenceArray<ToolResult>(batch.size)

        fun settle(index: Int, output: ToolOutput) {
            val call = batch[index]
            val instruction = output.finalTurnInstruction
            val response = instruction?.let { output.response.put("instruction", it) } ?: output.response
            val result = ToolResult(call.id, call.name, response, finalTurn = instruction != null)
            if (!results.compareAndSet(index, null, result)) return // already settled
            held.remove(call.id)
            if (pending.decrementAndGet() == 0) {
                val ordered = List(batch.size) { slot -> results.get(slot) }
                deliver.execute { if (!disposed) onResults(ordered) }
            }
        }

        batch.forEachIndexed { index, call ->
            val task = FutureTask<Unit> {
                runCatching { toolSet.invoke(call.name, call.args) }
                    .onSuccess { output -> settle(index, output) }
                    .onFailure { error ->
                        settle(index, ToolOutput(JSONObject().put("error", error.message ?: "error")))
                    }
            }
            // Held before it can run, so a call that finishes at once still takes itself out of [held].
            held[call.id] = HeldCall(task) { settle(index, ToolOutput(JSONObject().put("error", "cancelled"))) }
            toolExecutor.execute(task)
        }
    }

    /**
     * The reducer's current tools in flight: every call still held whose id isn't in [inFlight] is cancelled — never
     * run if it hadn't started, interrupted if it's running. Cheap when nothing is held (the common case).
     */
    fun keepOnly(inFlight: Set<String>) {
        if (held.isEmpty()) return
        held.keys.filter { it !in inFlight }.forEach { id ->
            val call = held[id] ?: return@forEach
            call.future.cancel(true)
            call.settleCancelled()
        }
    }

    /** Run the after-speech effects queued this turn (the reducer's turn-end). */
    fun turnEnded() = afterSpeech.fire()

    /**
     * Teardown: interrupt running tools, drop queued after-speech effects without running them (so a deferred mute
     * or DND can't fire into the next conversation), dispose the tool set, and from then on deliver nothing.
     */
    fun dispose() {
        disposed = true
        toolExecutor.shutdownNow()
        held.clear()
        afterSpeech.clear()
        toolSet.dispose()
    }

    companion object {
        /**
         * Production wiring: one after-speech queue, the [ToolRegistry] given it and the conversation's [hush], and
         * the `tool-exec` thread.
         */
        fun create(context: Context, deliver: Executor, hush: Hush): ToolRunner {
            val afterSpeech = AfterSpeech()
            return ToolRunner(
                toolSet = ToolRegistry(context, afterSpeech, hush),
                afterSpeech = afterSpeech,
                toolExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "tool-exec") },
                deliver = deliver,
            )
        }
    }
}
