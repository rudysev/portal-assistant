package com.portal.assistant.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for LAN voice-host parsing — the contract Settings and [AppPrefs] rely on. */
class LocalVoiceHostTest {

    @Test fun hostPortNormalizesToWss() {
        val r = LocalVoiceHost.parse("192.168.1.5:8080")
        assertTrue(r is LocalVoiceHost.ParseResult.Ok)
        assertEquals("wss://192.168.1.5:8080", (r as LocalVoiceHost.ParseResult.Ok).wssUrl)
    }

    @Test fun explicitWssAcceptedAndCanonicalized() {
        val r = LocalVoiceHost.parse("WSS://10.0.0.2:8765")
        assertTrue(r is LocalVoiceHost.ParseResult.Ok)
        assertEquals("wss://10.0.0.2:8765", (r as LocalVoiceHost.ParseResult.Ok).wssUrl)
    }

    @Test fun loopbackLinkLocalAndMdnsNamesAllowed() {
        assertEquals("wss://127.0.0.1:8080", ok("127.0.0.1:8080"))
        assertEquals("wss://169.254.10.1:443", ok("169.254.10.1:443"))
        assertEquals("wss://localhost:8765", ok("localhost:8765"))
        assertEquals("wss://my-pi.local:8080", ok("my-pi.local:8080"))
        assertEquals("wss://raspberrypi:8080", ok("raspberrypi:8080"))
    }

    @Test fun rejectsPublicIpAndInsecureSchemes() {
        assertInvalid("8.8.8.8:8080")
        assertInvalid("1.2.3.4:443")
        assertInvalid("ws://192.168.1.5:8080")
        assertInvalid("http://192.168.1.5:8080")
        assertInvalid("https://192.168.1.5:8080")
    }

    @Test fun rejectsMalformedInput() {
        assertInvalid("")
        assertInvalid("   ")
        assertInvalid("192.168.1.5") // no port
        assertInvalid("192.168.1.5:0")
        assertInvalid("192.168.1.5:99999")
        assertInvalid("wss://192.168.1.5:8080/extra/path")
        assertInvalid("not-a-host")
        assertInvalid("evil.com:8080") // dotted public hostname
    }

    @Test fun dnsNameExtractsHostFromBareAddressAndCanonicalWss() {
        assertEquals("192.168.1.5", LocalVoiceHost.dnsName("192.168.1.5:8080"))
        assertEquals("my-pi.local", LocalVoiceHost.dnsName("wss://my-pi.local:8443"))
        assertEquals("raspberrypi", LocalVoiceHost.dnsName("  raspberrypi:8080  "))
    }

    @Test fun dnsNameReturnsNullForBlankOrInvalid() {
        assertNull(LocalVoiceHost.dnsName("   "))
        assertNull(LocalVoiceHost.dnsName("not a url!!!"))
        assertNull(LocalVoiceHost.dnsName("8.8.8.8:8080"))
        assertNull(LocalVoiceHost.dnsName("ws://192.168.1.5:8080"))
    }

    @Test fun okResultExposesHost() {
        val r = LocalVoiceHost.parse("10.0.0.2:8765") as LocalVoiceHost.ParseResult.Ok
        assertEquals("wss://10.0.0.2:8765", r.wssUrl)
        assertEquals("10.0.0.2", r.host)
    }

    private fun ok(input: String) = (LocalVoiceHost.parse(input) as LocalVoiceHost.ParseResult.Ok).wssUrl

    private fun assertInvalid(input: String) {
        assertTrue(LocalVoiceHost.parse(input) is LocalVoiceHost.ParseResult.Invalid)
    }
}
