package com.portal.assistant.conversation.tools.lookup

/** WMO weather interpretation codes (as Open-Meteo returns them) in words the model can say. */
object WeatherCodes {

    fun words(code: Int): String = WORDS[code] ?: "unknown"

    private val WORDS = mapOf(
        0 to "clear sky",
        1 to "mainly clear",
        2 to "partly cloudy",
        3 to "overcast",
        45 to "fog",
        48 to "freezing fog",
        51 to "light drizzle",
        53 to "drizzle",
        55 to "heavy drizzle",
        56 to "light freezing drizzle",
        57 to "heavy freezing drizzle",
        61 to "light rain",
        63 to "rain",
        65 to "heavy rain",
        66 to "light freezing rain",
        67 to "heavy freezing rain",
        71 to "light snow",
        73 to "snow",
        75 to "heavy snow",
        77 to "snow grains",
        80 to "light rain showers",
        81 to "rain showers",
        82 to "violent rain showers",
        85 to "light snow showers",
        86 to "heavy snow showers",
        95 to "thunderstorm",
        96 to "thunderstorm with light hail",
        99 to "thunderstorm with heavy hail",
    )
}
