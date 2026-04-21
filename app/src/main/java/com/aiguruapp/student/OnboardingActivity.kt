package com.aiguruapp.student

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
            title = "Your AI Homework Helper",
            subtitle = "Stuck on a question? Get instant answers in seconds from your personal AI tutor."
        ),
        OnboardingPage(
            emoji = "📸",
            title = "Snap & Solve",
            subtitle = "Take a photo of your question and get step-by-step solutions for any subject."
        ),
        OnboardingPage(
            emoji = "⚡",
            title = "Learn Smarter",
            subtitle = "Understand concepts clearly, finish homework faster, and ace your exams with ease!"
        )
    )

    private lateinit var emojiView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var nextButton: Button
    private lateinit var skipButton: TextView
    private lateinit var dotsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        emojiView = findViewById(R.id.onboardingEmoji)
        titleView = findViewById(R.id.onboardingTitle)
        subtitleView = findViewById(R.id.onboardingSubtitle)
        nextButton = findViewById(R.id.onboardingNextButton)
        skipButton = findViewById(R.id.onboardingSkipButton)
        dotsContainer = findViewById(R.id.onboardingDots)

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
