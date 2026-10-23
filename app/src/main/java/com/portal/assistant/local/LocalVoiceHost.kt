package com.portal.assistant.local

/**
 * The single entry point for local voice host addresses — Settings
 * ([AppPrefs][com.portal.assistant.system.AppPrefs]) canonicalizes through here before storing, so the stored
 * address the transport dials is already valid; DNS pre-warm uses [dnsName].
 *
 * Accepts **`host:port`** (the Settings-friendly form) or an explicit **`wss://host:port`**, always
 * normalizing to a canonical `wss://` URL. The LAN transport uses [com.portal.assistant.util.Http.lanVoice]
 * (encrypted WebSocket with a self-signed cert), so `ws://` and plain `http(s)://` are rejected, as are
 * hosts outside private/link-local address space or typical LAN naming (`.local`, short mDNS names).
 */
object LocalVoiceHost {

    sealed interface ParseResult {
        /** Canonical `wss://host:port` ready for the WebSocket client. */
        data class Ok(val wssUrl: String) : ParseResult {
            /** DNS hostname extracted from [wssUrl] (e.g. for connection pre-warm). */
            val host: String = wssUrl.removePrefix("wss://").substringBeforeLast(':')
        }

        /** Unparseable, insecure scheme, missing port, or host not on the LAN. */
        data object Invalid : ParseResult
    }

    /** DNS hostname for [input], or null when [parse] would reject it. */
    fun dnsName(input: String): String? = (parse(input) as? ParseResult.Ok)?.host

    /** Parse [input]; blank/whitespace-only is [ParseResult.Invalid]. */
    fun parse(input: String): ParseResult {
        val trimmed = input.trim()
        val hostPort = when {
            trimmed.startsWith(WSS, ignoreCase = true) -> splitHostPort(trimmed.drop(WSS.length))

            trimmed.contains("://") -> null

            // ws://, http://, …
            else -> splitHostPort(trimmed) // blank input has no port, so it is rejected here too
        }
        return if (hostPort != null && isLanHost(hostPort.first)) {
            ParseResult.Ok("$WSS${hostPort.first}:${hostPort.second}")
        } else {
            ParseResult.Invalid
        }
    }

    /** Split `host:port` (no scheme). Rejects paths, query strings, and missing/invalid ports. */
    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        val colon = hostPort.lastIndexOf(':')
        val host = hostPort.substring(0, colon.coerceAtLeast(0))
        val port = if (colon > 0) hostPort.substring(colon + 1).toIntOrNull() else null
        val hasPath = '/' in hostPort || '?' in hostPort || '#' in hostPort
        val validPort = port != null && port in 1..MAX_PORT
        return if (!hasPath && validPort && host.isNotBlank()) host to port else null
    }

    private fun isLanHost(host: String): Boolean {
        val lower = host.lowercase()
        val octets = host.split('.')
        return when {
            lower == "localhost" -> true

            lower.endsWith(".local") -> isValidHostname(lower)

            octets.size == IPV4_OCTETS && octets.all { it.toIntOrNull() in 0..MAX_OCTET } -> isPrivateIpv4(octets.map { it.toInt() })

            // Short mDNS name (no dots) — e.g. `raspberrypi:8080`.
            '.' !in host -> isValidSimpleHostname(host)

            else -> false
        }
    }

    // The octets are the private, loopback and link-local ranges' own literals (10/8, 172.16/12, 192.168/16,
    // 127/8, 169.254/16); naming each would only restate them.
    @Suppress("MagicNumber")
    private fun isPrivateIpv4(o: List<Int>): Boolean = when {
        o[0] == 10 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 127 -> true
        o[0] == 169 && o[1] == 254 -> true
        else -> false
    }

    private const val WSS = "wss://"
    private const val MAX_PORT = 65_535
    private const val IPV4_OCTETS = 4
    private const val MAX_OCTET = 255

    private fun isValidHostname(host: String): Boolean = host.all { it.isLetterOrDigit() || it == '-' || it == '.' }

    private fun isValidSimpleHostname(host: String): Boolean = host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '-' }
}
