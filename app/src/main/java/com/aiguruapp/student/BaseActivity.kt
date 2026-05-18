package com.aiguruapp.student

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aiguruapp.student.BuildConfig
import com.aiguruapp.student.calculator.FloatingCalculatorView
import com.aiguruapp.student.puzzle.FloatingPuzzleView
import com.aiguruapp.student.utils.SchoolTheme

/**
 * BaseActivity — all activities that should honor school branding extend this.
 *
 * Enables edge-to-edge mode (app draws under the system bars) and applies
 * school branding colors + correct status-bar icon tint automatically.
 * Dark/immersive activities (Blackboard, GeminiLive, PageViewer) skip this.
 */
open class BaseActivity : AppCompatActivity() {

    private var floatingCalc: FloatingCalculatorView? = null
    private var floatingPuzzle: FloatingPuzzleView? = null
    private var securityDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SchoolTheme.ensureLoaded(this)
        SchoolTheme.applyStatusBar(window)
    }

    /**
     * onPostCreate is called after the subclass has finished its own onCreate
     * (and therefore has already called setContentView).  This is the earliest
     * safe moment to add an overlay on top of the completed layout hierarchy.
     */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Apply system bar insets to the content view so layouts don't go under the bars.
        // This works together with enableEdgeToEdge() + removing fitsSystemWindows from roots.
        val contentView = findViewById<ViewGroup>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (floatingCalc == null) {
            floatingCalc = FloatingCalculatorView(this)
            addContentView(
                floatingCalc!!,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        if (floatingPuzzle == null) {
            floatingPuzzle = FloatingPuzzleView(this)
            addContentView(
                floatingPuzzle!!,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        SchoolTheme.applyStatusBar(window)
        detectSecurityThreat()?.let { (title, message, openSettings) ->
            showSecurityBlock(title, message, openSettings)
        }
    }

    override fun onDestroy() {
        securityDialog?.dismiss()
        securityDialog = null
        super.onDestroy()
    }

    // Returns Triple(title, message, showOpenSettingsButton) or null if safe
    private fun detectSecurityThreat(): Triple<String, String, Boolean>? {
        if (BuildConfig.DEBUG) return null
        val cr = contentResolver
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        if (adb != 0 || dev != 0) return Triple(
            "USB Debugging Detected",
            "USB Debugging or Developer Options is enabled.\n\nPlease disable it to continue using Afterclass AI.",
            true
        )
        if (isRooted()) return Triple(
            "Rooted Device Detected",
            "Afterclass AI cannot run on a rooted device for security reasons.\n\nPlease use an unrooted device.",
            false
        )
        if (isEmulator()) return Triple(
            "Emulator Detected",
            "Afterclass AI cannot run on an emulator.\n\nPlease use a real Android device.",
            false
        )
        return null
    }

    private fun isRooted(): Boolean {
        val suPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/su/xbin/su", "/data/local/su"
        )
        if (suPaths.any { java.io.File(it).exists() }) return true
        val rootApps = arrayOf(
            "com.topjohnwu.magisk", "com.noshufou.android.su",
            "com.koushikdutta.superuser", "eu.chainfire.supersu"
        )
        return rootApps.any { pkg ->
            runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("Emulator", ignoreCase = true)
            || Build.MODEL.contains("Android SDK", ignoreCase = true)
            || Build.MANUFACTURER.contains("Genymotion", ignoreCase = true)
            || Build.BRAND.startsWith("generic")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk")
    }

    private fun showSecurityBlock(title: String, message: String, showOpenSettings: Boolean) {
        if (securityDialog?.isShowing == true) return
        val builder = AlertDialog.Builder(this)
            .setTitle("⚠️ $title")
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton("Close App") { _, _ -> finishAffinity() }
        if (showOpenSettings) {
            builder.setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        }
        securityDialog = builder.show()
    }

    fun showCalculator() {
        floatingCalc?.openPanel()
    }
}
