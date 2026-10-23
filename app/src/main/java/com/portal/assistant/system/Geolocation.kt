package com.portal.assistant.system

import org.json.JSONObject

/** Approximate device location from IP geolocation. Pure data. */
data class DeviceLocation(val city: String, val region: String, val country: String, val lat: Double, val lon: Double)

/**
 * Pure IP-geolocation parsing — Android-free so it's unit-tested (same pattern as
 * [com.portal.assistant.gemini.GeminiWireFormat]'s decoding). The Android shell ([LocationProvider]) does the
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
        val ipapiFailed = o.optBoolean("error", false)
        val ipwhoFailed = o.has("success") && !o.optBoolean("success", true)
        val city = o.optString("city").trim()
        val lat = o.optDouble("latitude", Double.NaN)
        val lon = o.optDouble("longitude", Double.NaN)
        val failed = ipapiFailed || ipwhoFailed
        val hasFix = !lat.isNaN() && !lon.isNaN() && !(lat == 0.0 && lon == 0.0)
        return if (failed || city.isEmpty() || !hasFix) {
            null
        } else {
            DeviceLocation(
                city = city,
                region = o.optString("region").trim(),
                country = o.optString("country").ifEmpty { o.optString("country_name") }.trim(),
                lat = lat,
                lon = lon,
            )
        }
    }
}
