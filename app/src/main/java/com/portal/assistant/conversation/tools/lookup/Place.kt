package com.portal.assistant.conversation.tools.lookup

/** A latitude/longitude pair. */
data class Coordinates(val lat: Double, val lon: Double)

/** A place a forecast is for: its spoken [name] and where it is. */
data class Place(val name: String, val coordinates: Coordinates)

/** What looking a place up by name found ([Weather.findPlace]). */
sealed interface PlaceSearch {
    data class Found(val place: Place) : PlaceSearch

    data object NotFound : PlaceSearch

    /** The lookup couldn't be made (offline, timed out, a provider error); [reason] is for the log, never the place. */
    data class Failed(val reason: String) : PlaceSearch
}
