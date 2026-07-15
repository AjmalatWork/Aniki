package com.aniki.anikiai.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.aniki.anikiai.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

class AuthManager(private val context: Context) {

    // Lazy + defensive: FirebaseAuth.getInstance() throws until google-services.json exists and
    // the google-services plugin is applied. Until then, treat this as "signed out" everywhere
    // rather than crashing — guest mode must keep working regardless of Firebase setup status.
    private val firebaseAuth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }

    val currentUser: FirebaseUser? get() = firebaseAuth?.currentUser
    val isSignedIn: Boolean get() = currentUser != null

    suspend fun signInWithGoogle(): Result<FirebaseUser> {
        val auth = firebaseAuth
            ?: return Result.failure(IllegalStateException("Firebase isn't configured yet (missing google-services.json)"))
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(context.getString(R.string.default_web_client_id))
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential

            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
            }

            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            val authResult = auth.signInWithCredential(firebaseCredential).await()
            val user = authResult.user ?: return Result.failure(IllegalStateException("Sign-in returned no user"))
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        firebaseAuth?.signOut()
    }
}
