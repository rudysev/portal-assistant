package com.portal.assistant.conversation.tools

import android.content.Context
import android.os.SystemClock
import com.portal.assistant.audio.Hush
import com.portal.assistant.conversation.tools.lookup.Here
import com.portal.assistant.conversation.tools.lookup.OkHttpLookupHttp
import com.portal.assistant.conversation.tools.lookup.Weather
import com.portal.assistant.conversation.tools.music.AndroidMediaSessions
import com.portal.assistant.conversation.tools.music.AndroidMusicApps
import com.portal.assistant.conversation.tools.music.Music
import com.portal.assistant.system.LocationProvider
import com.portal.commons.DebugLog
import org.json.JSONObject
import java.util.Locale

/**
 * The production [ToolSet]: routes function calls to registered [Tool]s. The [ToolRunner] holds one
 * instance; built-in tools are registered in [tools]. **External providers** (installed apps via the
 * [ToolContract] plugin API) contribute additional tools through [external] — discovered + allowlist-gated, declared
 * alongside the
 * built-ins, and invoked when a name isn't a built-in. Built-ins always win a name collision.
 *
 * [declarations] is called once at session start to populate the Gemini setup message; [externalPromptLines]
 * uses the same external-provider parse snapshot for the session system instruction.
 * [invoke] is called on the tool-executor thread, may block (HTTP / cross-process call), must not touch UI.
 */
class ToolRegistry(context: Context, afterSpeech: AfterSpeech, hush: Hush) : ToolSet {
    private val appContext = context.applicationContext
    private val timerScheduler = TimerScheduler(appContext)
    private val volume = VolumeController(appContext, afterSpeech)
    private val brightness = BrightnessController(appContext)
    private val dnd = DoNotDisturbController(appContext, afterSpeech)
    private val music =
        Music(AndroidMediaSessions(appContext), AndroidMusicApps(appContext), hush, appContext.packageName)
    private val appLauncher = AppLauncher(appContext)

    // Weather reads "here" and the locale when it runs. The override's stored coordinates arrive with the override
    // geocoding on save; until then it's the override text, looked up per call, else the IP-geo location.
    private val weather = Weather(
        OkHttpLookupHttp(),
        here = {
            Here(
                overrideText = LocationProvider.override(appContext),
                overrideCoordinates = null,
                device = LocationProvider.current(appContext),
            )
        },
        locale = { Locale.getDefault() },
        nowMs = { SystemClock.elapsedRealtime() },
    )
    private val external = ExternalToolProvider(appContext)

    // Music builds the **music commands** play_music, media_control and set_repeat (final when they work; a pause
    // under the hush is kept past it) and now_playing (never final: it expects an answer, flags a hushed track).
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
        music.mediaControl,
        music.setRepeat,
        music.nowPlaying,
        music.playMusic,
        OpenAppTool(appLauncher),
        EndConversationTool,
        // A Lookup: declared only when the opening decides on it (a model without Google Search).
        weather,
    )

    private val builtinNames: Set<String> = tools.mapTo(HashSet()) { it.name }

    /** Built-in declarations + external ones, with built-ins winning any name collision (external dropped). */
    override fun declarations(): List<JSONObject> {
        val externalAll = external.declarations()
        val externalKept = ExternalToolSpec.withoutBuiltinCollisions(externalAll, builtinNames)
        val dropped = externalAll.map { it.optString("name") }.filter { it in builtinNames }
        if (dropped.isNotEmpty()) DebugLog.log("ext-tool dropped (collides with built-in): $dropped")
        return tools.map { it.declaration } + externalKept
    }

    /** Enabled external-provider prompt bullets — same session snapshot as [declarations]. */
    override fun externalPromptLines(): List<String> = external.promptLines()

    override fun invoke(name: String, args: JSONObject): ToolOutput {
        val builtIn = tools.firstOrNull { it.name == name } // built-ins win
        // An external provider's tool never makes a final turn: its JSON response goes back as is.
        return when {
            builtIn != null -> builtIn.invoke(args)
            external.handles(name) -> ToolOutput(external.invoke(name, args))
            else -> ToolOutput.error("unknown tool: $name")
        }
    }

    /** Release any tool-held resources on conversation teardown (deferred mute/DND, external invoke executor). */
    override fun dispose() {
        volume.dispose()
        dnd.dispose()
        external.dispose()
    }
}
