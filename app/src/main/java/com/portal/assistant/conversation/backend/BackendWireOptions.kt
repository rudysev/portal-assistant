package com.portal.assistant.conversation.backend

/**
 * Wire/transport knobs for the Gemini Live backend. [GeminiBackend][com.portal.assistant.gemini.GeminiBackend] reads this
 * slice of [BackendConfig]; other factories ignore it. A null [endpointUrl] connects to
 * [GeminiBackend.DEFAULT_ENDPOINT][com.portal.assistant.gemini.GeminiBackend.DEFAULT_ENDPOINT].
 */
data class GeminiWireOptions(
    val endpointUrl: String? = null,
)

/**
 * Wire/transport knobs for the local voice host. [LocalBackend.Factory][com.portal.assistant.local.LocalBackend.Factory]
 * reads this slice of [BackendConfig]; other factories ignore it.
 */
data class LocalWireOptions(
    val tls: LocalTlsMode = LocalTlsMode.TRUST_SELF_SIGNED,
)

/** How the local backend validates the host's TLS certificate. */
enum class LocalTlsMode {
    /** Encrypt the wire but trust the host's auto-generated self-signed cert (shipping default). */
    TRUST_SELF_SIGNED,

    /** Use the device trust store — for a LAN host with a proper CA-issued cert (future). */
    SYSTEM,
}
