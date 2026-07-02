package com.portal.assistant.conversation.tools

import android.content.Context
import android.content.Intent
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * The one Android site that launches other apps, via `PackageManager` launcher intents (thin shell, mirrors
 * [MediaControl.play]'s `startActivity` pattern). Pure name→app matching lives in [AppMatch]; the installed
 * list comes from [PackageCatalog]; this resolves a name and acts on the result.
 *
 * Stateless (no deferred work) → no `dispose()`. Runs on the tool-executor thread; `startActivity` from a
 * background/service context needs `FLAG_ACTIVITY_NEW_TASK` (same as `play_music`).
 */
class AppLauncher(context: Context) {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    fun open(name: String): JSONObject {
        val r = AppMatch.best(name, PackageCatalog.launchable(appContext))
        val pick = r.pick
            ?: return JSONObject()
                .put("error", "app not found")
                .put("query", name)
                .put("candidates", r.candidates.fold(org.json.JSONArray()) { a, c -> a.put(c) })
                .also { DebugLog.log("open_app: no match for \"$name\" (candidates=${r.candidates})") }

        val launch = pm.getLaunchIntentForPackage(pick.pkg)
            ?: return JSONObject().put("error", "couldn't open ${pick.label}")
        return runCatching {
            appContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DebugLog.log("open_app → ${pick.pkg} (${r.tier} ${r.score})")
            JSONObject().put("opened", true).put("app", pick.label)
        }.getOrElse {
            DebugLog.log("open_app failed for ${pick.pkg}: ${it.message}")
            JSONObject().put("error", "couldn't open ${pick.label}: ${it.message}")
        }
    }
}
