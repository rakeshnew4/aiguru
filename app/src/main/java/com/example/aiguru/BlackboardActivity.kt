package com.example.aiguru

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.aiguru.chat.BlackboardGenerator
import com.example.aiguru.utils.PromptRepository
import com.example.aiguru.utils.TTSCallback
import com.example.aiguru.utils.TextToSpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen "blackboard" lesson.
 *
 * All steps live in a single scrollable view — each card fades in as the lesson
 * progresses so the student can review every step at once.
 *
 * Navigation:
 *  ▶ next  → reveals & speaks the next step card
 *  ◀ prev  → fades out the last card, re-speaks the previous
 *  ↺ replay → re-speaks the currently last visible step
 *  ⏸/▶ pause → suspends / resumes TTS
 */
class BlackboardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MESSAGE         = "extra_message"
        const val EXTRA_MESSAGE_ID      = "extra_message_id"
        const val EXTRA_USER_ID         = "extra_user_id"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_LANGUAGE_TAG    = "extra_language_tag"
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var loadingGroup:    View
    private lateinit var loadingText:     TextView
    private lateinit var contentGroup:    View
    private lateinit var stepsScrollView: ScrollView
    private lateinit var stepsContainer:  LinearLayout
    private lateinit var stepCounter:     TextView
    private lateinit var dotsContainer:   LinearLayout
    private lateinit var closeBtn:        TextView
    private lateinit var prevBtn:         TextView
    private lateinit var pauseBtn:        TextView
    private lateinit var replayBtn:       TextView
    private lateinit var nextBtn:         TextView
    private lateinit var teacherAvatar:   TeacherAvatarView

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeechManager
    private var steps          = listOf<BlackboardGenerator.BlackboardStep>()
    private var visibleCount   = 0        // number of cards currently shown
    private var isPaused       = false
    private var computedFontSp = 20f      // font size derived from longest step text
    private var preferredLanguageTag = "en-US"

    /** Index of the step that is currently visible / being spoken. */
    private val currentIndex get() = if (visibleCount > 0) visibleCount - 1 else 0

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blackboard)

        loadingGroup    = findViewById(R.id.loadingGroup)
        loadingText     = findViewById(R.id.loadingText)
        contentGroup    = findViewById(R.id.contentGroup)
        stepsScrollView = findViewById(R.id.stepsScrollView)
        stepsContainer  = findViewById(R.id.stepsContainer)
        stepCounter     = findViewById(R.id.stepCounter)
        dotsContainer   = findViewById(R.id.dotsContainer)
        closeBtn        = findViewById(R.id.closeButton)
        prevBtn         = findViewById(R.id.prevButton)
        pauseBtn        = findViewById(R.id.pauseButton)
        replayBtn       = findViewById(R.id.replayButton)
        nextBtn         = findViewById(R.id.nextButton)
        teacherAvatar   = findViewById(R.id.teacherAvatar)

        closeBtn.setOnClickListener  { finish() }
        prevBtn.setOnClickListener   { prevStep() }
        nextBtn.setOnClickListener   { nextStep() }
        replayBtn.setOnClickListener { reSpeakCurrent() }
        pauseBtn.setOnClickListener  { togglePause() }

        PromptRepository.init(this)
        tts = TextToSpeechManager(this)
        preferredLanguageTag = intent.getStringExtra(EXTRA_LANGUAGE_TAG)?.takeIf { it.isNotBlank() } ?: "en-US"
        generateSteps(
            message        = intent.getStringExtra(EXTRA_MESSAGE) ?: "",
            messageId      = intent.getStringExtra(EXTRA_MESSAGE_ID),
            userId         = intent.getStringExtra(EXTRA_USER_ID),
            conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        )
    }

    override fun onDestroy() {
        tts.destroy()
        super.onDestroy()
    }

    // ── Generation ────────────────────────────────────────────────────────────

    private fun generateSteps(
        message: String,
        messageId: String? = null,
        userId: String? = null,
        conversationId: String? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            BlackboardGenerator.generate(
                messageContent = message,
                messageId      = messageId,
                userId         = userId,
                conversationId = conversationId,
                preferredLanguageTag = preferredLanguageTag,
                onSuccess = { generated ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        steps = generated
                        computedFontSp = computeFontSize(steps)
                        loadingGroup.visibility = View.GONE
                        contentGroup.visibility = View.VISIBLE
                        buildDots()
                        revealStep(0)
                    }
                },
                onError = { err ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        loadingText.text = "Couldn't build lesson. Please try again."
                        android.util.Log.e("Blackboard", "Generation error: $err")
                    }
                }
            )
        }
    }

    // ── Font size ─────────────────────────────────────────────────────────────

    private fun computeFontSize(steps: List<BlackboardGenerator.BlackboardStep>): Float {
        val maxLen = steps.maxOfOrNull { it.text.length } ?: 80
        return when {
            maxLen <= 60  -> 24f
            maxLen <= 120 -> 21f
            maxLen <= 200 -> 18f
            maxLen <= 320 -> 16f
            else          -> 14f
        }
    }

    // ── Step reveal ───────────────────────────────────────────────────────────

    /** Append a card for [index] to the scroll view, fade it in, then speak. */
    private fun revealStep(index: Int) {
        if (steps.isEmpty() || index >= steps.size) return
        visibleCount = index + 1
        tts.stop()
        updateCounterAndDots()

        val card = createStepCard(index)
        stepsContainer.addView(card)

        // Fade the card in smoothly
        card.animate().alpha(1f).setDuration(480).start()

        // Auto-scroll so the new card is visible
        stepsScrollView.postDelayed({
            stepsScrollView.smoothScrollTo(0, stepsContainer.bottom)
        }, 300)

        // Start speaking after the fade-in settles
        if (!isPaused) {
            card.postDelayed({ speakStep(index) }, 380)
        }
    }

    /** Reveal the next step if available. */
    private fun nextStep() {
        if (visibleCount < steps.size) revealStep(visibleCount)
    }

    /**
     * Fade out and remove the last card, then re-speak the previous step.
     * If only one card is visible, just replay it.
     */
    private fun prevStep() {
        tts.stop()
        if (visibleCount <= 0) return

        if (visibleCount == 1) {
            reSpeakCurrent()
            return
        }

        val lastCard = stepsContainer.getChildAt(stepsContainer.childCount - 1) ?: return
        lastCard.animate().alpha(0f).setDuration(300).withEndAction {
            stepsContainer.removeView(lastCard)
            visibleCount--
            updateCounterAndDots()
            if (!isPaused) {
                stepsScrollView.postDelayed({
                    speakStep(currentIndex)
                }, 200)
            }
        }.start()
    }

    private fun reSpeakCurrent() {
        tts.stop()
        if (!isPaused) speakStep(currentIndex)
    }

    private fun togglePause() {
        isPaused = !isPaused
        pauseBtn.text = if (isPaused) "▶" else "⏸"
        if (isPaused) {
            tts.stop()
            teacherAvatar.setSpeaking(false)
        } else {
            speakStep(currentIndex)
        }
    }

    private fun speakStep(index: Int) {
        val step = steps.getOrNull(index) ?: return
        tts.setLocale(Locale.forLanguageTag(step.languageTag))
        tts.speak(step.speech, makeTtsCallback())
    }

    private fun updateCounterAndDots() {
        stepCounter.text = "$visibleCount / ${steps.size}"
        updateDots(currentIndex)
        prevBtn.alpha = if (visibleCount <= 1) 0.30f else 1f
        nextBtn.alpha = if (visibleCount >= steps.size) 0.30f else 1f
    }

    // ── Card creation ─────────────────────────────────────────────────────────

    // One accent colour per step (cycles for long lessons)
    private val accentPalette = listOf(
        "#5C6BC0", "#26A69A", "#EF5350",
        "#AB47BC", "#FFA726", "#42A5F5"
    )

    private fun createStepCard(index: Int): View {
        val dp = resources.displayMetrics.density
        val accent = Color.parseColor(accentPalette[index % accentPalette.size])

        // ── Outer card ──────────────────────────────────────────────────────
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * dp
                setColor(Color.parseColor("#161625"))
                setStroke(
                    (1 * dp).toInt(),
                    Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
                )
            }
            setPadding(
                (14 * dp).toInt(), (12 * dp).toInt(),
                (14 * dp).toInt(), (14 * dp).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
            alpha = 0f   // starts transparent; animated to 1f
        }

        // ── Header row: badge · "Step N ·" · accent divider ────────────────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }

        val badge = TextView(this).apply {
            text = "${index + 1}"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            val sz = (22 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (8 * dp).toInt()
            }
        }

        val stepLabel = TextView(this).apply {
            text = "Step ${index + 1}  ·"
            textSize = 11f
            setTextColor(Color.argb(120, 200, 200, 230))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val divider = View(this).apply {
            setBackgroundColor(
                Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent))
            )
            layoutParams = LinearLayout.LayoutParams(0, (1 * dp).toInt(), 1f).apply {
                marginStart = (10 * dp).toInt()
            }
        }

        headerRow.addView(badge)
        headerRow.addView(stepLabel)
        headerRow.addView(divider)

        // ── Content text (markdown rendered, dark-theme palette) ────────────
        val contentText = TextView(this).apply {
            text = parseMarkdownForBlackboard(steps[index].text)
            textSize = computedFontSp
            setTextColor(Color.parseColor("#E8E8F8"))
            setLineSpacing(0f, 1.55f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        card.addView(headerRow)
        card.addView(contentText)
        return card
    }

    /**
     * Converts step text to a styled SpannableStringBuilder for a dark background:
     *  **bold**  → golden yellow  (#FFE57F)
     *  *italic*  → sky blue       (#B3C8FF)
     *  `code`    → mint green     (#80FFAA) monospace
     *  # H1      → warm gold      (#FFD54F) +22% size  bold
     *  ## H2     → cyan           (#80DEEA) +12% size  bold
     *  ### H3    → lavender       (#CE93D8) +6%  size  bold
     *  - bullets → •
     */
    private fun parseMarkdownForBlackboard(text: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val lines = text.lines()
        for ((idx, rawLine) in lines.withIndex()) {
            val trimmed = rawLine.trim()
            val hlvl = when {
                trimmed.startsWith("### ") -> 3
                trimmed.startsWith("## ")  -> 2
                trimmed.startsWith("# ")   -> 1
                else -> 0
            }
            val lineText = when {
                hlvl == 1 -> trimmed.removePrefix("# ")
                hlvl == 2 -> trimmed.removePrefix("## ")
                hlvl == 3 -> trimmed.removePrefix("### ")
                trimmed.startsWith("- ")  -> "  •  " + trimmed.removePrefix("- ")
                trimmed.startsWith("* ") && !trimmed.startsWith("**") ->
                    "  •  " + trimmed.removePrefix("* ")
                trimmed.startsWith("• ")  -> "  " + trimmed
                trimmed == "---" || trimmed == "___" -> "──────────────────"
                else -> rawLine
            }
            val lineSpanned = applyInlineSpansBlackboard(lineText)
            if (hlvl > 0) {
                val color = when (hlvl) {
                    1    -> Color.parseColor("#FFD54F")
                    2    -> Color.parseColor("#80DEEA")
                    else -> Color.parseColor("#CE93D8")
                }
                val size = when (hlvl) { 1 -> 1.22f; 2 -> 1.12f; else -> 1.06f }
                lineSpanned.setSpan(
                    StyleSpan(Typeface.BOLD), 0, lineSpanned.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                lineSpanned.setSpan(
                    RelativeSizeSpan(size), 0, lineSpanned.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                lineSpanned.setSpan(
                    ForegroundColorSpan(color), 0, lineSpanned.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            ssb.append(lineSpanned)
            if (idx < lines.size - 1) ssb.append('\n')
        }
        return ssb
    }

    private fun applyInlineSpansBlackboard(text: String): SpannableStringBuilder {
        data class Sp(val start: Int, val end: Int, val inner: String, val type: String)
        val found = mutableListOf<Sp>()

        Regex("""\*\*(.+?)\*\*""").findAll(text).forEach {
            found.add(Sp(it.range.first, it.range.last + 1, it.groupValues[1], "bold"))
        }
        Regex("""\*([^*\n]+?)\*""").findAll(text).forEach { m ->
            if (found.none { it.start <= m.range.first && it.end >= m.range.last + 1 })
                found.add(Sp(m.range.first, m.range.last + 1, m.groupValues[1], "italic"))
        }
        Regex("""`([^`\n]+?)`""").findAll(text).forEach {
            found.add(Sp(it.range.first, it.range.last + 1, it.groupValues[1], "code"))
        }

        if (found.isEmpty()) return SpannableStringBuilder(text)

        found.sortBy { it.start }
        val ssb = SpannableStringBuilder()
        var cursor = 0
        for (span in found) {
            if (span.start < cursor) continue
            ssb.append(text.substring(cursor, span.start))
            val s = ssb.length
            ssb.append(span.inner)
            val e = ssb.length
            when (span.type) {
                "bold" -> {
                    ssb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(
                        ForegroundColorSpan(Color.parseColor("#FFE57F")),
                        s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                "italic" -> {
                    ssb.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(
                        ForegroundColorSpan(Color.parseColor("#B3C8FF")),
                        s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                "code" -> {
                    ssb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(
                        ForegroundColorSpan(Color.parseColor("#80FFAA")),
                        s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            cursor = span.end
        }
        if (cursor < text.length) ssb.append(text.substring(cursor))
        return ssb
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun makeTtsCallback() = object : TTSCallback {

        private var fakeAudioAnim: ValueAnimator? = null

        override fun onStart() {
            runOnUiThread {
                teacherAvatar.setSpeaking(true)
                fakeAudioAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 120
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    var t = 0f
                    addUpdateListener {
                        t += 0.15f
                        val base = ((Math.sin(t.toDouble()) + 1.0) / 2.0).toFloat()
                        val level = base * (0.3f + Math.random().toFloat() * 0.7f)
                        teacherAvatar.updateAudioLevel(level)
                    }
                    start()
                }
            }
        }

        override fun onComplete() {
            runOnUiThread {
                teacherAvatar.setSpeaking(false)
                fakeAudioAnim?.cancel()
                fakeAudioAnim = null
                teacherAvatar.updateAudioLevel(0f)
            }
            if (!isPaused && visibleCount < steps.size) {
                val delay = (500..900).random().toLong()
                stepsScrollView.postDelayed({ nextStep() }, delay)
            }
        }

        override fun onError(error: String) {
            runOnUiThread {
                teacherAvatar.setSpeaking(false)
                fakeAudioAnim?.cancel()
                fakeAudioAnim = null
                teacherAvatar.updateAudioLevel(0f)
            }
            android.util.Log.w("Blackboard", "TTS: $error")
        }
    }

    // ── Progress dots ─────────────────────────────────────────────────────────

    private fun buildDots() {
        dotsContainer.removeAllViews()
        val dp = resources.displayMetrics.density
        repeat(steps.size) { i ->
            val dot = View(this).apply {
                val sz = (9 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = (9 * dp).toInt()
                }
                background = makeDotDrawable(i == 0)
            }
            dotsContainer.addView(dot)
        }
    }

    private fun updateDots(active: Int) {
        for (i in 0 until dotsContainer.childCount) {
            val color = when {
                i < active  -> Color.argb(136, 255, 255, 255)  // revealed (past)
                i == active -> Color.argb(255, 255, 255, 255)  // current
                else        -> Color.argb(51,  255, 255, 255)  // not yet shown
            }
            (dotsContainer.getChildAt(i).background as? GradientDrawable)?.setColor(color)
        }
    }

    private fun makeDotDrawable(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (active) Color.argb(255, 255, 255, 255) else Color.argb(51, 255, 255, 255))
    }
}

