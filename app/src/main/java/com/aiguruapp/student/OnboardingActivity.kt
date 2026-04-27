package com.aiguruapp.student

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 3-screen onboarding walkthrough shown to first-time users.
 * After completion (or skip) the user is routed to HomeActivity.
 *
 * Shown only once — controlled by SharedPreferences key PREF_ONBOARDING_DONE.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val PREF_NAME = "onboarding"
        const val PREF_KEY_DONE = "done"

        /** Returns true if the user has already seen onboarding. */
        fun isDone(ctx: Context): Boolean {
            return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY_DONE, false)
        }

        private fun markDone(ctx: Context) {
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY_DONE, true).apply()
        }
    }

    private var currentPage = 0

    // Screen data
    private data class OnboardingPage(val emoji: String, val title: String, val subtitle: String)

    private val pages = listOf(
        OnboardingPage(
        emoji = "📚",
        title = "Meet Your AI Blackboard Tutor",
        subtitle = "Learn any concept with clear, step-by-step explanations—just like a real classroom."
    ),
    OnboardingPage(
        emoji = "🎓",
        title = "Interactive Blackboard Lessons",
        subtitle = "Got a doubt? Missed a class? Ask anything and watch it explained visually, step by step."
    ),
    OnboardingPage(
        emoji = "📸",
        title = "Snap. Ask. Understand.",
        subtitle = "Take a photo of your question and get instant, easy-to-follow solutions."
    ),
    OnboardingPage(
        emoji = "⚡",
        title = "Learn Faster. Think Smarter.",
        subtitle = "Build strong concepts, stay curious, and keep improving every day, Also Aligned with NCERT & CBSE concepts for school learning Greaty"
    ),
        
    )

    private lateinit var emojiView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var nextButton: Button
    private lateinit var skipButton: TextView
    private lateinit var dotsContainer: LinearLayout
    private lateinit var bbPreviewContainer: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboarding)

        emojiView = findViewById(R.id.onboardingEmoji)
        titleView = findViewById(R.id.onboardingTitle)
        subtitleView = findViewById(R.id.onboardingSubtitle)
        nextButton = findViewById(R.id.onboardingNextButton)
        skipButton = findViewById(R.id.onboardingSkipButton)
        dotsContainer = findViewById(R.id.onboardingDots)
        bbPreviewContainer = findViewById(R.id.bbPreviewContainer)

        buildDots()
        updatePage()

        nextButton.setOnClickListener {
            if (currentPage < pages.size - 1) {
                currentPage++
                updatePage()
            } else {
                finish()
            }
        }

        skipButton.setOnClickListener { finish() }
    }

    private fun updatePage() {
        val page = pages[currentPage]
        emojiView.text = page.emoji
        titleView.text = page.title
        subtitleView.text = page.subtitle
        nextButton.text = if (currentPage == pages.size - 1) "Get Started 🚀" else "Next"
        bbPreviewContainer.visibility = if (currentPage == pages.size - 1) View.VISIBLE else View.GONE
        updateDots()
    }

    private fun buildDots() {
        dotsContainer.removeAllViews()
        repeat(pages.size) {
            val dot = View(this)
            val size = (10 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(8, 0, 8, 0)
            dot.layoutParams = params
            dot.setBackgroundResource(R.drawable.onboarding_dot_inactive)
            dotsContainer.addView(dot)
        }
    }

    private fun updateDots() {
        for (i in 0 until dotsContainer.childCount) {
            val dot = dotsContainer.getChildAt(i)
            dot.setBackgroundResource(
                if (i == currentPage) R.drawable.onboarding_dot_active
                else R.drawable.onboarding_dot_inactive
            )
        }
    }

    override fun finish() {
        markDone(this)
        startActivity(Intent(this, HomeActivity::class.java))
        super.finish()
    }
}
