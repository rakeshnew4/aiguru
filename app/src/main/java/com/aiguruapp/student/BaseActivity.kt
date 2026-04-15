package com.aiguruapp.student

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.aiguruapp.student.calculator.FloatingCalculatorView
import com.aiguruapp.student.utils.SchoolTheme

/**
 * BaseActivity — all activities that should honor school branding extend this.
 *
 * Enables edge-to-edge mode (app draws under the system bars) and applies
 * school branding colors + correct status-bar icon tint automatically.
 * Dark/immersive activities (Blackboard, GeminiLive, PageViewer) skip this.
 */
open class BaseActivity : AppCompatActivity() {

    /** Floating calculator bubble — shared across the whole Activity lifetime. */
    private var floatingCalc: FloatingCalculatorView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: layout draws under status bar and nav bar.
        // Layouts that declare fitsSystemWindows="true" will auto-add the
        // correct inset padding; others draw behind the bars intentionally.
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
    }

    override fun onResume() {
        super.onResume()
        // Re-apply in case SchoolTheme was loaded by a parent activity
        SchoolTheme.applyStatusBar(window)
    }

    /** Opens the floating calculator panel (called from toolbar buttons in subclasses). */
    fun showCalculator() {
        floatingCalc?.openPanel()
    }
}
