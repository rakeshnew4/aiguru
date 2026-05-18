package com.aiguruapp.student

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.aiguruapp.student.models.School
import com.aiguruapp.student.utils.SessionManager
import com.aiguruapp.student.utils.SchoolTheme
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton

/**
 * Full-screen branded welcome shown once after a student successfully joins a school.
 *
 * Extras (all required):
 *   EXTRA_SCHOOL_NAME     – display name of the school
 *   EXTRA_SCHOOL_COLOR    – primary color hex (e.g. "#1A1A2E") — may be blank
 *   EXTRA_SCHOOL_LOGO_URL – URL for school logo — may be blank
 *   EXTRA_STUDENT_NAME    – student's display name
 *
 * On "Let's Start →": clears stack and launches HomeActivity.
 */
class SchoolWelcomeActivity : BaseActivity() {

    companion object {
        const val EXTRA_SCHOOL_NAME     = "school_name"
        const val EXTRA_SCHOOL_COLOR    = "school_color"
        const val EXTRA_SCHOOL_LOGO_URL = "school_logo_url"
        const val EXTRA_STUDENT_NAME    = "student_name"
        const val EXTRA_GRADE           = "grade"
        const val EXTRA_SECTION         = "section"
        const val EXTRA_FREE_PLAN_NAME  = "free_plan_name"

        /** Convenience launcher called from SchoolJoinActivity. */
        fun launch(activity: android.app.Activity, school: School, studentName: String, grade: String, section: String, freePlanName: String) {
            val color   = school.branding?.primaryColor?.takeIf { it.isNotBlank() } ?: ""
            val logoUrl = school.branding?.logoUrl?.takeIf { it.isNotBlank() } ?: ""
            activity.startActivity(
                Intent(activity, SchoolWelcomeActivity::class.java).apply {
                    putExtra(EXTRA_SCHOOL_NAME,     school.name)
                    putExtra(EXTRA_SCHOOL_COLOR,    color)
                    putExtra(EXTRA_SCHOOL_LOGO_URL, logoUrl)
                    putExtra(EXTRA_STUDENT_NAME,    studentName)
                    putExtra(EXTRA_GRADE,           grade)
                    putExtra(EXTRA_SECTION,         section)
                    putExtra(EXTRA_FREE_PLAN_NAME,  freePlanName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            activity.finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_school_welcome)

        val schoolName    = intent.getStringExtra(EXTRA_SCHOOL_NAME)    ?: "Your School"
        val schoolColor   = intent.getStringExtra(EXTRA_SCHOOL_COLOR)   ?: ""
        val logoUrl       = intent.getStringExtra(EXTRA_SCHOOL_LOGO_URL)?: ""
        val studentName   = intent.getStringExtra(EXTRA_STUDENT_NAME)   ?: SessionManager.getStudentName(this)
        val grade         = intent.getStringExtra(EXTRA_GRADE)          ?: SessionManager.getGrade(this)
        val section       = intent.getStringExtra(EXTRA_SECTION)        ?: ""
        val freePlanName  = intent.getStringExtra(EXTRA_FREE_PLAN_NAME) ?: ""

        // ── Apply school color to root background ──────────────────────────────
        val bgColor = try {
            if (schoolColor.isNotBlank()) Color.parseColor(schoolColor) else SchoolTheme.primaryColor
        } catch (_: Exception) {
            SchoolTheme.primaryColor
        }
        findViewById<LinearLayout>(R.id.welcomeRoot).setBackgroundColor(bgColor)

        // Make status bar match
        window.statusBarColor = bgColor
        val isLight = ColorUtils.calculateLuminance(bgColor) > 0.4
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = isLight

        // ── School logo / initial ──────────────────────────────────────────────
        val logoImg     = findViewById<ImageView>(R.id.welcomeSchoolLogo)
        val initialText = findViewById<TextView>(R.id.welcomeSchoolInitial)
        if (logoUrl.isNotBlank()) {
            logoImg.visibility = View.VISIBLE
            initialText.visibility = View.GONE
            Glide.with(this).load(logoUrl).circleCrop()
                .error(android.R.drawable.ic_menu_gallery)
                .into(logoImg)
        } else {
            initialText.text = schoolName.firstOrNull()?.uppercaseChar()?.toString() ?: "S"
        }

        // ── Text fields ────────────────────────────────────────────────────────
        findViewById<TextView>(R.id.welcomeSchoolName).text  = schoolName.uppercase()
        val nameDisplay = if (studentName.isNotBlank()) "Welcome, ${studentName.split(" ").first()}!" else "Welcome!"
        findViewById<TextView>(R.id.welcomeGreeting).text    = nameDisplay
        val subtitle = buildString {
            if (grade.isNotBlank()) append("Grade $grade")
            if (grade.isNotBlank() && section.isNotBlank()) append(" • ")
            if (section.isNotBlank()) append("Section $section")
        }
        val studentSubtitle = findViewById<TextView>(R.id.welcomeStudentName)
        if (subtitle.isNotBlank()) {
            studentSubtitle.text = subtitle
            studentSubtitle.visibility = View.VISIBLE
        }

        // ── Grade + Section pills ──────────────────────────────────────────────
        val gradePill   = findViewById<TextView>(R.id.welcomeGradePill)
        val sectionPill = findViewById<TextView>(R.id.welcomeSectionPill)
        val pillDivider = findViewById<View>(R.id.welcomePillDivider)
        if (grade.isNotBlank()) {
            gradePill.text = "Grade $grade"
            gradePill.visibility = View.VISIBLE
        }
        if (section.isNotBlank()) {
            sectionPill.text = "Section $section"
            sectionPill.visibility = View.VISIBLE
            if (grade.isNotBlank()) pillDivider.visibility = View.VISIBLE
        }

        // ── Benefit row customisation for school plan ─────────────────────────
        if (freePlanName.isNotBlank()) {
            val b1 = findViewById<TextView>(R.id.welcomeBenefit1)
            b1.text = "✅  ${freePlanName} — free for ${schoolName.split(" ").first()} students"
        }

        // ── CTA button ─────────────────────────────────────────────────────────
        val btn = findViewById<MaterialButton>(R.id.welcomeStartBtn)
        // Use a white button with school color text so it pops on any background
        btn.setTextColor(bgColor)
        btn.setOnClickListener {
            startActivity(
                Intent(this, HomeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }
}
