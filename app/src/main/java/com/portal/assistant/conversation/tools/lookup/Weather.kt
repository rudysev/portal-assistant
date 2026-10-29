package com.portal.assistant.conversation.tools.lookup

import com.portal.assistant.conversation.Lookup
import com.portal.assistant.conversation.tools.Tool
import com.portal.assistant.conversation.tools.ToolOutput
import com.portal.assistant.system.DeviceLocation
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** A latitude/longitude pair. */
data class Coordinates(val lat: Double, val lon: Double)

/**
 * Where "here" is when no place is named, as Settings and the network know it when the tool runs: the location
 * override's stored [overrideCoordinates] (written with its [overrideText] when Settings matched it), else
 * [overrideText] looked up on each call, else the IP-geo [device] location.
 */
data class Here(val overrideText: String?, val overrideCoordinates: Coordinates?, val device: DeviceLocation?)

/**
 * **Weather**, `portal.get_weather`: current conditions and a forecast of up to a week, for a named place or here
 * ([Here]), in the units the locale expects. Open-Meteo behind [LookupHttp]: its geocoding finds a named place
 * ([findPlace]), its forecast gives the weather, both within one [DEADLINE_MS]. Every failure is the tool's
 * `{"error": …}`, so the model says it can't check. Pure: "here", the locale and the monotonic clock ([nowMs]) are
 * injected and read when the tool runs.
 */
class Weather(
    private val http: LookupHttp,
    private val here: () -> Here,
    private val locale: () -> Locale,
    private val nowMs: () -> Long,
) : Tool {

    override val name: String = checkNotNull(Lookup.WEATHER.toolName)

    override val declaration: JSONObject = JSONObject(
        """{"name":"$name",
           "description":"Current weather and daily forecast. Omit location for here.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "location":{"type":"STRING","description":"A place name, e.g. 'Paris' or 'Paris, France'. Omit for here."},
               "days":{"type":"INTEGER","description":"Days of forecast from today, 1 to 7 (2 for tomorrow). Default 1."}},
             "required":[]}}""",
    )

    override fun invoke(args: JSONObject): ToolOutput {
        val deadline = nowMs() + DEADLINE_MS
        val named = args.optString("location").trim().ifEmpty { null }
        return try {
            val place = when (val where = named?.let { Where.Named(it) } ?: where(here())) {
                null -> throw LookupFailed("location unknown")
                is Where.Known -> where.place
                is Where.Named -> search(where.name, deadline) ?: throw LookupFailed("place not found: ${where.name}")
            }
            ToolOutput(forecast(place, args.optInt("days", 1).coerceIn(1, MAX_DAYS), deadline))
        } catch (e: LookupFailed) {
            ToolOutput.error(e.message)
        }
    }

    /**
     * Looks [name] up as the tool would, within its own [DEADLINE_MS]: for Settings matching a location override
     * when it's saved.
     */
    fun findPlace(name: String): PlaceSearch = try {
        search(name.trim(), nowMs() + DEADLINE_MS)?.let { PlaceSearch.Found(it) } ?: PlaceSearch.NotFound
    } catch (e: LookupFailed) {
        PlaceSearch.Failed(e.message)
    }

    /** Where "here" is: the override's coordinates, else its text to look up, else the network's location. */
    private fun where(here: Here): Where? {
        val text = here.overrideText?.trim()?.ifEmpty { null }
        val device = here.device
        return when {
            text != null && here.overrideCoordinates != null -> Where.Known(Place(text, here.overrideCoordinates))

            text != null -> Where.Named(text)

            device != null -> Where.Known(
                Place(placeName(device.city, device.region, device.country), Coordinates(device.lat, device.lon)),
            )

            else -> null
        }
    }

    private sealed interface Where {
        data class Known(val place: Place) : Where

        data class Named(val name: String) : Where
    }

    /** The best match for [name], or null when there's none. */
    private fun search(name: String, deadline: Long): Place? {
        val body = get(
            GEOCODING_URL,
            mapOf("name" to name, "count" to "1", "language" to locale().language, "format" to "json"),
            deadline,
        )
        return parsed {
            JSONObject(body).optJSONArray("results")?.optJSONObject(0)?.let { match ->
                Place(
                    placeName(match.optString("name"), match.optString("admin1"), match.optString("country")),
                    Coordinates(match.getDouble("latitude"), match.getDouble("longitude")),
                )
            }
        }
    }

    private fun forecast(place: Place, days: Int, deadline: Long): JSONObject {
        val units = Units.of(locale())
        val body = get(
            FORECAST_URL,
            mapOf(
                "latitude" to place.coordinates.lat.toString(),
                "longitude" to place.coordinates.lon.toString(),
                "current" to "temperature_2m,apparent_temperature,weather_code,wind_speed_10m",
                "daily" to "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                "timezone" to "auto",
                "forecast_days" to days.toString(),
                "temperature_unit" to units.temperatureParam,
                "wind_speed_unit" to units.windParam,
            ),
            deadline,
        )
        return parsed { forecastJson(place, units, JSONObject(body)) }
    }

    /** One GET within what's left of [deadline]; anything but a body is a [LookupFailed]. */
    private fun get(url: String, query: Map<String, String>, deadline: Long): String {
        val remaining = deadline - nowMs()
        val response = if (remaining > 0) {
            http.execute(LookupRequest(url = url, query = query, timeoutMs = remaining))
        } else {
            LookupResponse.TimedOut
        }
        val failure = when (response) {
            is LookupResponse.Body -> return response.text
            is LookupResponse.Status -> "$UNAVAILABLE (HTTP ${response.code})"
            LookupResponse.TimedOut -> TIMED_OUT
            is LookupResponse.Failure -> UNAVAILABLE
        }
        throw LookupFailed(failure)
    }

    /** A provider body that isn't the JSON we expect is [UNAVAILABLE]. */
    private inline fun <T> parsed(read: () -> T): T = try {
        read()
    } catch (e: JSONException) {
        throw LookupFailed(UNAVAILABLE, e)
    } catch (e: DateTimeParseException) {
        throw LookupFailed(UNAVAILABLE, e)
    }

    private fun forecastJson(place: Place, units: Units, forecast: JSONObject): JSONObject {
        val current = forecast.getJSONObject("current")
        val daily = forecast.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val days = JSONArray()
        for (i in 0 until dates.length()) {
            val date = LocalDate.parse(dates.getString(i))
            days.put(
                JSONObject()
                    .put("date", date.toString())
                    .put("day", date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    .put("high", daily.getJSONArray("temperature_2m_max").getDouble(i).roundToInt())
                    .put("low", daily.getJSONArray("temperature_2m_min").getDouble(i).roundToInt())
                    .put("conditions", WeatherCodes.words(daily.getJSONArray("weather_code").getInt(i)))
                    .put("precipitation_chance", daily.getJSONArray("precipitation_probability_max").getInt(i)),
            )
        }
        return JSONObject()
            .put("place", place.name)
            .put("temperature_unit", units.temperatureWords)
            .put("wind_unit", units.windWords)
            .put(
                "current",
                JSONObject()
                    .put("temperature", current.getDouble("temperature_2m").roundToInt())
                    .put("feels_like", current.getDouble("apparent_temperature").roundToInt())
                    .put("conditions", WeatherCodes.words(current.getInt("weather_code")))
                    .put("wind", current.getDouble("wind_speed_10m").roundToInt()),
            )
            .put("days", days)
    }

    /** A lookup step that failed; its [message] is the tool's `{"error": …}`. */
    private class LookupFailed(override val message: String, cause: Throwable? = null) : Exception(message, cause)

    /** The units the forecast is asked for ([temperatureParam], [windParam]) and named in for the model. */
    private enum class Units(
        val temperatureParam: String,
        val windParam: String,
        val temperatureWords: String,
        val windWords: String,
    ) {
        IMPERIAL("fahrenheit", "mph", "fahrenheit", "mph"),
        METRIC("celsius", "kmh", "celsius", "km/h"),
        ;

        companion object {
            // Where everyday temperatures are in °F: the US and its territories, and the few others that do.
            private val FAHRENHEIT_COUNTRIES =
                setOf("US", "PR", "GU", "VI", "AS", "MP", "UM", "LR", "BS", "BZ", "KY", "PW", "FM", "MH")

            fun of(locale: Locale) = if (locale.country in FAHRENHEIT_COUNTRIES) IMPERIAL else METRIC
        }
    }

    /** What looking a place up by name found. */
    sealed interface PlaceSearch {
        data class Found(val place: Place) : PlaceSearch

        data object NotFound : PlaceSearch

        /** The lookup couldn't be made (offline, timed out, a provider error); [reason] is for the log. */
        data class Failed(val reason: String) : PlaceSearch
    }

    /** A place the forecast is for: its spoken [name] and where it is. */
    data class Place(val name: String, val coordinates: Coordinates)

    companion object {
        /** One deadline across the place lookup and the forecast. Provisional (mirrors the external tool bound). */
        const val DEADLINE_MS = 5_000L

        /** The forecast goes at most a week ahead. */
        const val MAX_DAYS = 7

        private const val UNAVAILABLE = "weather unavailable"
        private const val TIMED_OUT = "weather timed out"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

        private fun placeName(vararg parts: String?) =
            parts.mapNotNull { it?.trim()?.ifEmpty { null } }.distinct().joinToString(", ")
    }
}
