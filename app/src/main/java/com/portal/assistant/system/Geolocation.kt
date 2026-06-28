package com.portal.assistant.system

import org.json.JSONObject

/** Approximate device location from IP geolocation. Pure data. */
data class DeviceLocation(
    val city: String,
    val region: String,
    val country: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Pure IP-geolocation parsing — Android-free so it's unit-tested (same pattern as
 * [com.portal.assistant.gemini.LiveClient]'s parsers). The Android shell ([LocationProvider]) does the
 * HTTP + SharedPreferences I/O around these.
 */
object Geolocation {

    /**
     * Parse an IP-geolocation response into a [DeviceLocation], tolerant of both providers we try
     * (`ipwho.is` → `country`/`success`; `ipapi.co` → `country_name`/`error`). Returns null on an
     * error/rate-limit body, a 0,0 fix, or when the essentials (city + finite lat/lon) are missing —
     * callers then fall back to "location unknown".
     */
    fun parse(json: String): DeviceLocation? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("error", false)) return null // ipapi.co failure
        if (o.has("success") && !o.optBoolean("success", true)) return null // ipwho.is failure
        val city = o.optString("city").trim()
        val lat = o.optDouble("latitude", Double.NaN)
        val lon = o.optDouble("longitude", Double.NaN)
        if (city.isEmpty() || lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) return null
        return DeviceLocation(
            city = city,
            region = o.optString("region").trim(),
            country = o.optString("country").ifEmpty { o.optString("country_name") }.trim(),
            lat = lat,
            lon = lon,
        )
    }
}
