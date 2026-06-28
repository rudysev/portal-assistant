package com.portal.assistant.util

import okhttp3.OkHttpClient

/**
 * One app-wide [OkHttpClient]. OkHttp is designed to be used as a singleton — a single instance owns
 * the connection pool and the dispatcher's thread pool, and every call reuses them. Features that need
 * different timeouts derive a variant with [OkHttpClient.newBuilder] (e.g. the Live WebSocket's
 * infinite read timeout), which **keeps sharing** those same pools.
 */
object Http {
    val shared: OkHttpClient = OkHttpClient()
}
