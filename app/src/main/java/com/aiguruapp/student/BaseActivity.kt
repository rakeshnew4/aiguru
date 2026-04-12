package com.aiguruapp.student

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.aiguruapp.student.utils.SchoolTheme

/**
 * BaseActivity — all activities that should honor school branding extend this.
 *
 * Enables edge-to-edge mode (app draws under the system bars) and applies
 * school branding colors + correct status-bar icon tint automatically.
 * Dark/immersive activities (Blackboard, GeminiLive, PageViewer) skip this.
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: layout draws under status bar and nav bar.
        // Layouts that declare fitsSystemWindows="true" will auto-add the
        // correct inset padding; others draw behind the bars intentionally.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SchoolTheme.ensureLoaded(this)
        SchoolTheme.applyStatusBar(window)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply in case SchoolTheme was loaded by a parent activity
        SchoolTheme.applyStatusBar(window)
    }
}
