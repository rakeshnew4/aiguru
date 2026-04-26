package com.aiguruapp.student

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.aiguruapp.student.BuildConfig
import com.aiguruapp.student.config.AppStartRepository
import com.aiguruapp.student.models.AppUpdateConfig
import com.aiguruapp.student.utils.AppUpdateBus
import com.aiguruapp.student.utils.AppUpdateManager
import com.aiguruapp.student.utils.AppUpdateManager.UpdateResult

/**
 * Entry-point activity (replaces HomeActivity as LAUNCHER).
 *
 * Responsibilities:
 *  1. Show branded splash for at least MIN_SPLASH_MS.
 *  2. Perform an async update/maintenance check via AppUpdateManager.
 *  3. Handle all result types:
 *       - Maintenance  → non-dismissible full-screen dialog (exit button only)
 *       - ForceUpdate  → non-dismissible "Update Now" dialog; re-shown on resume
 *       - OptionalUpdate → dismissible dialog; cooldown prevents daily nag
 *       - UpToDate / NetworkError → proceed straight to HomeActivity
 *  4. HomeActivity still owns auth-routing (Login vs Home), so SplashActivity
 *     stays simple and focused on update-gating only.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SplashActivity"
        /** Minimum time to show splash so the user sees the brand — fast entry at 700ms. */
        private const val MIN_SPLASH_MS = 700L
    }

    private lateinit var prefs: SharedPreferences

    /**
     * Holds the last ForceUpdate result so that when the user returns from
     * the Play Store without actually updating, onResume can re-present
     * the dialog instead of accidentally letting them in.
     */
    private var pendingForceUpdate: UpdateResult.ForceUpdate? = null

    /** Guards against launching HomeActivity more than once. */
    private var hasProceeded = false

    /** True once MIN_SPLASH_MS has elapsed — never act before the brand is shown. */
    private var minTimeElapsed = false

    /** Holds the update-check result once Firestore responds. */
    private var checkResultCache: UpdateResult? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_splash)

        prefs = getSharedPreferences(AppUpdateManager.PREFS_NAME, Context.MODE_PRIVATE)

        // Block back during a mandatory force-update.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pendingForceUpdate != null) return // block back during force update
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        // Bootstrap data fetch — runs in parallel, not on the critical path.
        AppStartRepository.fetchAll {
            Log.d(TAG, "Bootstrap data ready — plans=${AppStartRepository.plans.size}, " +
                    "offers=${AppStartRepository.offers.size}, " +
                    "notifications=${AppStartRepository.notifications.size}")
        }

        // Minimum brand-exposure timer.
        // When it fires we either handle an already-available result or proceed
        // straight to HomeActivity and let AppUpdateBus deliver the result there.
        Handler(Looper.getMainLooper()).postDelayed({
            minTimeElapsed = true
            tryAct()
        }, MIN_SPLASH_MS)

        // Update check runs fully in parallel — no blocking.
        AppUpdateManager.checkForUpdates(
            currentVersionCode = BuildConfig.VERSION_CODE,
            prefs = prefs,
            appContext = this  // ✓ Pass context for caching
        ) { result ->
            checkResultCache = result
            if (hasProceeded) {
                // Splash already dismissed — deliver result to HomeActivity via bus.
                AppUpdateBus.post(result)
            } else {
                tryAct()
            }
        }
    }

    /**
     * Acts only after [minTimeElapsed] is true.
     *
     * • Result available  → handle it here on splash (ForceUpdate / Maintenance will
     *   block; UpToDate / NetworkError will proceed normally).
     * • Result not yet in → proceed immediately; the result is delivered to
     *   HomeActivity via [AppUpdateBus] when it eventually arrives.
     */
    private fun tryAct() {
        if (!minTimeElapsed) return
        val result = checkResultCache
        if (result == null) {
            proceedToApp()          // check still running — HomeActivity handles via bus
        } else {
            handleResult(result)
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user returned from Play Store for a mandatory update,
        // re-validate their installed version code rather than trusting
        // the previous Firestore result blindly.
        val forceUpdate = pendingForceUpdate ?: return
        if (BuildConfig.VERSION_CODE < forceUpdate.config.minVersionCode) {
            // Still not updated — re-present the blocking dialog.
            showForceUpdateDialog(forceUpdate.config)
        } else {
            // User successfully updated; clear flag and proceed.
            pendingForceUpdate = null
            proceedToApp()
        }
    }

    // ── Result handling ───────────────────────────────────────────────────

    private fun handleResult(result: UpdateResult) {
        Log.d(TAG, "Update result: $result")
        when (result) {
            is UpdateResult.Maintenance     -> showMaintenanceDialog(result.config)
            is UpdateResult.ForceUpdate     -> {
                pendingForceUpdate = result
                showForceUpdateDialog(result.config)
            }
            is UpdateResult.OptionalUpdate  -> {
                AppUpdateManager.markOptionalUpdateShown(prefs)
                showOptionalUpdateDialog(result.config)
            }
            is UpdateResult.UpToDate,
            is UpdateResult.NetworkError    -> proceedToApp()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────

    /**
     * Non-dismissible. User MUST update or close the app.
     * Shown again on every onResume until the installed version meets
     * the minimum requirement.
     */
    private fun showForceUpdateDialog(config: AppUpdateConfig) {
        val versionLabel = config.latestVersionName
            .takeIf { it.isNotBlank() }?.let { " (v$it)" } ?: ""

        AlertDialog.Builder(this)
            .setTitle("Update Required")
            .setMessage(
                "${config.updateMessage}\n\n" +
                "You need to update AI Guru$versionLabel to continue." +
                releaseNotesSection(config)
            )
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ -> openPlayStore(config.updateUrl) }
            // "Exit" gives users a graceful out rather than leaving them stuck.
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .show()
    }

    /**
     * Dismissible. User can choose to update or skip.
     * The cooldown in AppUpdateManager prevents this from appearing every launch.
     */
    private fun showOptionalUpdateDialog(config: AppUpdateConfig) {
        val title = if (config.latestVersionName.isNotBlank())
            "Update Available — v${config.latestVersionName}"
        else
            "Update Available"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(config.updateMessage + releaseNotesSection(config))
            .setCancelable(true)
            .setPositiveButton("Update") { _, _ ->
                openPlayStore(config.updateUrl)
                // Proceed immediately so the user isn't blocked if they switch
                // back without updating from optional flow.
                proceedToApp()
            }
            .setNegativeButton("Later") { _, _ -> proceedToApp() }
            .setOnCancelListener { proceedToApp() }
            .show()
    }

    /**
     * Non-dismissible maintenance screen. Only option is to close the app.
     * Admin controls this via the `is_maintenance` or `is_active` flag.
     */
    private fun showMaintenanceDialog(config: AppUpdateConfig) {
        val messageBuilder = StringBuilder(config.maintenanceMessage)
        if (config.supportContact.isNotBlank()) {
            messageBuilder.append("\n\nFor assistance: ${config.supportContact}")
        }

        AlertDialog.Builder(this)
            .setTitle("Down for Maintenance")
            .setMessage(messageBuilder.toString())
            .setCancelable(false)
            .setPositiveButton("Close App") { _, _ -> finishAffinity() }
            .show()
    }

    // ── Navigation ────────────────────────────────────────────────────────

    private fun proceedToApp() {
        if (hasProceeded) return
        hasProceeded = true
        val dest = if (!OnboardingActivity.isDone(this)) OnboardingActivity::class.java
                   else HomeActivity::class.java
        startActivity(Intent(this, dest))
        finish()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun openPlayStore(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // Prefer the Play Store app over the browser.
            setPackage("com.android.vending")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Play Store app not available — fall back to browser.
            Log.w(TAG, "Play Store app unavailable, opening browser: ${e.message}")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun releaseNotesSection(config: AppUpdateConfig): String {
        return if (config.releaseNotes.isNotBlank())
            "\n\nWhat's new:\n${config.releaseNotes}"
        else
            ""
    }
}
