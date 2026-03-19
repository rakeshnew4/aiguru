package com.example.aiguru

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.aiguru.models.School
import com.example.aiguru.models.SubscriptionPlan
import com.example.aiguru.utils.ConfigManager
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton

class SubscriptionActivity : AppCompatActivity() {

    private lateinit var school: School

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        val schoolId = intent.getStringExtra("schoolId")
            ?: SessionManager.getSchoolId(this)

        school = ConfigManager.getSchool(this, schoolId)
            ?: run { navigateHome(); return }

        applySchoolBranding()
        buildPlanCards()
    }

    private fun applySchoolBranding() {
        val branding = school.branding
        runCatching {
            val primary = Color.parseColor(branding.primaryColor)
            findViewById<LinearLayout>(R.id.subscriptionHeader)
                .setBackgroundColor(primary)
        }
        val studentName = SessionManager.getStudentName(this)
        val studentId = SessionManager.getStudentId(this)
        findViewById<TextView>(R.id.schoolNameHeader).text = school.name
        findViewById<TextView>(R.id.studentInfoHeader).text =
            "$studentName  •  ID: $studentId"
        findViewById<TextView>(R.id.schoolLogoText).text = branding.logoEmoji
    }

    private fun buildPlanCards() {
        val container = findViewById<LinearLayout>(R.id.plansContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Identify recommended plan from config (BASIC by default)
        val recommendedPlanId = ConfigManager.getAppConfig(this)
            .let { "BASIC" }  // Could be read from config in future

        school.plans.forEach { plan ->
            val card = inflater.inflate(R.layout.item_plan_card, container, false)
            bindPlanCard(card, plan, plan.id == recommendedPlanId)
            container.addView(card)
        }
    }

    private fun bindPlanCard(view: View, plan: SubscriptionPlan, isRecommended: Boolean) {
        val branding = school.branding
        val primaryColor = runCatching { Color.parseColor(branding.primaryColor) }
            .getOrDefault(Color.parseColor("#1565C0"))
        val accentColor = runCatching { Color.parseColor(branding.accentColor) }
            .getOrDefault(Color.parseColor("#FF8F00"))

        view.findViewById<TextView>(R.id.planName).apply {
            text = plan.name
            setTextColor(primaryColor)
        }
        view.findViewById<TextView>(R.id.planDuration).text = plan.duration
        view.findViewById<TextView>(R.id.planPrice).apply {
            text = plan.displayPrice
            setTextColor(if (plan.isFree) Color.parseColor("#2E7D32") else primaryColor)
        }

        // Features as bullet list
        view.findViewById<TextView>(R.id.planFeatures).text =
            plan.features.joinToString("\n") { "✓  $it" }

        // Badge
        val badgeView = view.findViewById<TextView>(R.id.planBadge)
        val badgeText = when {
            plan.badge.isNotBlank() -> plan.badge
            isRecommended && plan.badge.isBlank() -> "Recommended"
            else -> ""
        }
        if (badgeText.isNotBlank()) {
            badgeView.text = badgeText
            badgeView.visibility = View.VISIBLE
            badgeView.backgroundTintList = ColorStateList.valueOf(accentColor)
        } else {
            badgeView.visibility = View.GONE
        }

        // CTA button
        val btn = view.findViewById<MaterialButton>(R.id.selectPlanButton)
        btn.text = if (plan.isFree) "Start Free Trial" else "Subscribe — ${plan.displayPrice}"
        btn.backgroundTintList = ColorStateList.valueOf(primaryColor)
        btn.setOnClickListener { selectPlan(plan) }

        // Highlight recommended card with border tint
        if (isRecommended) {
            // Slightly elevate the recommended card — done via cardElevation if accessible
            // For now just color the button accent
            btn.backgroundTintList = ColorStateList.valueOf(accentColor)
        }
    }

    private fun selectPlan(plan: SubscriptionPlan) {
        SessionManager.savePlan(this, plan.id, plan.name)
        navigateHome()
    }

    private fun navigateHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
