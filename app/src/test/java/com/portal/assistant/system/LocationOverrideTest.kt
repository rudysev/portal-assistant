package com.portal.assistant.system

import com.portal.assistant.conversation.tools.lookup.Coordinates
import com.portal.assistant.conversation.tools.lookup.Place
import com.portal.assistant.conversation.tools.lookup.PlaceSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The location override as Settings saves it and as prefs give it back, with or without its coordinates. */
class LocationOverrideTest {

    @Test fun aFoundPlaceIsSavedWithItsCoordinates() {
        val search = PlaceSearch.Found(Place("Paris, Île-de-France, France", Coordinates(48.85341, 2.3488)))

        assertEquals(
            LocationOverride("Paris", Coordinates(48.85341, 2.3488)),
            LocationOverride.saved("  Paris ", search),
        )
    }

    @Test fun aPlaceNotFoundOrNotLookedUpIsSavedAsTextOnly() {
        assertEquals(LocationOverride("Atlantis", null), LocationOverride.saved("Atlantis", PlaceSearch.NotFound))
        assertEquals(
            LocationOverride("Paris", null),
            LocationOverride.saved("Paris", PlaceSearch.Failed("weather unavailable")),
        )
    }

    @Test fun storedCoordinatesComeBackExactly() {
        val saved = LocationOverride("Mountain View, CA", Coordinates(lat = 37.38605, lon = -122.08385))

        assertEquals(saved, LocationOverride.read(saved.text, saved.storedCoordinates))
    }

    @Test fun anOverrideSavedBeforeCoordinatesWereStoredIsTextOnly() {
        assertEquals(LocationOverride("Lisbon", null), LocationOverride.read("Lisbon", storedCoordinates = null))
    }

    @Test fun unreadableStoredCoordinatesLeaveTheOverrideTextOnly() {
        assertEquals(LocationOverride("Lisbon", null), LocationOverride.read("Lisbon", "north,west"))
        assertEquals(LocationOverride("Lisbon", null), LocationOverride.read("Lisbon", "38.7"))
    }

    @Test fun coordinatesWithNoTextAreNoOverride() {
        assertNull(LocationOverride.read(null, "38.7,-9.1"))
        assertNull(LocationOverride.read("  ", "38.7,-9.1"))
    }

    @Test fun aTextOnlyOverrideStoresNoCoordinates() {
        assertNull(LocationOverride("Atlantis", null).storedCoordinates)
    }
}
