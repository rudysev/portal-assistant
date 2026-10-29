package com.portal.assistant.conversation

import com.portal.assistant.system.DeviceLocation

/**
 * Pure helper that appends the **Device Context** section to the system instruction, so the model can answer time/
 * date- and location-relative questions ("what time is it?", relative dates, "near me") without the user stating
 * where or when they are. The caller resolves the clock/zone and location and passes plain strings, so this stays
 * Android-free and unit-tested (same pure-logic pattern as [ResumeContext]).
 *
 * Applied to **both** fresh and resumed openings ([ConversationOpening]), so a mic-tap resume keeps the context.
 * Two bullets: the time at conversation start ([timeLine]; `portal.get_time` gives the time now) and approximate
 * location ([locationLine]/[overrideLine], or [LOCATION_UNKNOWN]). No coordinates: nothing in the prompt reads them
 * (Weather resolves the Settings location itself).
 */
object SystemContext {

    const val HEADER = "# Device Context"

    /** Append a Device Context section ([lines], one bullet each) to [systemPrompt]. No-op when [lines] is empty. */
    fun enrich(systemPrompt: String, lines: List<String>): String = if (lines.isEmpty()) {
        systemPrompt
    } else {
        "$systemPrompt\n\n$HEADER\n" + lines.joinToString("\n") { "- $it" }
    }

    /**
     * The local date/time line, e.g.
     * `"Time at conversation start: Saturday, June 21, 2026 at 9:30 PM (America/Los_Angeles)."`
     * [localDateTime] is preformatted by the caller; [zoneId] is the IANA zone (e.g. `America/Los_Angeles`).
     */
    fun timeLine(localDateTime: String, zoneId: String): String =
        "Time at conversation start: $localDateTime ($zoneId)."

    /** Injected when no location is known yet, so the model asks rather than assuming a place. */
    const val LOCATION_UNKNOWN =
        "Location: unknown — if a question needs a location, ask the user."

    /** The approximate-location line from IP geolocation, e.g. `"Location: Seattle, Washington, United States."` */
    fun locationLine(loc: DeviceLocation): String {
        val place = listOf(loc.city, loc.region, loc.country).filter { it.isNotEmpty() }.joinToString(", ")
        return "Location: $place."
    }

    /** A user-set location override (free text), which wins over IP geolocation. */
    fun overrideLine(place: String): String = "Location: $place."
}
