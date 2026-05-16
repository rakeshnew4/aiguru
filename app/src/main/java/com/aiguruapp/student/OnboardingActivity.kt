package com.aiguruapp.student

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aiguruapp.student.utils.SessionManager

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
    private data class OnboardingPage(
        val emoji: String,
        val title: String,
        val subtitle: String,
        val imageAsset: String? = null   // path inside assets/, null = show emoji
    )

    private val pages = listOf(
        OnboardingPage(
            emoji = "📚",
            title = "Meet Your AI Blackboard Tutor",
            subtitle = "Learn any concept with clear, step-by-step explanations—just like a real classroom.",
            imageAsset = "onboard_images/bb_session.jpeg"
        ),
        OnboardingPage(
            emoji = "🎓",
            title = "Chat With Your AI Tutor",
            subtitle = "Got a doubt? Missed a class? Ask anything and get instant, subject-specific answers.",
            imageAsset = "onboard_images/subject_chat.jpeg"
        ),
        OnboardingPage(
            emoji = "📸",
            title = "Snap. Ask. Understand.",
            subtitle = "Take a photo of your question and get instant, easy-to-follow solutions.",
            imageAsset = "onboard_images/image_crop_send.jpeg"
        ),
        OnboardingPage(
            emoji = "📖",
            title = "Your Subjects, Your Way.",
            subtitle = "Add your subjects, browse chapters, and dive into any topic — or just ask a question directly.",
            imageAsset = "onboard_images/Maths_chapter_pages_view.jpeg"
        ),
        OnboardingPage(
            emoji = "⚡",
            title = "Learn Faster. Think Smarter.",
            subtitle = "Build strong concepts, stay curious, and keep improving every day. Aligned with NCERT & CBSE curriculum."
        ),
    )

    private lateinit var emojiView: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var nextButton: Button
    private lateinit var skipButton: TextView
    private lateinit var dotsContainer: LinearLayout
    private lateinit var bbPreviewContainer: View
    private lateinit var gradePickerContainer: LinearLayout
    private lateinit var gradeChipsRow: LinearLayout
    private lateinit var onboardingImageCard: View
    private lateinit var onboardingImage: ImageView
    private var selectedGrade: String = ""

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
        gradePickerContainer = findViewById(R.id.gradePickerContainer)
        gradeChipsRow = findViewById(R.id.gradeChipsRow)
        onboardingImageCard = findViewById(R.id.onboardingImageCard)
        onboardingImage = findViewById(R.id.onboardingImage)

        buildDots()
        buildGradeChips()
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
        titleView.text = page.title
        subtitleView.text = page.subtitle
        nextButton.text = if (currentPage == pages.size - 1) "Get Started 🚀" else "Next"
        val isLastPage = currentPage == pages.size - 1
        bbPreviewContainer.visibility = if (isLastPage) View.VISIBLE else View.GONE
        gradePickerContainer.visibility = if (isLastPage) View.VISIBLE else View.GONE

        // Show screenshot image or large emoji
        val asset = page.imageAsset
        if (asset != null) {
            emojiView.visibility = View.GONE
            onboardingImageCard.visibility = View.VISIBLE
            try {
                val bmp = BitmapFactory.decodeStream(assets.open(asset))
                onboardingImage.setImageBitmap(bmp)
            } catch (_: Exception) {
                // Asset missing — fall back to emoji
                onboardingImageCard.visibility = View.GONE
                emojiView.text = page.emoji
                emojiView.visibility = View.VISIBLE
            }
        } else {
            onboardingImageCard.visibility = View.GONE
            emojiView.text = page.emoji
            emojiView.visibility = View.VISIBLE
        }

        updateDots()
    }

    private fun buildGradeChips() {
        val grades = listOf("6th", "7th", "8th", "9th", "10th", "11th", "12th")
        val dp = resources.displayMetrics.density
        for (grade in grades) {
            val chip = TextView(this).apply {
                text = grade
                textSize = 13f
                setTextColor(Color.parseColor("#AABBCC"))
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20 * dp
                    setColor(Color.parseColor("#1A2030"))
                    setStroke((1 * dp).toInt(), Color.parseColor("#334455"))
                }
                setOnClickListener { selectGrade(grade, this) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
            gradeChipsRow.addView(chip, lp)
        }
    }

    private fun selectGrade(grade: String, selected: TextView) {
        selectedGrade = grade
        val dp = resources.displayMetrics.density
        for (i in 0 until gradeChipsRow.childCount) {
            val chip = gradeChipsRow.getChildAt(i) as? TextView ?: continue
            val isSelected = chip === selected
            chip.setTextColor(if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#AABBCC"))
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(if (isSelected) Color.parseColor("#3D5AFE") else Color.parseColor("#1A2030"))
                setStroke((1 * dp).toInt(), if (isSelected) Color.parseColor("#3D5AFE") else Color.parseColor("#334455"))
            }
        }
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
        if (selectedGrade.isNotBlank()) {
            SessionManager.saveGrade(this, selectedGrade)
        }
        startActivity(Intent(this, HomeActivity::class.java))
        super.finish()
    }
}
