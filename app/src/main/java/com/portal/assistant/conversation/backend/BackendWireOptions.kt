package com.portal.assistant.conversation.backend

/**
 * Wire/transport knobs for the Gemini Live client. [GeminiBackend] reads this slice of [BackendConfig];
 * other factories ignore it. Endpoint defaults live in [LiveClient][com.portal.assistant.gemini.LiveClient]
 * unless [endpointUrl] is set here in the future.
 */
data class GeminiWireOptions(
    val endpointUrl: String? = null,
)

/**
 * Wire/transport knobs for the local voice host. [LocalBackend][com.portal.assistant.conversation.backend.local.LocalBackend]
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
