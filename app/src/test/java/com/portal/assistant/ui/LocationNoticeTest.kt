package com.portal.assistant.ui

import com.portal.assistant.conversation.tools.lookup.Coordinates
import com.portal.assistant.conversation.tools.lookup.Place
import com.portal.assistant.conversation.tools.lookup.PlaceSearch
import com.portal.assistant.system.LocationOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Settings says under Location once an override is saved. */
class LocationNoticeTest {

    @Test fun aFoundPlaceIsShownAsResolved() {
        val found = PlaceSearch.Found(Place("Paris, Île-de-France, France", Coordinates(48.85341, 2.3488)))

        assertEquals("Found Paris, Île-de-France, France.", LocationNotice.afterSave(found))
    }

    @Test fun notFoundAndOfflineBothSayTheyCouldntFindIt() {
        val expected = "Couldn't find that place — saved as typed."

        assertEquals(expected, LocationNotice.afterSave(PlaceSearch.NotFound))
        assertEquals(expected, LocationNotice.afterSave(PlaceSearch.Failed("weather timed out")))
    }

    @Test fun onReopenAMatchedOverrideShowsItsResolvedPlaceAsAfterSaving() {
        val paris = Place("Paris, Île-de-France, France", Coordinates(48.85341, 2.3488))

        assertEquals("Found Paris, Île-de-France, France.", LocationNotice.stored(LocationOverride("Paris", paris)))
    }

    @Test fun onReopenATextOnlyOverrideOrNoneSaysNothing() {
        assertNull(LocationNotice.stored(LocationOverride("Atlantis", null)))
        assertNull(LocationNotice.stored(null))
    }
}
