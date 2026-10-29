package com.portal.assistant.ui

import com.portal.assistant.conversation.tools.lookup.Place
import com.portal.assistant.conversation.tools.lookup.PlaceSearch
import com.portal.assistant.system.LocationOverride

/** What Settings says under Location: the resolved place, that it wasn't found, or that the override was cleared. */
object LocationNotice {
    /** After clearing: weather goes back to the location found from the network. */
    const val CLEARED = "Cleared — weather uses the location found from your network."

    /** Not found and offline read the same: either way the text is saved as typed, looked up on each call. */
    fun afterSave(search: PlaceSearch): String = when (search) {
        is PlaceSearch.Found -> found(search.place)
        PlaceSearch.NotFound, is PlaceSearch.Failed -> "Couldn't find that place — saved as typed."
    }

    /** On opening Settings: the stored override's resolved place, as after saving it; nothing when it has none. */
    fun stored(override: LocationOverride?): String? = override?.match?.let(::found)

    private fun found(place: Place) = "Found ${place.name}."
}
