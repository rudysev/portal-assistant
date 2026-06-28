package com.portal.assistant.gemini

import com.portal.assistant.util.Http
import com.portal.commons.DebugLog
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * One-shot validation of a user-supplied Gemini API key (BYOD — Settings → API key). The Live API is a
 * WebSocket, so a key is otherwise only proven when a conversation connects; this lets Settings confirm a
 * pasted key *before* storing it, so a non-technical user gets instant feedback on a typo or a disabled key.
 *
 * A cheap, free GET to the Generative Language REST endpoint (same host as the Live API): listing models
 * needs no request body and no token spend. The status-code → [Result] mapping is the pure, unit-tested
 * [classify]; [validate] is the thin I/O around it (mirrors the codebase's pure-core + thin-shell pattern).
 */
object GeminiKeyCheck {

    private const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    sealed interface Result {
        /** The key authenticated — the API accepted it. */
        data object Valid : Result

        /** The server rejected the key (bad, revoked, or the API isn't enabled for it). */
        data object Invalid : Result

        /** Couldn't reach the server (offline / transient) — don't treat as a bad key. */
        data object NetworkError : Result
    }

    /** Map an HTTP status to a verdict. 2xx = valid; auth/bad-request = invalid; anything else (5xx,
     *  unexpected) is treated as a transient error so a flaky server never discards a good key. Pure. */
    fun classify(code: Int): Result = when {
        code in 200..299 -> Result.Valid
        code == 400 || code == 401 || code == 403 -> Result.Invalid
        else -> Result.NetworkError
    }

    /** Blocking — call off the main thread. Validates [key] against the models endpoint. A blank key is
     *  [Result.Invalid] without a network round-trip. */
    fun validate(key: String): Result {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return Result.Invalid
        // addQueryParameter percent-encodes the key, so a stray reserved char can't corrupt the request
        // (and misclassify a good key as Invalid).
        val url = MODELS_URL.toHttpUrl().newBuilder().addQueryParameter("key", trimmed).build()
        val request = Request.Builder().url(url).build()
        return try {
            Http.shared.newCall(request).execute().use { resp ->
                DebugLog.log("api key check: HTTP ${resp.code}")
                classify(resp.code)
            }
        } catch (e: IOException) {
            DebugLog.log("api key check failed (network): ${e.javaClass.simpleName}")
            Result.NetworkError
        }
    }
}
