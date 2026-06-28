package com.portal.assistant.system

import android.content.Context
import com.portal.assistant.util.Http
import com.portal.commons.DebugLog
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Approximate device location for system context (e.g. "what's the weather?"). Keyless **IP geolocation**,
 * tried `ipwho.is` first (more generous) then `ipapi.co` (the free geocoders rate-limit hard, so we fall
 * back across providers). On first successful fetch the result is stored in
 * [AppPrefs.KEY_LOC_JSON] once — the device is fixed, so it never re-fetches. [detectedLabel] supplies
 * the city+region label for the Settings pre-fill; the user can confirm or edit it into an explicit
 * [AppPrefs.KEY_LOC_OVERRIDE]. Prompt routing: override wins (labeled "set by the user") → IP-geo with
 * full lat/lon → LOCATION_UNKNOWN. Pure parse logic lives in [Geolocation]; this is the thin
 * Android/HTTP/prefs shell. All prefs keys live in [AppPrefs].
 */
object LocationProvider {

    private val PROVIDERS = listOf("https://ipwho.is/", "https://ipapi.co/json/")

    private val refreshing = AtomicBoolean(false)

    /** Best known IP-geo location (lat/lon + place), or null until the first fetch succeeds. */
    fun current(context: Context): DeviceLocation? = AppPrefs.prefs(context).getString(AppPrefs.KEY_LOC_JSON, null)?.let { Geolocation.parse(it) }

    /** Explicit user-set location override (free text), or null when only the IP-geo result is available. */
    fun override(context: Context): String? = AppPrefs.prefs(context).getString(AppPrefs.KEY_LOC_OVERRIDE, null)?.ifBlank { null }

    fun setOverride(context: Context, place: String) {
        AppPrefs.prefs(context).edit().putString(AppPrefs.KEY_LOC_OVERRIDE, place.trim()).apply()
    }

    /** Human label for the Settings pre-fill: "City, Region" from the IP-geo cache, or null. */
    fun detectedLabel(context: Context): String? = current(context)?.let { loc ->
        listOf(loc.city, loc.region).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
    }

    /**
     * Fire a background lookup if the IP-geo result hasn't landed yet. Safe to call repeatedly (prewarm +
     * each conversation start) — retries until a parseable result is stored. Once [AppPrefs.KEY_LOC_JSON]
     * holds a valid result, this is a no-op (the device is fixed; no TTL re-fetch needed). The fetched
     * result is stored as structured JSON only; it never auto-promotes to [AppPrefs.KEY_LOC_OVERRIDE] —
     * that key is reserved for explicit user saves.
     */
    fun refreshIfStale(context: Context) {
        val p = AppPrefs.prefs(context)
        // Already have a parseable geo result — done.
        if (p.getString(AppPrefs.KEY_LOC_JSON, null)?.let { Geolocation.parse(it) != null } == true) return
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            runCatching {
                for (url in PROVIDERS) {
                    val body = runCatching { httpGet(url) }.getOrNull().orEmpty()
                    val loc = body.takeIf { it.isNotEmpty() }?.let { Geolocation.parse(it) } ?: continue
                    p.edit().putString(AppPrefs.KEY_LOC_JSON, body).apply()
                    DebugLog.log("location fetched: ${loc.city}, ${loc.region} ($url)")
                    return@runCatching
                }
                DebugLog.log("location refresh: no provider returned a usable fix")
            }.onFailure { DebugLog.log("location refresh failed: ${it.message}") }
            refreshing.set(false)
        }.apply {
            isDaemon = true
            name = "location-refresh"
        }.start()
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "portal-assistant").build()
        return Http.shared.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.log("location http ${resp.code} from $url") // surfaces 429 vs network errors
                return ""
            }
            resp.body?.string().orEmpty()
        }
    }
}
