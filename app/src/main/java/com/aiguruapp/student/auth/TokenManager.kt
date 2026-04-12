package com.aiguruapp.student.auth

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth

/**
 * Fetches and caches Firebase ID tokens for server-side authentication.
 *
 * Every request to the backend must include an `Authorization: Bearer <id-token>` header.
 * Firebase ID tokens are JWTs signed by Google, verified server-side with no shared secrets.
 * They expire after 1 hour — this manager proactively refreshes when ≥55 minutes old.
 *
 * ⚠  All methods are BLOCKING — call from a background thread (Dispatchers.IO) only.
 *    Never call from the main thread or inside a suspend function without a dispatcher.
 */
object TokenManager {

    private const val TAG = "TokenManager"

    /** Refresh the cached token 5 minutes before the 1-hour Firebase expiry. */
    private const val REFRESH_THRESHOLD_MS = 55L * 60 * 1000   // 55 min

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenFetchedAtMs: Long = 0L

    /**
     * Returns the Firebase ID token for the currently signed-in user.
     * Re-fetches from Firebase when the cache is stale or [forceRefresh] is true.
     *
     * @param forceRefresh Force a new token from Firebase even if the cache is fresh.
     *                     Use this after receiving an HTTP 401 from the server.
     * @return The ID token string, or null if no user is currently signed in.
     */
    fun getToken(forceRefresh: Boolean = false): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Log.w(TAG, "getToken: no Firebase user signed in")
            return null
        }

        val ageMs = System.currentTimeMillis() - tokenFetchedAtMs
        val isStale = cachedToken == null || ageMs >= REFRESH_THRESHOLD_MS

        return try {
            val result = Tasks.await(user.getIdToken(forceRefresh || isStale))
            val token = result?.token
            if (!token.isNullOrBlank()) {
                cachedToken = token
                tokenFetchedAtMs = System.currentTimeMillis()
                Log.d(TAG, "getToken: fetched new token (forceRefresh=$forceRefresh)")
            }
            token
        } catch (e: Exception) {
            Log.w(TAG, "getToken: failed to fetch token — ${e.message}")
            // Return the cached token as a degraded fallback; the server will return 401 if expired.
            cachedToken
        }
    }

    /**
     * Builds the HTTP Authorization header value ready to be passed to OkHttp.
     * Returns null when no user is signed in.
     */
    fun buildAuthHeader(forceRefresh: Boolean = false): String? {
        val token = getToken(forceRefresh) ?: return null
        return "Bearer $token"
    }

    /** Clear the in-memory cache, e.g. on sign-out. */
    fun clearCache() {
        cachedToken = null
        tokenFetchedAtMs = 0L
    }
}
