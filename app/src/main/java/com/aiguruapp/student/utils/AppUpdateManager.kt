package com.aiguruapp.student.utils

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aiguruapp.student.models.AppUpdateConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/**
 * Centralized update-check logic against Firestore `updates/app_config`.
 *
 * Flow:
 *  1. Fetch document with Source.DEFAULT (cache-first, falls back to server).
 *  2. A 5-second safety timeout guarantees we never block the user indefinitely
 *     when the device is offline or Firestore is slow.
 *  3. Caller receives a typed [UpdateResult] and decides how to display UI.
 *
 * Optional-update cooldown: once a user dismisses the optional dialog we
 * suppress it for [OPTIONAL_PROMPT_COOLDOWN_MS] so it doesn't nag every launch.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // ── Firestore ──────────────────────────────────────────────────────────
    private const val COLLECTION = "updates"
    private const val DOC_ID = "app_config"

    // ── Timing ────────────────────────────────────────────────────────────
    /** Max milliseconds to wait for Firestore before proceeding unblocked. */
    private const val TIMEOUT_MS = 5_000L

    /** Suppress optional-update dialog for 24 h after user dismisses it. */
    private const val OPTIONAL_PROMPT_COOLDOWN_MS = 24L * 60 * 60 * 1_000

    // ── SharedPreferences key ─────────────────────────────────────────────
    const val PREFS_NAME = "app_update_prefs"
    private const val KEY_LAST_OPTIONAL_PROMPT = "last_optional_update_prompt"

    // ── Result types ──────────────────────────────────────────────────────
    sealed class UpdateResult {
        /** App is below the minimum required version — user MUST update. */
        data class ForceUpdate(val config: AppUpdateConfig) : UpdateResult()

        /** Newer version exists but is not mandatory — user can skip. */
        data class OptionalUpdate(val config: AppUpdateConfig) : UpdateResult()

        /** Admin set is_maintenance=true or is_active=false. */
        data class Maintenance(val config: AppUpdateConfig) : UpdateResult()

        /** App is up-to-date (or optional prompt was shown recently). */
        object UpToDate : UpdateResult()

        /** Network/Firestore error — proceed as if no update is needed. */
        object NetworkError : UpdateResult()
    }

    /**
     * Performs the asynchronous update check.
     *
     * @param currentVersionCode  [BuildConfig.VERSION_CODE] of the running build.
     * @param prefs               SharedPreferences used for optional-prompt cooldown.
     * @param onResult            Called exactly once on the **main thread**.
     */
    fun checkForUpdates(
        currentVersionCode: Int,
        prefs: SharedPreferences,
        onResult: (UpdateResult) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        var settled = false

        // Guarantee we call onResult exactly once even if Firestore hangs.
        fun settle(result: UpdateResult) {
            if (!settled) {
                settled = true
                mainHandler.removeCallbacksAndMessages(null)
                mainHandler.post { onResult(result) }
            }
        }

        // Safety timeout — never block the user longer than TIMEOUT_MS.
        mainHandler.postDelayed({
            Log.w(TAG, "Update check timed out after ${TIMEOUT_MS}ms — proceeding.")
            settle(UpdateResult.NetworkError)
        }, TIMEOUT_MS)

        FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(DOC_ID)
            .get(Source.DEFAULT) // cache-first, server fallback
            .addOnSuccessListener { snapshot ->
                val config = snapshot.toAppUpdateConfig()
                Log.d(TAG, "Update check: minVer=${config.minVersionCode}, " +
                        "latestVer=${config.latestVersionCode}, " +
                        "active=${config.isActive}, maintenance=${config.isMaintenance}")

                val result = when {
                    // 1. Global kill-switch or scheduled maintenance
                    !config.isActive || config.isMaintenance ->
                        UpdateResult.Maintenance(config)

                    // 2. Hard block — version is below minimum required
                    currentVersionCode < config.minVersionCode ->
                        UpdateResult.ForceUpdate(config)

                    // 3. Soft nudge — newer version exists
                    currentVersionCode < config.latestVersionCode -> {
                        val lastShown = prefs.getLong(KEY_LAST_OPTIONAL_PROMPT, 0L)
                        val cooldownExpired =
                            System.currentTimeMillis() - lastShown > OPTIONAL_PROMPT_COOLDOWN_MS
                        if (cooldownExpired) UpdateResult.OptionalUpdate(config)
                        else UpdateResult.UpToDate
                    }

                    else -> UpdateResult.UpToDate
                }
                settle(result)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Update check failed: ${e.message}")
                settle(UpdateResult.NetworkError)
            }
    }

    /**
     * Call this when the user dismisses an optional-update dialog so we
     * suppress it for the next [OPTIONAL_PROMPT_COOLDOWN_MS] milliseconds.
     */
    fun markOptionalUpdateShown(prefs: SharedPreferences) {
        prefs.edit().putLong(KEY_LAST_OPTIONAL_PROMPT, System.currentTimeMillis()).apply()
    }

    // ── Firestore → model mapping ─────────────────────────────────────────
    private fun com.google.firebase.firestore.DocumentSnapshot.toAppUpdateConfig(): AppUpdateConfig {
        if (!exists()) return AppUpdateConfig()
        return AppUpdateConfig(
            minVersionCode    = getLong("min_version_code")    ?: 0L,
            latestVersionCode = getLong("latest_version_code") ?: 0L,
            latestVersionName = getString("latest_version_name") ?: "",
            updateUrl         = getString("update_url")
                ?: "https://play.google.com/store/apps/details?id=com.aiguruapp.student",
            updateMessage     = getString("update_message")
                ?: "A new version of AI Guru is available.",
            releaseNotes      = getString("release_notes")      ?: "",
            isMaintenance     = getBoolean("is_maintenance")    ?: false,
            maintenanceMessage = getString("maintenance_message")
                ?: "We're making improvements. Please check back soon.",
            isActive          = getBoolean("is_active")         ?: true,
            supportContact    = getString("support_contact")    ?: ""
        )
    }
}
