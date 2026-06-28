package com.portal.assistant.conversation

import com.portal.assistant.system.DeviceLocation
import java.util.Locale

/**
 * Pure helper that appends **device context** to the system instruction so the model can answer time/date-
 * (and, later, location-) relative questions accurately without the user stating where or when they are —
 * e.g. "what time is it?", "what's the weather?", relative dates. The caller resolves the clock/zone (and
 * location) and passes plain strings, so this stays Android-free and unit-tested (same pure-logic pattern
 * as [ResumeContext]).
 *
 * Applied to **both** the fresh and resume prompt branches in `AssistantEngine.start`, so a tap-to-talk
 * resume keeps the context. Currently two lines: local time ([timeLine]) and approximate location
 * ([locationLine]/[overrideLine], or [LOCATION_UNKNOWN]).
 */
object SystemContext {

    const val HEADER = "Device context — use this for time/date- and location-aware answers:"

    /** Append a context block ([lines], one item per line) to [systemPrompt]. No-op when [lines] is empty. */
    fun enrich(systemPrompt: String, lines: List<String>): String = if (lines.isEmpty()) systemPrompt else "$systemPrompt\n\n$HEADER\n" + lines.joinToString("\n")

    /**
     * The local date/time line, e.g.
     * `"Local date/time: Saturday, June 21, 2026 at 9:30 PM (America/Los_Angeles)."`
     * [localDateTime] is preformatted by the caller; [zoneId] is the IANA zone (e.g. `America/Los_Angeles`).
     */
    fun timeLine(localDateTime: String, zoneId: String): String = "Local date/time: $localDateTime ($zoneId)."

    /** Injected when no location is known yet, so the model asks rather than assuming a place. */
    const val LOCATION_UNKNOWN =
        "Approximate device location: unknown — if a question needs a location, ask the user."

    /**
     * The approximate-location line, e.g.
     * `"Approximate device location: Seattle, Washington, United States (lat 47.61, lon -122.33)."`
     * Use this for location-relative answers (weather, "near me") without the user stating where they are.
     */
    fun locationLine(loc: DeviceLocation): String {
        val place = listOf(loc.city, loc.region, loc.country).filter { it.isNotEmpty() }.joinToString(", ")
        val coords = String.format(Locale.US, "(lat %.2f, lon %.2f)", loc.lat, loc.lon)
        return "Approximate device location: $place $coords."
    }

    /** A user-set location override (free text), which wins over IP geolocation. */
    fun overrideLine(place: String): String = "Approximate device location: $place (set by the user)."
}
