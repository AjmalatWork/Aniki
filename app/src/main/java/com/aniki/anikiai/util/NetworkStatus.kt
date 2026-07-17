package com.aniki.anikiai.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** True if the device currently has a network with internet access -- doesn't guarantee the
 *  backend itself is reachable, just whether there's a live connection to attempt one. */
fun isOnline(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
