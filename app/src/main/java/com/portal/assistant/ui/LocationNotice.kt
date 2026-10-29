package com.portal.assistant.ui

import com.portal.assistant.conversation.tools.lookup.PlaceSearch

/** What Settings says under Location once an override is saved: the resolved place, or that it wasn't found. */
object LocationNotice {
    /** Not found and offline read the same: either way the text is saved as typed, looked up on each call. */
    fun afterSave(search: PlaceSearch): String = when (search) {
        is PlaceSearch.Found -> "Found ${search.place.name}."
        PlaceSearch.NotFound, is PlaceSearch.Failed -> "Couldn't find that place — saved as typed."
    }
}
