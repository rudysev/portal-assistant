package com.portal.assistant.conversation.tools.lookup

/**
 * The one port a Lookup reaches the network through: run one HTTP request and return its body or why there's none.
 * Blocking, called on the tool-executor thread. The Android adapter is [OkHttpLookupHttp]; tests use a fake that
 * answers with recorded provider responses.
 */
fun interface LookupHttp {
    fun execute(request: LookupRequest): LookupResponse
}

/**
 * One HTTP request: a GET of [url] with [query] when [jsonBody] is null, else a POST of [jsonBody] as
 * `application/json`. [headers] go on as is (e.g. bearer auth). [timeoutMs] bounds the whole call, connect to body.
 */
data class LookupRequest(
    val url: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val jsonBody: String? = null,
    val timeoutMs: Long,
)

/** What one [LookupRequest] came back with. */
sealed interface LookupResponse {
    /** A 2xx response's body. */
    data class Body(val text: String) : LookupResponse

    /** A non-2xx response: its [code] and body (a provider's error JSON, or empty). */
    data class Status(val code: Int, val body: String) : LookupResponse

    /** The call ran past its [LookupRequest.timeoutMs]. */
    data object TimedOut : LookupResponse

    /** No response: offline, DNS, TLS, a dropped connection. [reason] is for the log. */
    data class Failure(val reason: String) : LookupResponse
}
