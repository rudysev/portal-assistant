package com.portal.assistant.conversation.tools.lookup

import com.portal.assistant.conversation.tools.ToolOutput
import com.portal.assistant.system.DeviceLocation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Pure JVM tests for **Weather** at the `Tool` seam, behind a fake [LookupHttp] answering with recorded Open-Meteo
 * responses: each test sets where "here" is and the locale, calls the tool and pins the JSON the model reads.
 */
class WeatherTest {

    private val http = FakeLookupHttp()
    private var here = Here(overrideText = null, overrideCoordinates = null, device = SEATTLE)
    private var locale: Locale = Locale.UK

    private fun weather() = Weather(http.port, here = { here }, locale = { locale }, nowMs = http.clock)

    private fun invoke(args: JSONObject = JSONObject()): ToolOutput = weather().invoke(args)

    @Test fun withNoPlaceNamedItIsTheWeatherWhereTheNetworkPutsThePortal() {
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        assertJson(
            """{"place":"Seattle, Washington, United States",
               "temperature_unit":"celsius","wind_unit":"km/h",
               "current":{"temperature":12,"feels_like":10,"conditions":"partly cloudy","wind":14},
               "days":[{"date":"2026-10-29","day":"Thursday","high":14,"low":8,"conditions":"light rain",
                        "precipitation_chance":60}]}""",
            invoke(),
        )
        val query = http.requestsTo(FORECAST).single().query
        assertEquals("47.6062", query["latitude"])
        assertEquals("-122.3321", query["longitude"])
    }

    @Test fun aUsLocaleAsksForAndReportsFahrenheitAndMph() {
        locale = Locale.US
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        val response = invoke().response

        val query = http.requestsTo(FORECAST).single().query
        assertEquals("fahrenheit", query["temperature_unit"])
        assertEquals("mph", query["wind_speed_unit"])
        assertEquals("fahrenheit", response.getString("temperature_unit"))
        assertEquals("mph", response.getString("wind_unit"))
    }

    @Test fun aLocaleOutsideTheUsAsksForAndReportsCelsiusAndKmh() {
        for (other in listOf(Locale.UK, Locale.FRANCE, Locale.CANADA)) {
            locale = other
            http.requests.clear()
            http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

            val response = invoke().response

            val query = http.requestsTo(FORECAST).single().query
            assertEquals("$other", "celsius", query["temperature_unit"])
            assertEquals("$other", "kmh", query["wind_speed_unit"])
            assertEquals("$other", "celsius", response.getString("temperature_unit"))
            assertEquals("$other", "km/h", response.getString("wind_unit"))
        }
    }

    @Test fun aNamedPlaceIsLookedUpAndItsWeatherGivenUnderTheMatchedName() {
        http.onBody(GEOCODING, GEOCODING_PARIS)
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        val response = invoke(JSONObject().put("location", "Paris")).response

        assertEquals("Paris, Île-de-France, France", response.getString("place"))
        val search = http.requestsTo(GEOCODING).single().query
        assertEquals("Paris", search["name"])
        assertEquals("1", search["count"])
        assertEquals("en", search["language"])
        val forecast = http.requestsTo(FORECAST).single().query
        assertEquals("48.85341", forecast["latitude"])
        assertEquals("2.3488", forecast["longitude"])
    }

    @Test fun aNamedPlaceThatCantBeFoundIsAnErrorAndNoForecastIsAskedFor() {
        http.onBody(GEOCODING, GEOCODING_NO_MATCH)

        assertJson("""{"error":"place not found"}""", invoke(JSONObject().put("location", "Atlantis")))
        assertEquals(emptyList<LookupRequest>(), http.requestsTo(FORECAST))
    }

    @Test fun anEmptyResultsListIsAlsoNotFound() {
        http.onBody(GEOCODING, """{"results":[],"generationtime_ms":0.4}""")

        assertJson("""{"error":"place not found"}""", invoke(JSONObject().put("location", "Atlantis")))
    }

    @Test fun daysAskForThatManyDaysOfForecastWithinOneToSeven() {
        val cases = mapOf<Int?, String>(null to "1", 3 to "3", 7 to "7", 0 to "1", -2 to "1", 10 to "7")
        for ((days, asked) in cases) {
            http.requests.clear()
            http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

            invoke(if (days == null) JSONObject() else JSONObject().put("days", days))

            assertEquals("days=$days", asked, http.requestsTo(FORECAST).single().query["forecast_days"])
        }
    }

    @Test fun theForecastIsAskedForCurrentAndDailyValuesInThePlacesOwnTimezone() {
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        invoke()

        val query = http.requestsTo(FORECAST).single().query
        assertEquals("auto", query["timezone"])
        assertEquals("temperature_2m,apparent_temperature,weather_code,wind_speed_10m", query["current"])
        assertEquals(
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
            query["daily"],
        )
    }

    @Test fun eachForecastDayIsReportedInOrder() {
        http.onBody(FORECAST, FORECAST_IMPERIAL_THREE_DAYS)

        val days = invoke(JSONObject().put("days", 3)).response.getJSONArray("days")

        assertEquals(3, days.length())
        assertJson(
            """{"date":"2026-10-31","day":"Saturday","high":55,"low":41,"conditions":"thunderstorm with heavy hail",
               "precipitation_chance":90}""",
            ToolOutput(days.getJSONObject(2)),
        )
    }

    @Test fun aNullValueLeavesOnlyThatFieldOutForThatDay() {
        http.onBody(FORECAST, FORECAST_WITH_NULLS)

        assertJson(
            """{"place":"Seattle, Washington, United States",
               "temperature_unit":"celsius","wind_unit":"km/h",
               "current":{"temperature":12,"conditions":"partly cloudy","wind":14},
               "days":[{"date":"2026-10-29","day":"Thursday","high":14,"low":8,"conditions":"light rain",
                        "precipitation_chance":60},
                       {"date":"2026-10-30","day":"Friday","high":13,"conditions":"overcast"}]}""",
            invoke(JSONObject().put("days", 2)),
        )
    }

    @Test fun anUnknownWeatherCodeIsReportedAsUnknownConditions() {
        http.onBody(FORECAST, FORECAST_IMPERIAL_THREE_DAYS)

        val response = invoke(JSONObject().put("days", 3)).response

        assertEquals("unknown", response.getJSONObject("current").getString("conditions"))
        assertEquals("fog", response.getJSONArray("days").getJSONObject(0).getString("conditions"))
    }

    @Test fun theOverridesStoredCoordinatesWinWithNoPlaceLookup() {
        here = Here(overrideText = "Mountain View, CA", overrideCoordinates = MOUNTAIN_VIEW, device = SEATTLE)
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        val response = invoke().response

        assertEquals("Mountain View, CA", response.getString("place"))
        assertEquals(emptyList<LookupRequest>(), http.requestsTo(GEOCODING))
        val forecast = http.requestsTo(FORECAST).single().query
        assertEquals("37.38605", forecast["latitude"])
        assertEquals("-122.08385", forecast["longitude"])
    }

    @Test fun anOverrideWithNoCoordinatesIsLookedUpByItsText() {
        here = Here(overrideText = "Paris", overrideCoordinates = null, device = SEATTLE)
        http.onBody(GEOCODING, GEOCODING_PARIS)
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        val response = invoke().response

        assertEquals("Paris, Île-de-France, France", response.getString("place"))
        assertEquals("Paris", http.requestsTo(GEOCODING).single().query["name"])
        assertEquals("48.85341", http.requestsTo(FORECAST).single().query["latitude"])
    }

    @Test fun anOverrideThatCantBeFoundIsAnErrorNotTheNetworksLocation() {
        here = Here(overrideText = "Atlantis", overrideCoordinates = null, device = SEATTLE)
        http.onBody(GEOCODING, GEOCODING_NO_MATCH)

        assertJson("""{"error":"place not found"}""", invoke())
        assertEquals(emptyList<LookupRequest>(), http.requestsTo(FORECAST))
    }

    @Test fun aNamedPlaceWinsOverTheOverride() {
        here = Here(overrideText = "Mountain View, CA", overrideCoordinates = MOUNTAIN_VIEW, device = SEATTLE)
        http.onBody(GEOCODING, GEOCODING_PARIS)
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        assertEquals("Paris, Île-de-France, France", invoke(JSONObject().put("location", "Paris")).response["place"])
    }

    @Test fun withNoOverrideAndNoNetworkLocationThereIsNoHere() {
        here = Here(overrideText = null, overrideCoordinates = null, device = null)

        assertJson("""{"error":"location unknown"}""", invoke())
        assertEquals(emptyList<LookupRequest>(), http.requests)
    }

    @Test fun aForecastHttpErrorIsAnError() {
        http.on(FORECAST, LookupResponse.Status(400, """{"error":true,"reason":"Latitude must be in range"}"""))

        assertJson("""{"error":"weather unavailable (HTTP 400)"}""", invoke())
    }

    @Test fun aPlaceLookupHttpErrorIsAnErrorAndNoForecastIsAskedFor() {
        http.on(GEOCODING, LookupResponse.Status(503, ""))

        assertJson("""{"error":"weather unavailable (HTTP 503)"}""", invoke(JSONObject().put("location", "Paris")))
        assertEquals(emptyList<LookupRequest>(), http.requestsTo(FORECAST))
    }

    @Test fun aMalformedBodyIsAnError() {
        for (body in listOf("<html>Bad gateway</html>", """{"current":{}}""", "")) {
            http.requests.clear()
            http.onBody(FORECAST, body)
            assertJson("""{"error":"weather unavailable"}""", invoke())
        }
    }

    @Test fun aMalformedPlaceLookupIsAnError() {
        http.onBody(GEOCODING, """{"results":[{"name":"Paris"}]}""")

        assertJson("""{"error":"weather unavailable"}""", invoke(JSONObject().put("location", "Paris")))
    }

    @Test fun noConnectionIsAnError() {
        http.on(FORECAST, LookupResponse.Failure("Unable to resolve host"))

        assertJson("""{"error":"weather unavailable"}""", invoke())
    }

    @Test fun aTimeoutReportedByThePortIsAnError() {
        http.on(FORECAST, LookupResponse.TimedOut)

        assertJson("""{"error":"weather timed out"}""", invoke())
    }

    @Test fun thePlaceLookupAndTheForecastShareOneDeadline() {
        http.onBody(GEOCODING, GEOCODING_PARIS, elapseMs = 1_500)
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        invoke(JSONObject().put("location", "Paris"))

        assertEquals(Weather.DEADLINE_MS, http.requestsTo(GEOCODING).single().timeoutMs)
        assertEquals(Weather.DEADLINE_MS - 1_500, http.requestsTo(FORECAST).single().timeoutMs)
    }

    @Test fun aPlaceLookupThatUsesUpTheDeadlineLeavesNoForecast() {
        http.onBody(GEOCODING, GEOCODING_PARIS, elapseMs = Weather.DEADLINE_MS)
        http.onBody(FORECAST, FORECAST_METRIC_ONE_DAY)

        assertJson("""{"error":"weather timed out"}""", invoke(JSONObject().put("location", "Paris")))
        assertEquals(emptyList<LookupRequest>(), http.requestsTo(FORECAST))
    }

    // --- Place lookup on its own (Settings matching a location override) ---

    @Test fun findPlaceGivesTheMatchedNameAndCoordinates() {
        http.onBody(GEOCODING, GEOCODING_PARIS)

        assertEquals(
            PlaceSearch.Found(Place("Paris, Île-de-France, France", Coordinates(48.85341, 2.3488))),
            weather().findPlace("  Paris "),
        )
        assertEquals("Paris", http.requestsTo(GEOCODING).single().query["name"])
    }

    @Test fun findPlaceTellsNotFoundFromCouldntLookUp() {
        http.onBody(GEOCODING, GEOCODING_NO_MATCH)
        assertEquals(PlaceSearch.NotFound, weather().findPlace("Atlantis"))

        val offline = FakeLookupHttp().apply { on(GEOCODING, LookupResponse.Failure("Unable to resolve host")) }
        assertEquals(
            PlaceSearch.Failed("weather unavailable"),
            Weather(offline.port, { here }, { locale }, offline.clock).findPlace("Paris"),
        )
    }

    // --- helpers ---

    /** The model reads [expected] exactly: same keys and values (key order isn't part of JSON). */
    private fun assertJson(expected: String, output: ToolOutput) =
        assertEquals(JSONObject(expected).plain(), output.response.plain())

    private fun Any?.plain(): Any? = when (this) {
        is JSONObject -> keys().asSequence().associateWith { get(it).plain() }
        is JSONArray -> (0 until length()).map { get(it).plain() }
        is Number -> toDouble()
        else -> this
    }

    private companion object {
        const val FORECAST = "https://api.open-meteo.com/v1/forecast"
        const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"

        val MOUNTAIN_VIEW = Coordinates(lat = 37.38605, lon = -122.08385)

        val SEATTLE = DeviceLocation("Seattle", "Washington", "United States", lat = 47.6062, lon = -122.3321)

        // Recorded from api.open-meteo.com/v1/forecast (current + daily, timezone=auto, forecast_days=1).
        const val FORECAST_METRIC_ONE_DAY = """{"latitude":47.6,"longitude":-122.33,"timezone":"America/Los_Angeles",
            "current_units":{"temperature_2m":"°C","apparent_temperature":"°C","weather_code":"wmo code",
              "wind_speed_10m":"km/h"},
            "current":{"time":"2026-10-29T09:30","interval":900,"temperature_2m":12.3,"apparent_temperature":9.6,
              "weather_code":2,"wind_speed_10m":14.2},
            "daily_units":{"time":"iso8601","weather_code":"wmo code","temperature_2m_max":"°C",
              "temperature_2m_min":"°C","precipitation_probability_max":"%"},
            "daily":{"time":["2026-10-29"],"weather_code":[61],"temperature_2m_max":[14.1],
              "temperature_2m_min":[7.8],"precipitation_probability_max":[60]}}"""

        // Recorded with temperature_unit=fahrenheit, wind_speed_unit=mph, forecast_days=3. Current code 42 is not a
        // WMO code Open-Meteo documents.
        const val FORECAST_IMPERIAL_THREE_DAYS = """{"latitude":40.71,"longitude":-74.0,"timezone":"America/New_York",
            "current_units":{"temperature_2m":"°F","apparent_temperature":"°F","weather_code":"wmo code",
              "wind_speed_10m":"mp/h"},
            "current":{"time":"2026-10-29T12:30","interval":900,"temperature_2m":61.7,"apparent_temperature":59.2,
              "weather_code":42,"wind_speed_10m":8.9},
            "daily_units":{"time":"iso8601","weather_code":"wmo code","temperature_2m_max":"°F",
              "temperature_2m_min":"°F","precipitation_probability_max":"%"},
            "daily":{"time":["2026-10-29","2026-10-30","2026-10-31"],"weather_code":[45,0,99],
              "temperature_2m_max":[63.4,66.0,54.6],"temperature_2m_min":[50.1,52.3,41.2],
              "precipitation_probability_max":[5,0,90]}}"""

        // Recorded with forecast_days=2 at the edge of the forecast range: Open-Meteo sends null for a value it
        // doesn't have (day two's low and rain chance, and here the current feels-like).
        const val FORECAST_WITH_NULLS = """{"latitude":47.6,"longitude":-122.33,"timezone":"America/Los_Angeles",
            "current":{"time":"2026-10-29T09:30","interval":900,"temperature_2m":12.3,"apparent_temperature":null,
              "weather_code":2,"wind_speed_10m":14.2},
            "daily":{"time":["2026-10-29","2026-10-30"],"weather_code":[61,3],"temperature_2m_max":[14.1,12.8],
              "temperature_2m_min":[7.8,null],"precipitation_probability_max":[60,null]}}"""

        // Recorded from geocoding-api.open-meteo.com/v1/search?name=Paris&count=1&language=en&format=json.
        const val GEOCODING_PARIS = """{"results":[{"id":2988507,"name":"Paris","latitude":48.85341,
            "longitude":2.3488,"elevation":42.0,"feature_code":"PPLC","country_code":"FR","admin1_id":3012874,
            "timezone":"Europe/Paris","population":2138551,"country_id":3017382,"country":"France",
            "admin1":"Île-de-France"}],"generationtime_ms":0.62}"""

        // No match: Open-Meteo leaves "results" out.
        const val GEOCODING_NO_MATCH = """{"generationtime_ms":0.3}"""
    }
}
