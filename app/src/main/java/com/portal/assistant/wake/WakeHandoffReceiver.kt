package com.portal.assistant.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.portal.assistant.service.AssistantService
import com.portal.commons.DebugLog

/**
 * Entry point for the portal-wake hand-off. portal-wake detects "hey jarvis", **releases the mic**, and
 * sends this app an explicit [ACTION_WAKE] broadcast; we start [AssistantService] to take the freed mic.
 *
 * This app advertises which wake words it handles via a `<meta-data>` on this receiver in the manifest
 * (`com.portal.wake.keywords` = `jarvis;hey jarvis;jarvis;0.6`), which portal-wake discovers at runtime.
 * The action/extra strings are portal-wake's stable public contract (`WakeContract`); a plugin does not
 * depend on portal-wake — the literal strings *are* the contract, so we mirror the two we read here
 * (the manifest meta-data must stay a literal too — XML can't reference constants).
 */
class WakeHandoffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WAKE) return
        val id = intent.getStringExtra(EXTRA_WAKE_ID) ?: "?"
        DebugLog.log("WAKE received (id=$id) → starting AssistantService")
        AssistantService.start(context, source = "wake:$id")
    }

    private companion object {
        // portal-wake's public wake contract — frozen wire strings (mirror of WakeContract). A plugin
        // needs only these literals, not a dependency on portal-wake.
        const val ACTION_WAKE = "com.portal.wake.action.WAKE"
        const val EXTRA_WAKE_ID = "com.portal.wake.extra.ID"
    }
}
