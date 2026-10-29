package com.portal.assistant.conversation.tools.lookup

import android.os.SystemClock
import java.util.Locale

/**
 * [Weather] as it runs on the Portal: over [OkHttpLookupHttp], in the default locale, on the monotonic clock, with
 * [here] read on each call. The one way it's built: for the tool (`ToolRegistry`) and for Settings matching a
 * location override (`LocationProvider.saveOverride`).
 */
fun Weather.Companion.onDevice(here: () -> Here): Weather = Weather(
    OkHttpLookupHttp(),
    here = here,
    locale = { Locale.getDefault() },
    nowMs = { SystemClock.elapsedRealtime() },
)
