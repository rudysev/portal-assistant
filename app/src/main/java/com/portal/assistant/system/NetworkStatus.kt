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
        return runCatching {
            // A null active network is a definitive "offline"; unknown capabilities count as online.
            cm.activeNetwork?.let { network ->
                cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
            } ?: false
        }.getOrDefault(true)
    }
}
