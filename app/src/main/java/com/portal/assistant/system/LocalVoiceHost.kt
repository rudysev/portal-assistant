package com.portal.assistant.system

/**
 * The single entry point for local voice host addresses — Settings ([AppPrefs]), DNS pre-warm
 * ([Backends.warmDns]), and the wire client all go through here.
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
        if (trimmed.isEmpty()) return ParseResult.Invalid

        val (host, port) = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> splitHostPort(trimmed.drop(6))

            trimmed.contains("://") -> return ParseResult.Invalid

            // ws://, http://, …

            else -> splitHostPort(trimmed)
        } ?: return ParseResult.Invalid

        if (!isLanHost(host)) return ParseResult.Invalid
        return ParseResult.Ok("wss://$host:$port")
    }

    /** Split `host:port` (no scheme). Rejects paths, query strings, and missing/invalid ports. */
    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        if ('/' in hostPort || '?' in hostPort || '#' in hostPort) return null
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return null
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535 || host.isBlank()) return null
        return host to port
    }

    private fun isLanHost(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost") return true
        if (lower.endsWith(".local")) return isValidHostname(lower)

        val octets = host.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            return isPrivateIpv4(octets.map { it.toInt() })
        }

        // Short mDNS name (no dots) — e.g. `raspberrypi:8080`.
        if ('.' !in host) return isValidSimpleHostname(host)
        return false
    }

    private fun isPrivateIpv4(o: List<Int>): Boolean = when {
        o[0] == 10 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 127 -> true
        o[0] == 169 && o[1] == 254 -> true
        else -> false
    }

    private fun isValidHostname(host: String): Boolean = host.all { it.isLetterOrDigit() || it == '-' || it == '.' }

    private fun isValidSimpleHostname(host: String): Boolean = host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '-' }
}
