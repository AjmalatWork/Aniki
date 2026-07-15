package com.aniki.anikiai.auth

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <idToken>` to every request when signed in; guest sessions
 * (no current user) pass through unmodified — the server rejects those on /sync and
 * SyncManager treats that as SyncResult.Unauthenticated, not an error. On a 401, refreshes the
 * token once and retries, since the cached token can expire between issuance and use.
 */
class FirebaseAuthInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = currentIdToken(forceRefresh = false)
        val authedRequest = if (token != null) {
            request.newBuilder().addHeader("Authorization", "Bearer $token").build()
        } else {
            request
        }

        val response = chain.proceed(authedRequest)
        if (response.code != 401 || token == null) return response

        response.close()
        val refreshed = currentIdToken(forceRefresh = true) ?: return response
        val retryRequest = request.newBuilder().addHeader("Authorization", "Bearer $refreshed").build()
        return chain.proceed(retryRequest)
    }

    private fun currentIdToken(forceRefresh: Boolean): String? {
        return try {
            // FirebaseAuth.getInstance() itself throws until google-services.json exists and
            // the google-services plugin is applied — this runs on every network call
            // (including /enrich, /health), so it must never crash the request.
            val user = FirebaseAuth.getInstance().currentUser ?: return null
            Tasks.await(user.getIdToken(forceRefresh), 10, TimeUnit.SECONDS)?.token
        } catch (e: Exception) {
            null
        }
    }
}
