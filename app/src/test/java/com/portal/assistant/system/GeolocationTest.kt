package com.portal.assistant.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for IP-geo parsing (both provider shapes). */
class GeolocationTest {

    @Test fun parsesIpwhoIsShape() {
        val loc = Geolocation.parse(
            """{"success":true,"city":"Seattle","region":"Washington","country":"United States",
               "latitude":47.61,"longitude":-122.33}""",
        )!!
        assertEquals("Seattle", loc.city)
        assertEquals("Washington", loc.region)
        assertEquals("United States", loc.country)
        assertEquals(47.61, loc.lat, 1e-9)
        assertEquals(-122.33, loc.lon, 1e-9)
    }

    @Test fun parsesIpapiCoShape() {
        val loc = Geolocation.parse(
            """{"city":"Paris","region":"Île-de-France","country_name":"France",
               "latitude":48.85,"longitude":2.35}""",
        )!!
        assertEquals("Paris", loc.city)
        assertEquals("France", loc.country)
    }

    @Test fun rejectsErrorBodies() {
        assertNull(Geolocation.parse("""{"error":true,"reason":"RateLimited"}""")) // ipapi.co
        assertNull(Geolocation.parse("""{"success":false,"message":"quota"}""")) // ipwho.is
    }

    @Test fun rejectsMissingOrZeroFix() {
        assertNull(Geolocation.parse("""{"city":"","latitude":47.0,"longitude":-122.0}"""))
        assertNull(Geolocation.parse("""{"city":"X","latitude":0.0,"longitude":0.0}"""))
        assertNull(Geolocation.parse("not json"))
    }
}
