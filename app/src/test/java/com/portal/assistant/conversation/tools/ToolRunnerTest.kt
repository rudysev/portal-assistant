package com.portal.assistant.conversation.tools

import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * JVM tests for the [ToolRunner]'s batch promise — N calls in, one ordered list of N results out, whatever was
 * cancelled and when, and nothing after [ToolRunner.dispose]. Driven only through the runner's interface, with a
 * scripted [FakeToolSet], a hand-stepped `tool-exec` executor (the test picks when each call runs) and direct
 * delivery.
 */
class ToolRunnerTest {

    private val afterSpeech = AfterSpeech()
    private val tools = FakeToolSet()
    private val toolExec = SteppedExecutor()
    private val runner = ToolRunner(tools, afterSpeech, toolExec, Executor { it.run() })
    private val deliveries = mutableListOf<List<ToolResult>>()

    private fun call(id: String, name: String = "tool_$id") = FunctionCall(id, name, JSONObject())

    private fun run(vararg calls: FunctionCall) = runner.run(calls.toList()) { deliveries += it }

    /** Each delivered result as `id=response`, so a whole batch reads as one comparable list. */
    private fun List<ToolResult>.render() = map { "${it.id}=${it.response}" }

    @Test fun aBatchDeliversOneListOfEveryResultInCallOrder() {
        tools.returns("tool_a", JSONObject().put("ok", "a"))
        tools.returns("tool_b", JSONObject().put("ok", "b"))
        tools.returns("tool_c", JSONObject().put("ok", "c"))
        run(call("a"), call("b"), call("c"))

        toolExec.runNext()
        toolExec.runNext()
        assertEquals(0, deliveries.size) // nothing until the last call is in
        toolExec.runNext()

        assertEquals(listOf("tool_a", "tool_b", "tool_c"), tools.invoked)
        assertEquals(1, deliveries.size)
        assertEquals(listOf("""a={"ok":"a"}""", """b={"ok":"b"}""", """c={"ok":"c"}"""), deliveries.single().render())
    }

    @Test fun cancellingACallBeforeItStartsStillDeliversTheWholeBatch() {
        tools.returns("tool_b", JSONObject().put("ok", "b"))
        run(call("a"), call("b"))

        runner.keepOnly(setOf("b")) // the model cancels A while it's still queued
        toolExec.runAll()

        assertEquals(1, deliveries.size)
        assertEquals(listOf("""a={"error":"cancelled"}""", """b={"ok":"b"}"""), deliveries.single().render())
    }

    @Test fun aCallCancelledWhileRunningIsInterruptedAndCountedOnce() {
        tools.script("tool_a") {
            runner.keepOnly(setOf("b")) // the model cancels A mid-run…
            JSONObject().put("ok", "a") // …and the interrupted tool finishes anyway
        }
        tools.returns("tool_b", JSONObject().put("ok", "b"))
        run(call("a"), call("b"))

        toolExec.runNext()
        assertEquals(listOf("tool_a"), tools.interruptedWhileRunning)
        assertEquals(0, deliveries.size) // A counted once: the batch still waits on B
        toolExec.runNext()

        assertEquals(1, deliveries.size)
        assertEquals(listOf("""a={"error":"cancelled"}""", """b={"ok":"b"}"""), deliveries.single().render())
    }

    @Test fun aThrowingToolAnswersItsMessageAsAnErrorForThatCallOnly() {
        tools.script("tool_a") { throw IllegalStateException("no such app") }
        tools.script("tool_b") { throw IllegalStateException() }
        tools.returns("tool_c", JSONObject().put("ok", "c"))
        run(call("a"), call("b"), call("c"))

        toolExec.runAll()

        assertEquals(
            listOf("""a={"error":"no such app"}""", """b={"error":"error"}""", """c={"ok":"c"}"""),
            deliveries.single().render(),
        )
    }

    @Test fun anUnknownOrFailingToolDoesNotHideTheCallsAfterIt() {
        tools.returns("nope", JSONObject().put("error", "unknown tool: nope"))
        tools.returns("tool_b", JSONObject().put("error", "provider failed"))
        tools.returns("tool_c", JSONObject().put("ok", "c"))
        run(call("a", name = "nope"), call("b"), call("c"))

        toolExec.runAll()

        assertEquals(listOf("nope", "tool_b", "tool_c"), tools.invoked)
        assertEquals(
            listOf("""a={"error":"unknown tool: nope"}""", """b={"error":"provider failed"}""", """c={"ok":"c"}"""),
            deliveries.single().render(),
        )
    }

    @Test fun disposingMidBatchDeliversNothingDropsQueuedEffectsAndDisposesTheToolSet() {
        var effectRan = false
        tools.script("tool_a") {
            afterSpeech.post { effectRan = true } // e.g. a deferred mute
            JSONObject().put("ok", "a")
        }
        tools.script("tool_b") {
            runner.dispose() // the conversation is torn down while B runs…
            JSONObject().put("ok", "b") // …and B finishes anyway, completing the batch
        }
        run(call("a"), call("b"))

        toolExec.runAll()
        runner.turnEnded()

        assertEquals(0, deliveries.size)
        assertFalse(effectRan)
        assertTrue(tools.disposed)
    }

    @Test fun turnEndedRunsTheEffectsToolsPostedOnce() {
        var effectRuns = 0
        tools.script("tool_a") {
            afterSpeech.post { effectRuns++ }
            JSONObject().put("ok", "a")
        }
        run(call("a"))
        toolExec.runAll()
        assertEquals(0, effectRuns) // deferred until the turn ends

        runner.turnEnded()
        runner.turnEnded()

        assertEquals(1, effectRuns)
    }

    @Test fun keepingEveryCallInFlightCancelsNothing() {
        tools.script("tool_a") {
            runner.keepOnly(setOf("a", "b")) // an event mid-run that left both in flight
            JSONObject().put("ok", "a")
        }
        tools.returns("tool_b", JSONObject().put("ok", "b"))
        run(call("a"), call("b"))

        runner.keepOnly(setOf("a", "b"))
        toolExec.runAll()

        assertEquals(emptyList<String>(), tools.interruptedWhileRunning)
        assertEquals(listOf("""a={"ok":"a"}""", """b={"ok":"b"}"""), deliveries.single().render())
    }

    // ---- fakes -------------------------------------------------------------------------------------------------

    /** A [ToolSet] scripted per tool name; an unscripted name answers `{}`. Records what it saw. */
    private class FakeToolSet : ToolSet {
        private val scripts = mutableMapOf<String, () -> JSONObject>()
        val invoked = mutableListOf<String>()
        val interruptedWhileRunning = mutableListOf<String>()
        var disposed = false

        fun returns(name: String, result: JSONObject) = script(name) { result }

        fun script(name: String, body: () -> JSONObject) {
            scripts[name] = body
        }

        override fun declarations(): List<JSONObject> = listOf(JSONObject().put("name", "tool"))

        override fun externalPromptLines(): List<String> = listOf("- a provider")

        override fun invoke(name: String, args: JSONObject): JSONObject {
            invoked += name
            val result = scripts[name]?.invoke() ?: JSONObject()
            if (Thread.currentThread().isInterrupted) interruptedWhileRunning += name
            return result
        }

        override fun dispose() {
            disposed = true
        }
    }

    /**
     * The `tool-exec` executor, stepped by hand on the test thread. Like a single-thread pool, it clears a task's
     * interrupt before running the next one, and [shutdownNow] drops whatever is still queued.
     */
    private class SteppedExecutor : AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("shut down")
            queue.add(command)
        }

        /** Run the next queued task; false when none is queued. */
        fun runNext(): Boolean {
            val task = queue.poll() ?: return false
            task.run()
            Thread.interrupted()
            return true
        }

        fun runAll() {
            while (runNext()) {
                // Each runNext() call runs one queued task.
            }
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): List<Runnable> {
            shutdown = true
            return queue.toList().also { queue.clear() }
        }

        override fun isShutdown() = shutdown

        override fun isTerminated() = shutdown && queue.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
