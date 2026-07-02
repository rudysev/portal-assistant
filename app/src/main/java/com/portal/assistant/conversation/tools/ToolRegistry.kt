package com.portal.assistant.conversation.tools

import android.content.Context
import com.portal.assistant.conversation.AfterSpeech
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * Routes function calls to registered [Tool]s. The engine holds one instance; built-in tools are
 * registered in [tools]. **External providers** (installed apps via the [ToolContract] plugin API)
 * contribute additional tools through [external] — discovered + allowlist-gated, declared alongside the
 * built-ins, and invoked when a name isn't a built-in. Built-ins always win a name collision.
 *
 * [declarations] is called once at session start to populate the Gemini setup message.
 * [invoke] is called on the tool-executor thread, may block (HTTP / cross-process call), must not touch UI.
 */
class ToolRegistry(context: Context, afterSpeech: AfterSpeech) {
    private val appContext = context.applicationContext
    private val timerScheduler = TimerScheduler(appContext)
    private val volume = VolumeController(appContext, afterSpeech)
    private val brightness = BrightnessController(appContext)
    private val dnd = DoNotDisturbController(appContext, afterSpeech, volume)
    private val media = MediaControl(appContext)
    private val appLauncher = AppLauncher(appContext)
    private val external = ExternalToolProvider(appContext)

    private val tools: List<Tool> = listOf(
        GetTimeTool,
        SetTimerTool(timerScheduler),
        ListTimersTool(timerScheduler),
        CancelTimerTool(timerScheduler),
        GetVolumeTool(volume),
        SetVolumeTool(volume),
        AdjustVolumeTool(volume),
        SetMuteTool(volume),
        GetBrightnessTool(brightness),
        SetBrightnessTool(brightness),
        AdjustBrightnessTool(brightness),
        GetDoNotDisturbTool(dnd),
        SetDoNotDisturbTool(dnd),
        MediaControlTool(media),
        SetRepeatTool(media),
        NowPlayingTool(media),
        PlayMusicTool(media),
        OpenAppTool(appLauncher),
    )

    private val builtinNames: Set<String> = tools.mapTo(HashSet()) { it.name }

    /** Built-in declarations + external ones, with built-ins winning any name collision (external dropped). */
    fun declarations(): List<JSONObject> {
        val externalAll = external.declarations()
        val externalKept = ExternalToolSpec.withoutBuiltinCollisions(externalAll, builtinNames)
        val dropped = externalAll.map { it.optString("name") }.filter { it in builtinNames }
        if (dropped.isNotEmpty()) DebugLog.log("ext-tool dropped (collides with built-in): $dropped")
        return tools.map { it.declaration } + externalKept
    }

    fun invoke(name: String, args: JSONObject): JSONObject {
        tools.firstOrNull { it.name == name }?.let { return it.invoke(args) } // built-ins win
        if (external.handles(name)) return external.invoke(name, args)
        return JSONObject().put("error", "unknown tool: $name")
    }

    /** Release any tool-held resources on conversation teardown (deferred mute/DND, external invoke executor). */
    fun dispose() {
        volume.dispose()
        dnd.dispose()
        external.dispose()
    }
}
