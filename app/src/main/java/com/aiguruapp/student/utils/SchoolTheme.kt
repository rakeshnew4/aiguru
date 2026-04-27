package com.aiguruapp.student.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.aiguruapp.student.models.SchoolBranding
import com.google.android.material.button.MaterialButton

/**
 * SchoolTheme — centralized runtime color system.
 *
 * COMPILE-TIME defaults live in res/values/colors.xml.
 * RUNTIME school override: call [load] once (e.g. from HomeActivity after
 * the school is resolved), then use [primaryColor], [primaryCsl] etc.
 * throughout the app.
 *
 * Usage:
 *   // On app start / school change:
 *   SchoolTheme.load(school?.branding)
 *
 *   // In any activity to tint views:
 *   SchoolTheme.tint(binding.myButton)        // fills with primary
 *   SchoolTheme.tintLight(binding.myChip)     // fills with primaryLight
 *   SchoolTheme.applyStatusBar(window)
 *
 * Color keys map:
 *   SchoolBranding.primaryColor     → colorPrimary
 *   SchoolBranding.primaryDarkColor → colorPrimaryDark
 *   SchoolBranding.accentColor      → colorSecondary
 */
object SchoolTheme {

    // ── Active colors (default = GoIbibo-style warm orange) ───────────
    var primaryColor: Int = Color.parseColor("#1A1A2E"); private set
    var primaryDarkColor: Int = Color.parseColor("#0A0A1A"); private set
    var primaryLightColor: Int = Color.parseColor("#EBEBF0"); private set
    var accentColor: Int = Color.parseColor("#1565C0"); private set

    // ── Fixed semantic colors (not school-branded) ─────────────────────
    val successColor: Int = Color.parseColor("#1E9B6B")
    val successLightColor: Int = Color.parseColor("#E6FAF4")
    val errorColor: Int = Color.parseColor("#E05050")
    val errorLightColor: Int = Color.parseColor("#FFEEEE")
    val bgColor: Int = Color.parseColor("#F5F7FA")
    val surfaceColor: Int = Color.parseColor("#FFFFFF")
    val textPrimary: Int = Color.parseColor("#1A1A2E")
    val textSecondary: Int = Color.parseColor("#666B8A")
    val dividerColor: Int = Color.parseColor("#E0E4F0")

    // ── ColorStateList helpers ─────────────────────────────────────────
    val primaryCsl: ColorStateList get() = ColorStateList.valueOf(primaryColor)
    val primaryDarkCsl: ColorStateList get() = ColorStateList.valueOf(primaryDarkColor)
    val primaryLightCsl: ColorStateList get() = ColorStateList.valueOf(primaryLightColor)
    val accentCsl: ColorStateList get() = ColorStateList.valueOf(accentColor)

    private var isLoaded = false

    // ── Core API ──────────────────────────────────────────────────────

    /**
     * Load colors from the school's branding config.
     * Call once per school (e.g. in HomeActivity.onCreate after ConfigManager resolves school).
     * Safe to call multiple times — idempotent if branding is null.
     */
    fun load(branding: SchoolBranding?) {
        if (branding == null) {
            isLoaded = true
            return
        }
        runCatching { primaryColor = Color.parseColor(branding.primaryColor) }
        runCatching { primaryDarkColor = Color.parseColor(branding.primaryDarkColor) }
        runCatching { accentColor = Color.parseColor(branding.accentColor) }
        // Auto-derive light variant: blend primary at 15% alpha over white
        primaryLightColor = blendWithWhite(primaryColor, 0.15f)
        isLoaded = true
    }

    /**
     * Ensure colors are loaded — call from any activity as a safety net.
     * Only reloads if [load] was never called.
     */
    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        val schoolId = SessionManager.getSchoolId(context)
        val school = ConfigManager.getSchool(context, schoolId)
        load(school?.branding)
    }

    /** Reset to defaults (useful for logout). */
    fun reset() {
        primaryColor = Color.parseColor("#1A1A2E")
        primaryDarkColor = Color.parseColor("#0A0A1A")
        primaryLightColor = Color.parseColor("#EBEBF0")
        accentColor = Color.parseColor("#1565C0")
        isLoaded = false
    }

    // ── Window / status bar ───────────────────────────────────────────

    /**
     * Tints the status bar with primaryDark and automatically chooses
     * dark (black) or light (white) status-bar icons based on the
     * luminance of the chosen color — exactly like BookMyShow / Zomato.
     */
    fun applyStatusBar(window: Window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // App background is light (#F5F7FA / white) — always use dark (black) icons
        // so the clock and battery are clearly visible over the app content.
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
    }

    /** Returns true when [color] is light enough to need dark (black) text/icons. */
    private fun isColorLight(color: Int): Boolean {
        // W3C relative luminance formula
        val r = android.graphics.Color.red(color) / 255.0
        val g = android.graphics.Color.green(color) / 255.0
        val b = android.graphics.Color.blue(color) / 255.0
        fun linearize(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val luminance = 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
        return luminance > 0.179  // above this threshold, use dark icons
    }

    // ── View tinting helpers ──────────────────────────────────────────

    /** Fill background with primary color (solid). */
    fun tint(view: View?) {
        view?.backgroundTintList = primaryCsl
    }

    /** Fill background with primaryLight (subtle tinted chip/badge). */
    fun tintLight(view: View?) {
        view?.backgroundTintList = primaryLightCsl
    }

    /** Set background color directly (use for non-tintable backgrounds like LinearLayout). */
    fun setBackground(view: View?) {
        view?.setBackgroundColor(primaryColor)
    }

    /** Set background to primaryLight color directly. */
    fun setBackgroundLight(view: View?) {
        view?.setBackgroundColor(primaryLightColor)
    }

    /** Set MaterialButton to primary filled style. */
    fun styleButtonFilled(btn: MaterialButton?) {
        btn?.backgroundTintList = primaryCsl
        btn?.setTextColor(Color.WHITE)
    }

    /** Set MaterialButton to outlined (light fill, primary border + text). */
    fun styleButtonLight(btn: MaterialButton?) {
        btn?.backgroundTintList = primaryLightCsl
        btn?.setTextColor(primaryColor)
        btn?.strokeColor = primaryCsl
    }

    // ── Color utilities ───────────────────────────────────────────────

    /** Blend [color] at [alpha] opacity over white. */
    private fun blendWithWhite(color: Int, alpha: Float): Int {
        return ColorUtils.blendARGB(Color.WHITE, color, alpha)
    }

    /** Create a ColorStateList for the given color. */
    fun csl(color: Int): ColorStateList = ColorStateList.valueOf(color)
}
