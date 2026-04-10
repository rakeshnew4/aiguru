package com.aiguruapp.student

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.aiguruapp.student.utils.SchoolTheme

/**
 * BaseActivity — all activities that should honor school branding extend this.
 *
 * Automatically calls SchoolTheme.ensureLoaded() and applies the school's
 * primary color to the status bar. Dark/immersive activities (Blackboard,
 * GeminiLive, PageViewer) should skip extending this and use AppCompatActivity directly.
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SchoolTheme.ensureLoaded(this)
        SchoolTheme.applyStatusBar(window)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply in case SchoolTheme was loaded by a parent activity
        SchoolTheme.applyStatusBar(window)
    }
}
