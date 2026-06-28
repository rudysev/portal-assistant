package com.portal.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.portal.commons.DebugLog
import java.io.File

/**
 * Brings the resident [AssistantService] up at boot so the **first** "hey jarvis" after a reboot hits a
 * warm process instead of paying a cold spawn (see [AssistantService] for the latency rationale).
 *
 * On the Portal (Android 9 / API 28) a background-started foreground service from a boot broadcast is
 * allowed. Same "stopped state" caveat as portal-wake: Android won't deliver BOOT_COMPLETED until a
 * component has run once since install, and this app's only launchable component is the Activity — so
 * `setup.sh` kicks [ACTION_STANDBY] once after install; from then on boot start-up is automatic.
 */
class AssistantBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON, AssistantService.ACTION_STANDBY -> {
                if (DebugLog.file == null) DebugLog.file = File(context.getExternalFilesDir(null), "debug.txt")
                DebugLog.log("AssistantBootReceiver: ${intent.action} → standby")
                AssistantService.standby(context)
            }
        }
    }

    private companion object {
        // Some OEMs send this instead of (or alongside) BOOT_COMPLETED on a "quick boot".
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
