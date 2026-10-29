package com.portal.assistant.system

import com.portal.assistant.conversation.tools.lookup.Coordinates
import com.portal.assistant.conversation.tools.lookup.Weather.PlaceSearch

/**
 * The location override as it's stored: the user's [text] and, when Weather's place lookup found it on save, its
 * [coordinates]. The two are written and cleared together ([LocationProvider.saveOverride], Settings the only
 * writer); without coordinates, Weather looks the text up on each call. Pure: [LocationProvider] does the prefs.
 */
data class LocationOverride(val text: String, val coordinates: Coordinates?) {

    /** [coordinates] as the prefs string ("lat,lon"), or null for a text-only override. */
    val storedCoordinates: String? get() = coordinates?.let { "${it.lat},${it.lon}" }

    companion object {
        /** What saving [text] stores, given the place lookup's [search]: its coordinates only when found. */
        fun saved(text: String, search: PlaceSearch): LocationOverride =
            LocationOverride(text.trim(), (search as? PlaceSearch.Found)?.place?.coordinates)

        /**
         * The override from its stored [text] and [storedCoordinates], or null with no text. Missing or unreadable
         * coordinates (an override saved before they were stored) leave it text-only.
         */
        fun read(text: String?, storedCoordinates: String?): LocationOverride? {
            val place = text?.ifBlank { null } ?: return null
            return LocationOverride(place, storedCoordinates?.let(::parseCoordinates))
        }

        private fun parseCoordinates(stored: String): Coordinates? {
            val parts = stored.split(',').map { it.toDoubleOrNull() }
            val lat = parts.getOrNull(0)
            val lon = parts.getOrNull(1)
            return if (parts.size == 2 && lat != null && lon != null) Coordinates(lat, lon) else null
        }
    }
}
