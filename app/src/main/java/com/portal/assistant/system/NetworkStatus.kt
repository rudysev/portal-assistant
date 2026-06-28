package com.portal.assistant.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * A coarse "is there internet right now?" check, used only to phrase a connection failure: an offline
 * device gets "check your Wi-Fi" while an online one that still couldn't reach the service gets "try again".
 * Deliberately optimistic — if we can't tell (no ConnectivityManager / SecurityException), we assume online
 * so we never wrongly blame the network.
 */
object NetworkStatus {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = runCatching {
            val network = cm.activeNetwork ?: return false // a null active network is a definitive "offline"
            cm.getNetworkCapabilities(network)
        }.getOrNull() ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
