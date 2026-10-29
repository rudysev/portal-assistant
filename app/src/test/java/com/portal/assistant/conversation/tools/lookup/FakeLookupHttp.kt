package com.portal.assistant.conversation.tools.lookup

/**
 * In-memory [LookupHttp]: each request is answered by the first route whose URL prefix matches, and recorded in
 * [requests]. Routing a prefix again replaces its earlier route, so a test can loop over
 * bodies. A route may also spend time on the fake clock ([elapse]), as a slow provider would.
 */
class FakeLookupHttp {
    var nowMs = 0L
        private set

    val requests = mutableListOf<LookupRequest>()

    private val routes = mutableListOf<Route>()

    private class Route(val urlPrefix: String, val response: LookupResponse, val elapseMs: Long)

    fun on(urlPrefix: String, response: LookupResponse, elapseMs: Long = 0) {
        routes.removeAll { it.urlPrefix == urlPrefix }
        routes += Route(urlPrefix, response, elapseMs)
    }

    fun onBody(urlPrefix: String, body: String, elapseMs: Long = 0) = on(urlPrefix, LookupResponse.Body(body), elapseMs)

    val port = LookupHttp { request ->
        requests += request
        val route = routes.firstOrNull { request.url.startsWith(it.urlPrefix) }
            ?: return@LookupHttp LookupResponse.Failure("no route for ${request.url}")
        nowMs += route.elapseMs
        route.response
    }

    val clock: () -> Long = { nowMs }

    fun requestsTo(urlPrefix: String) = requests.filter { it.url.startsWith(urlPrefix) }
}
