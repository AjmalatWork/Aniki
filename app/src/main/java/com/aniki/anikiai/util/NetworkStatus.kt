package com.aniki.anikiai.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** True if the device currently has a network with internet access -- doesn't guarantee the
 *  backend itself is reachable, just whether there's a live connection to attempt one. */
fun isOnline(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Live version of [isOnline] for the "offline, waiting to process" UI state (a PENDING item whose
 * enrichment job is parked on WorkManager's CONNECTED constraint) -- pushes an update whenever
 * connectivity changes so that state clears itself the instant the job is actually eligible to
 * resume, without the UI polling. Registers against every network (not just the active one) since
 * that's what actually determines whether NET_CAPABILITY_INTERNET is available; unregisters when
 * the collecting scope cancels.
 */
fun observeOnline(context: Context): Flow<Boolean> = callbackFlow {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (connectivityManager == null) {
        trySend(false)
        awaitClose {}
        return@callbackFlow
    }

    trySend(isOnline(context))

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(isOnline(context))
        }

        override fun onLost(network: Network) {
            trySend(isOnline(context))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(isOnline(context))
        }
    }

    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    connectivityManager.registerNetworkCallback(request, callback)

    awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
