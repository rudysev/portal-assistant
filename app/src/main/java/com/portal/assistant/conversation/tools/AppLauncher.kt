package com.portal.assistant.conversation.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * The one Android site that launches other apps, via [PackageManager] launcher intents (thin shell, mirrors
 * [MediaControl.play]'s `startActivity` pattern). Pure name→app matching lives in [AppMatch]; this resolves
 * the installed list and acts on the result.
 *
 * Stateless (no deferred work) → no `dispose()`. Runs on the tool-executor thread; `startActivity` from a
 * background/service context needs `FLAG_ACTIVITY_NEW_TASK` (same as `play_music`). All packages are visible
 * because `targetSdk = 29`; raising it past 30 would require a `<queries>` element in the manifest.
 */
class AppLauncher(context: Context) {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val ownPkg = appContext.packageName

    /** Installed launchable apps as (label, package). Filtered to honest launchables; deduped; excludes self. */
    private fun installed(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != ownPkg && it.applicationInfo.enabled }
            .map { AppEntry(it.loadLabel(pm).toString().trim(), it.packageName) }
            .filter { it.label.isNotEmpty() && pm.getLaunchIntentForPackage(it.pkg) != null }
            .distinctBy { it.pkg }
            .toList()
    }

    fun open(name: String): JSONObject {
        val r = AppMatch.best(name, installed())
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
