package com.portal.assistant.util

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * One app-wide [OkHttpClient]. OkHttp is designed to be used as a singleton — a single instance owns
 * the connection pool and the dispatcher's thread pool, and every call reuses them. Features that need
 * different timeouts derive a variant with [OkHttpClient.newBuilder] (e.g. the Live WebSocket's
 * infinite read timeout), which **keeps sharing** those same pools.
 */
object Http {
    val shared: OkHttpClient = OkHttpClient()

    /**
     * OkHttp for the opt-in LAN voice backend ([wss://][wss]). The host auto-generates a self-signed TLS
     * cert on first boot — no install step — so we encrypt the wire but do **not** authenticate the server
     * (trust-all). Gemini keeps using [shared] with normal PKI verification.
     */
    val lanVoice: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        shared.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
