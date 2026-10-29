package com.portal.assistant.system

import com.portal.assistant.conversation.tools.lookup.Coordinates
import com.portal.assistant.conversation.tools.lookup.FakeLookupHttp
import com.portal.assistant.conversation.tools.lookup.Place
import com.portal.assistant.conversation.tools.lookup.PlaceSearch
import com.portal.assistant.conversation.tools.lookup.Weather
import com.portal.assistant.system.LocationOverride.Stored
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The location override as Settings saves and clears it and as prefs give it back: its text, and the place it was
 * matched to (resolved name and coordinates) when the lookup on save found it.
 */
class LocationOverrideTest {

    @Test fun aFoundPlaceIsSavedWithTheMatchedNameAndCoordinates() {
        assertEquals(LocationOverride("Paris", PARIS), LocationOverride.saved("  Paris ", PlaceSearch.Found(PARIS)))
    }

    @Test fun aPlaceNotFoundOrNotLookedUpIsSavedAsTextOnly() {
        assertEquals(LocationOverride("Atlantis", null), LocationOverride.saved("Atlantis", PlaceSearch.NotFound))
        assertEquals(
            LocationOverride("Paris", null),
            LocationOverride.saved("Paris", PlaceSearch.Failed("weather unavailable")),
        )
    }

    @Test fun aMatchedOverrideStoresItsTextCoordinatesAndPlaceName() {
        assertEquals(
            Stored(text = "Paris", coordinates = "48.85341,2.3488", placeName = "Paris, Île-de-France, France"),
            LocationOverride("Paris", PARIS).stored,
        )
    }

    @Test fun aTextOnlyOverrideStoresNoCoordinatesAndNoPlaceName() {
        assertEquals(
            Stored(text = "Atlantis", coordinates = null, placeName = null),
            LocationOverride("Atlantis", null).stored,
        )
    }

    @Test fun whatIsStoredComesBackExactly() {
        for (saved in listOf(
            LocationOverride("Mountain View, CA", MOUNTAIN_VIEW),
            LocationOverride("Atlantis", null),
        )) {
            assertEquals(saved, LocationOverride.read(saved.stored))
        }
    }

    @Test fun clearingStoresNothingSoTextCoordinatesAndPlaceNameGoTogether() {
        assertEquals(Stored(text = null, coordinates = null, placeName = null), LocationOverride.CLEARED)
        assertNull(LocationOverride.read(LocationOverride.CLEARED))
    }

    @Test fun onceClearedWeatherUsesTheNetworksLocation() {
        val http = FakeLookupHttp().apply { onBody(FORECAST, FORECAST_ONE_DAY) }
        val here = LocationOverride.here(LocationOverride.read(LocationOverride.CLEARED), device = SEATTLE)

        val response = Weather(http.port, { here }, { Locale.UK }, http.clock).invoke(JSONObject()).response

        assertEquals("Seattle, Washington, United States", response.getString("place"))
        assertEquals("47.6062", http.requestsTo(FORECAST).single().query["latitude"])
    }

    @Test fun anOverrideSavedBeforeItsMatchWasStoredIsTextOnly() {
        assertEquals(LocationOverride("Lisbon", null), LocationOverride.read(Stored("Lisbon", null, null)))
        // Coordinates stored before the matched name was: no name to show, so the text is looked up again.
        assertEquals(LocationOverride("Lisbon", null), LocationOverride.read(Stored("Lisbon", "38.7,-9.1", null)))
    }

    @Test fun unreadableStoredCoordinatesLeaveTheOverrideTextOnly() {
        assertEquals(LocationOverride("Lisbon", null), LocationOverride.read(Stored("Lisbon", "north,west", "Lisbon")))
        assertEquals(LocationOverride("Lisbon", null), LocationOverride.read(Stored("Lisbon", "38.7", "Lisbon")))
    }

    @Test fun aMatchWithNoTextIsNoOverride() {
        assertNull(LocationOverride.read(Stored(null, "38.7,-9.1", "Lisbon, Portugal")))
        assertNull(LocationOverride.read(Stored("  ", "38.7,-9.1", "Lisbon, Portugal")))
    }

    private companion object {
        const val FORECAST = "https://api.open-meteo.com/v1/forecast"

        val PARIS = Place("Paris, Île-de-France, France", Coordinates(48.85341, 2.3488))
        val MOUNTAIN_VIEW = Place("Mountain View, California, United States", Coordinates(37.38605, -122.08385))
        val SEATTLE = DeviceLocation("Seattle", "Washington", "United States", lat = 47.6062, lon = -122.3321)

        // Trimmed from a recorded api.open-meteo.com/v1/forecast response (forecast_days=1).
        const val FORECAST_ONE_DAY = """{"current":{"time":"2026-10-29T09:30","temperature_2m":12.3,
            "apparent_temperature":9.6,"weather_code":2,"wind_speed_10m":14.2},
            "daily":{"time":["2026-10-29"],"weather_code":[61],"temperature_2m_max":[14.1],
              "temperature_2m_min":[7.8],"precipitation_probability_max":[60]}}"""
    }
}
