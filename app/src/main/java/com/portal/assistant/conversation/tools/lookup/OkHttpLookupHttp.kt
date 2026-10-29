package com.portal.assistant.conversation.tools.lookup

import android.os.SystemClock
import com.portal.assistant.util.Http
import com.portal.commons.DebugLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * The Android [LookupHttp]: OkHttp on a client derived from [Http.shared] (sharing its pools), each call bounded by
 * its own [LookupRequest.timeoutMs] (OkHttp's call timeout: connect, write, read and body together). Logs each call's
 * outcome and time, for tuning the bounds on the Portal. Not unit-tested: checked on device.
 */
class OkHttpLookupHttp(base: OkHttpClient = Http.shared) : LookupHttp {

    private val client = base.newBuilder().callTimeout(CEILING_MS, TimeUnit.MILLISECONDS).build()

    override fun execute(request: LookupRequest): LookupResponse {
        val url = request.url.toHttpUrlOrNull()?.newBuilder()?.apply {
            request.query.forEach { (key, value) -> addQueryParameter(key, value) }
        }?.build() ?: return LookupResponse.Failure("bad url: ${request.url}")
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        request.jsonBody?.let { builder.post(it.toRequestBody(JSON)) }
        val call = client.newCall(builder.build())
        call.timeout().timeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        val started = SystemClock.elapsedRealtime()
        val response = try {
            call.execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) LookupResponse.Body(body) else LookupResponse.Status(resp.code, body)
            }
        } catch (e: InterruptedIOException) {
            DebugLog.log("lookup ${url.host} timed out: ${e.message}")
            LookupResponse.TimedOut
        } catch (e: IOException) {
            LookupResponse.Failure(e.message ?: e.javaClass.simpleName)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        DebugLog.log("lookup ${url.host}${url.encodedPath} → ${outcome(response)} in $elapsed ms")
        return response
    }

    private fun outcome(response: LookupResponse): String = when (response) {
        is LookupResponse.Body -> "ok"
        is LookupResponse.Status -> "HTTP ${response.code}"
        LookupResponse.TimedOut -> "timed out"
        is LookupResponse.Failure -> "failed (${response.reason})"
    }

    private companion object {
        // Backstop only: every call sets its own, shorter timeout.
        const val CEILING_MS = 30_000L
        const val USER_AGENT = "portal-assistant"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
