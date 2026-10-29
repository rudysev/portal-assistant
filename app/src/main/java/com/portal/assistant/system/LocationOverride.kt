package com.portal.assistant.system

import com.portal.assistant.conversation.tools.lookup.Coordinates
import com.portal.assistant.conversation.tools.lookup.Here
import com.portal.assistant.conversation.tools.lookup.Place
import com.portal.assistant.conversation.tools.lookup.PlaceSearch

/**
 * The location override as it's stored: the user's [text] and, when Weather's place lookup found it on save, the
 * [match] (its resolved name, shown in Settings, and its coordinates, which Weather uses). Text and match are written
 * together ([saved]) and cleared together ([CLEARED]); Settings is the only writer ([LocationProvider]). Without a
 * match, Weather looks the text up on each call. Pure: [LocationProvider] does the prefs.
 */
data class LocationOverride(val text: String, val match: Place?) {

    /** The coordinates Weather uses for "here", when the override was matched. */
    val coordinates: Coordinates? get() = match?.coordinates

    /** This override as the prefs hold it. */
    val stored: Stored
        get() = Stored(text, match?.coordinates?.let { "${it.lat},${it.lon}" }, match?.name)

    /**
     * The override's three prefs values, each null when absent: the [text], the match's [coordinates] ("lat,lon")
     * and its [placeName]. Always written as one, so none outlives the others.
     */
    data class Stored(val text: String?, val coordinates: String?, val placeName: String?)

    companion object {
        /** What clearing the override stores: nothing, so no stale coordinates or name linger. */
        val CLEARED = Stored(text = null, coordinates = null, placeName = null)

        /** What saving [text] stores, given the place lookup's [search]: its match only when found. */
        fun saved(text: String, search: PlaceSearch): LocationOverride =
            LocationOverride(text.trim(), (search as? PlaceSearch.Found)?.place)

        /**
         * The override from what's [stored], or null with no text. A match needs both readable coordinates and a
         * name; anything less (an override saved before they were stored) leaves it text-only.
         */
        fun read(stored: Stored): LocationOverride? {
            val text = stored.text?.ifBlank { null } ?: return null
            val coordinates = stored.coordinates?.let(::parseCoordinates)
            val name = stored.placeName?.ifBlank { null }
            return LocationOverride(text, if (coordinates != null && name != null) Place(name, coordinates) else null)
        }

        /** "Here" for Weather: the [override] (its text and any matched coordinates), else the IP-geo [device]. */
        fun here(override: LocationOverride?, device: DeviceLocation?): Here =
            Here(override?.text, override?.coordinates, device)

        private fun parseCoordinates(stored: String): Coordinates? {
            val parts = stored.split(',').map { it.toDoubleOrNull() }
            val lat = parts.getOrNull(0)
            val lon = parts.getOrNull(1)
            return if (parts.size == 2 && lat != null && lon != null) Coordinates(lat, lon) else null
        }
    }
}
