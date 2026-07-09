package com.portal.assistant.conversation.tools

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.portal.assistant.system.AppPrefs
import com.portal.commons.DebugLog
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bridges the assistant to installed **tool provider** apps (the [ToolContract] plugin API). Discovers
 * enabled providers via PackageManager meta-data and invokes their tools via [android.content.ContentResolver.call].
 * Pure policy (allowlist, namespace, shape, collisions) lives in [ExternalToolSpec]; this is the Android shell.
 *
 * **Snapshot once:** discovery runs lazily the first time [declarations] or [promptLines] is read for a
 * session and is cached, so declarations and prompt bullets stay fixed at session setup (same as built-ins). One instance per conversation (held by
 * [ToolRegistry]); [dispose] shuts the invoke executor down on teardown.
 *
 * **Self-timeout:** the engine's stall watchdog is gated off while a tool is in flight, so a hung provider
 * would wedge the conversation. Each invoke runs the cross-process `call()` on a dedicated thread bounded by
 * [EXTERNAL_TIMEOUT_MS]; past it we return an error and move on (the provider may keep running in its own
 * process — provider authors are told to ack fast and stay idempotent).
 */
class ExternalToolProvider(context: Context) {

    private val appContext = context.applicationContext
    private val callExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ext-tool") }

    /** Discovered + parsed once, lazily, on first access — the session snapshot. */
    private val tools: ExternalTools by lazy { discover() }

    fun declarations(): List<JSONObject> = tools.declarations

    /** Enabled-provider prompt bullets — same session [discover] snapshot as [declarations]. */
    fun promptLines(): List<String> = tools.promptLines

    fun handles(name: String): Boolean = name in tools.authorityByName

    fun invoke(name: String, args: JSONObject): JSONObject {
        val authority = tools.authorityByName[name]
            ?: return JSONObject().put("error", "unknown tool: $name")
        return callWithTimeout(authority, name, args)
    }

    fun dispose() {
        callExecutor.shutdownNow()
    }

    private fun discover(): ExternalTools {
        val enabled = AppPrefs.enabledProviders(appContext)
        if (enabled.isEmpty()) return ExternalTools(emptyList(), emptyMap())
        val parsed = ExternalToolSpec.parse(providerSnapshot(enabled), enabled) { DebugLog.log("ext-tool dropped: $it") }
        if (parsed.declarations.isNotEmpty()) {
            DebugLog.log("ext-tool enabled: ${parsed.authorityByName.keys}")
        }
        return parsed
    }

    /**
     * The installed-provider list to parse: the process-level [cachedProviders] snapshot when it already
     * covers every [enabled] provider, otherwise a fresh [query] (which also refreshes the snapshot). The
     * snapshot is warmed off the main thread by [warm], so the first conversation after boot skips the
     * getInstalledPackages scan on the wake→listening path. The cheap per-session parse in [discover] still
     * runs every time, so a Settings enable/disable is always honoured without invalidating the cache, and a
     * provider newly installed *and* enabled mid-process self-heals here (absent from the snapshot → re-query).
     */
    private fun providerSnapshot(enabled: Set<String>): List<DiscoveredProvider> =
        snapshot(appContext, enabled)

    private fun callWithTimeout(authority: String, name: String, args: JSONObject): JSONObject {
        val future = callExecutor.submit<JSONObject> {
            val uri = Uri.parse("content://$authority")
            val extras = Bundle().apply { putString(ToolContract.EXTRA_ARGS_JSON, args.toString()) }
            val out = appContext.contentResolver.call(uri, ToolContract.METHOD_INVOKE, name, extras)
            val resultJson = out?.getString(ToolContract.EXTRA_RESULT_JSON)
                ?: return@submit JSONObject().put("error", "no result from $name")
            // A misbehaving provider could return non-JSON; surface a clean error, not a parser stack message.
            runCatching { JSONObject(resultJson) }.getOrElse { JSONObject().put("error", "invalid result from $name") }
        }
        return runCatching { future.get(EXTERNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrElse { e ->
            future.cancel(true)
            val msg = if (e is TimeoutException) "provider timed out" else (e.cause?.message ?: e.message ?: "error")
            DebugLog.log("ext-tool $name failed: $msg")
            JSONObject().put("error", msg)
        }.also { DebugLog.log("ext-tool $name → ${if (it.has("error")) "error" else "ok"}") }
    }

    /**
     * A tool provider as shown in Settings: its app label, the one-sentence [summary] shown under the label
     * (the provider's required [ToolContract.META_SUMMARY]), and its enabled state.
     */
    data class ProviderUi(val pkg: String, val label: String, val summary: String, val enabled: Boolean)

    companion object {
        private const val EXTERNAL_TIMEOUT_MS = 5_000L // bounded so a hung provider can't wedge the turn

        // Process-level snapshot of the installed tool providers — the result of the expensive
        // getInstalledPackages enumeration in [query]. Warmed off the main thread by [warm] (from the
        // service's prewarm) so the first conversation's declarations() doesn't pay that scan on the
        // wake→listening path. Holds only immutable data (no Context), published via @Volatile; a concurrent
        // warm + cold discover() merely store equivalent snapshots, so the race is benign. Refreshed when
        // [providerSnapshot] misses (a newly enabled provider absent from the snapshot). NOT caught until the
        // next process start: a still-enabled provider that is uninstalled or updated in place mid-process — it
        // stays in the snapshot, so a dead/outdated tool keeps being advertised (the invoke just fails
        // gracefully). Accepted on this appliance, where providers are pre-installed and rarely change.
        @Volatile private var cachedProviders: List<DiscoveredProvider>? = null

        /** Pre-compute the installed-provider snapshot off the main thread. Idempotent; safe to call anytime. */
        fun warm(context: Context) {
            if (cachedProviders == null) cachedProviders = query(context.applicationContext)
        }

        /**
         * The installed-provider list, preferring the process-level [cachedProviders] snapshot warmed by
         * [warm]. Re-queries only when the cache is cold or an [enabled] package is absent from it.
         */
        private fun snapshot(context: Context, enabled: Set<String> = emptySet()): List<DiscoveredProvider> {
            val appContext = context.applicationContext
            val cache = cachedProviders
            if (cache != null && enabled.all { pkg -> cache.any { it.pkg == pkg } }) return cache
            return query(appContext).also { cachedProviders = it }
        }

        /** Every installed ContentProvider carrying the [ToolContract.META_DECLARATIONS] meta-data. */
        fun query(context: Context): List<DiscoveredProvider> = runCatching {
            val pm = context.applicationContext.packageManager

            @Suppress("DEPRECATION", "QueryPermissionsNeeded")
            val pkgs = pm.getInstalledPackages(PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
            buildList {
                for (pi in pkgs) {
                    for (prov in pi.providers ?: continue) {
                        val json = prov.metaData?.getString(ToolContract.META_DECLARATIONS) ?: continue
                        val authority = prov.authority ?: continue
                        val summary = prov.metaData?.getString(ToolContract.META_SUMMARY)?.trim()?.takeIf { it.isNotEmpty() }
                        add(DiscoveredProvider(prov.packageName, authority, json, summary))
                    }
                }
            }
        }.getOrElse {
            DebugLog.log("ext-tool discovery failed: ${it.message}")
            emptyList()
        }

        /** All installed tool providers (allowlist-independent) for the Settings toggle list. */
        fun providersForSettings(context: Context): List<ProviderUi> {
            val enabled = AppPrefs.enabledProviders(context)
            val pm = context.applicationContext.packageManager
            return snapshot(context, enabled).groupBy { it.pkg }.mapNotNull { (pkg, provs) ->
                val parsed = ExternalToolSpec.parse(provs, setOf(pkg))
                if (parsed.authorityByName.isEmpty()) return@mapNotNull null // hide providers with no valid tool
                // The Settings row is the provider's required one-sentence summary (META_SUMMARY), which lives
                // on the component meta-data, not the per-tool declarations — take the first non-null among
                // this package's providers (a package has one in practice). A provider that declares no summary
                // is malformed (same as a tool missing its description) and is hidden — no fallback.
                val summary = provs.firstNotNullOfOrNull { it.summary } ?: run {
                    DebugLog.log("ext-tool $pkg hidden: no ${ToolContract.META_SUMMARY}")
                    return@mapNotNull null
                }
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                    .getOrDefault(pkg)
                ProviderUi(pkg, label, summary, pkg in enabled)
            }.sortedBy { it.label }
        }
    }
}
