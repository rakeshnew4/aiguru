package com.example.aiguru

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebSettings
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.example.aiguru.utils.WikimediaUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
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
    private lateinit var handWriter:      TextView

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeechManager
    private var steps            = listOf<BlackboardGenerator.BlackboardStep>()
    private var currentStepIdx   = 0
    private var currentFrameIdx  = 0
    private var isPaused         = false
    private var typeAnimator:    ValueAnimator? = null
    private var computedFontSp   = 30f
    private var preferredLanguageTag = "en-US"

    // Board views created once in setupBoard(), updated each frame
    private var boardLayout: LinearLayout? = null

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
        handWriter      = findViewById(R.id.handWriter)

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
        typeAnimator?.cancel()
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
                        setupBoard()
                        showFrame(0, 0)
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
        val maxLen = steps.flatMap { it.frames }.maxOfOrNull { it.text.length } ?: 80
        return when {
            maxLen <= 40  -> 28f
            maxLen <= 80  -> 24f
            maxLen <= 140 -> 20f
            maxLen <= 240 -> 17f
            else          -> 14f
        }
    }

    // ── Board setup ───────────────────────────────────────────────────────────

    /** Creates the permanent board container. Content is appended per frame via showFrame(). */
    private fun setupBoard() {
        val dp = resources.displayMetrics.density
        stepsContainer.removeAllViews()

        boardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (24 * dp).toInt(), (24 * dp).toInt(),
                (24 * dp).toInt(), (24 * dp).toInt()
            )
        }
        stepsContainer.addView(boardLayout)
    }

    // ── Frame playback ────────────────────────────────────────────────────────

    /** Display frame [frameIdx] of step [stepIdx]: append to board, fade in, speak. */
    private fun showFrame(stepIdx: Int, frameIdx: Int) {
        val step  = steps.getOrNull(stepIdx) ?: return
        val frame = step.frames.getOrNull(frameIdx) ?: return
        currentStepIdx  = stepIdx
        currentFrameIdx = frameIdx
        tts.stop()
        typeAnimator?.cancel()
        handWriter.visibility = View.INVISIBLE
        updateCounterAndDots()

        val board = boardLayout ?: return
        val dp = resources.displayMetrics.density
        val caveatFont = ResourcesCompat.getFont(this, R.font.kalam)

        // Clear the board only if we're restarting the entire lesson (from prev button returning to start)
        // or starting fresh. The actual forward traversal only appends.
        if (stepIdx == 0 && frameIdx == 0) {
            board.removeAllViews()
        }

        // If this is the FIRST frame of a NEW step, append the Step Title
        if (frameIdx == 0) {
            val titleWrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (stepIdx > 0) (32 * dp).toInt() else 0 }
                alpha = 0f
            }

            val titleText = if (step.title.isNotBlank()) "${step.title}" else ""
            val titleView = TextView(this).apply {
                text = titleText
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#F5E3A0"))
                typeface = Typeface.create(caveatFont, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            titleWrapper.addView(titleView)

            val separator = View(this).apply {
                setBackgroundColor(Color.parseColor("#8BAB8B"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                ).apply { topMargin = (10 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
            }
            titleWrapper.addView(separator)

            board.addView(titleWrapper)
            titleWrapper.animate().alpha(1f).setDuration(400).start()

            // Placeholder inserted synchronously so the image/icon appears between
            // the step title and the first frame's text, even though the fetch is async.
            val imagePlaceholder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            board.addView(imagePlaceholder)

            val imageQuery = step.image_description.ifBlank { step.title }
            if (imageQuery.isNotBlank()) fetchAndShowStepImage(imageQuery, step.imageConfidenceScore, imagePlaceholder)
        }

        // Now append the Frame Content
        val contentText = TextView(this).apply {
            textSize = computedFontSp
            gravity = Gravity.LEFT
            setTextColor(Color.parseColor("#F0EDD0"))
            setLineSpacing(0f, 1.6f)
            typeface = caveatFont
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * dp).toInt() }
        }
        board.addView(contentText)

        val baseSsb = buildFrameText(frame.text, frame.highlight)
        val textLen = baseSsb.length

        if (textLen == 0) {
            contentText.text = baseSsb
            if (!isPaused && frame.speech.isNotBlank()) {
                contentText.postDelayed({ speakFrame(stepIdx, frameIdx) }, 200)
            }
            return
        }

        // Setup typewriter transparent span
        val transparentSpan = ForegroundColorSpan(Color.TRANSPARENT)
        val initialSsb = SpannableStringBuilder(baseSsb)
        initialSsb.setSpan(transparentSpan, 0, textLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        contentText.text = initialSsb

        handWriter.visibility = View.VISIBLE
        stepsScrollView.postDelayed({ stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 50)

        typeAnimator = ValueAnimator.ofInt(0, textLen).apply {
            duration = frame.durationMs
            addUpdateListener { anim ->
                val revealed = anim.animatedValue as Int
                val currentSsb = SpannableStringBuilder(baseSsb)
                if (revealed < textLen) {
                    currentSsb.setSpan(ForegroundColorSpan(Color.TRANSPARENT), revealed, textLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                contentText.text = currentSsb

                val layout = contentText.layout
                if (layout != null && revealed > 0) {
                    val charIdx = revealed - 1
                    val line = layout.getLineForOffset(charIdx)
                    val charX = layout.getPrimaryHorizontal(charIdx)
                    val charY = layout.getLineBottom(line)

                    val contentLoc = IntArray(2)
                    contentText.getLocationInWindow(contentLoc)
                    val handParentLoc = IntArray(2)
                    (handWriter.parent as View).getLocationInWindow(handParentLoc)
                    
                    val relativeX = contentLoc[0] - handParentLoc[0]
                    val relativeY = contentLoc[1] - handParentLoc[1]
                    
                    handWriter.translationX = relativeX + charX + contentText.paddingLeft - (handWriter.width * 0.1f)
                    handWriter.translationY = relativeY + charY + contentText.paddingTop - (handWriter.height * 0.85f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    handWriter.visibility = View.INVISIBLE
                    stepsScrollView.smoothScrollTo(0, stepsContainer.bottom)
                }
            })
            start()
        }
        // Start speech in parallel with the writing animation (after a short lead-in delay)
        if (!isPaused && frame.speech.isNotBlank()) {
            handWriter.postDelayed({ speakFrame(stepIdx, frameIdx) }, 600)
        }
    }

    /**
     * Fetches a Wikimedia Commons image for [query] and populates [placeholder].
     *  - serverScore ≥ 0.9  → show inline (full 180 dp image)
     *  - serverScore  < 0.9  → show a small tap-to-view icon button; image opens in a dialog
     *  - if no image found on Wikimedia → show nothing
     */
    private fun fetchAndShowStepImage(query: String, serverScore: Float, placeholder: LinearLayout) {
        if (query.isBlank()) return
        lifecycleScope.launch {
            val url = WikimediaUtils.firstImageUrl(query) ?: return@launch
            val dp = resources.displayMetrics.density

            if (serverScore >= 0.7f) {
                // ── High confidence: show inline ──────────────────────────────────
                val caption = TextView(this@BlackboardActivity).apply {
                    text = "📷 ${query.take(50)}"
                    textSize = 10f
                    setTextColor(android.graphics.Color.parseColor("#88857070"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * dp).toInt() }
                    alpha = 0f
                }
                if (url.lowercase().endsWith(".svg")) {
                    val webView = WebView(this@BlackboardActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (180 * dp).toInt()
                        ).apply { topMargin = (10 * dp).toInt(); bottomMargin = (4 * dp).toInt() }
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        alpha = 0f
                    }
                    placeholder.addView(webView)
                    placeholder.addView(caption)
                    webView.loadUrl(url)
                    webView.animate().alpha(1f).setDuration(700).start()
                    caption.animate().alpha(1f).setDuration(700).start()
                } else {
                    val imageView = ImageView(this@BlackboardActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (180 * dp).toInt()
                        ).apply { topMargin = (10 * dp).toInt(); bottomMargin = (4 * dp).toInt() }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0f
                    }
                    placeholder.addView(imageView)
                    placeholder.addView(caption)
                    Glide.with(this@BlackboardActivity)
                        .load(url)
                        .transform(RoundedCorners((12 * dp).toInt()))
                        .into(imageView)
                    imageView.animate().alpha(1f).setDuration(700).start()
                    caption.animate().alpha(1f).setDuration(700).start()
                }
                stepsScrollView.postDelayed(
                    { stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 400
                )
            } else {
                // ── Low confidence: icon button only ──────────────────────────────
                val refBtn = TextView(this@BlackboardActivity).apply {
                    text = "🖼  For your reference  •  tap to view"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#70C8E8"))
                    gravity = Gravity.CENTER
                    setPadding(
                        (16 * dp).toInt(), (7 * dp).toInt(),
                        (16 * dp).toInt(), (7 * dp).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 20 * dp
                        setColor(Color.parseColor("#1A2E3A"))
                        setStroke((1 * dp).toInt(), Color.parseColor("#3040E0D0"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = (10 * dp).toInt()
                        bottomMargin = (8 * dp).toInt()
                    }
                    alpha = 0f
                    setOnClickListener { showImageDialog(url, query) }
                }
                placeholder.addView(refBtn)
                refBtn.animate().alpha(1f).setDuration(500).start()
            }
        }
    }

    /** Opens [url] in a simple AlertDialog so the user can inspect the reference image. */
    private fun showImageDialog(url: String, caption: String) {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        if (url.lowercase().endsWith(".svg")) {
            val webView = WebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (300 * dp).toInt()
                )
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = false
            }
            container.addView(webView)
            webView.loadUrl(url)
        } else {
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (300 * dp).toInt()
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            Glide.with(this).load(url).into(imageView)
            container.addView(imageView)
        }
        val captionView = TextView(this).apply {
            text = "📷 ${caption.take(80)}"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() }
        }
        container.addView(captionView)
        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun speakFrame(stepIdx: Int, frameIdx: Int) {
        if (currentStepIdx != stepIdx || currentFrameIdx != frameIdx) return
        val step  = steps.getOrNull(stepIdx) ?: return
        val frame = step.frames.getOrNull(frameIdx) ?: return
        tts.setLocale(Locale.forLanguageTag(step.languageTag))
        tts.speak(frame.speech, makeTtsCallback(stepIdx, frameIdx))
    }

    /** Advance to next frame in current step, or first frame of next step. */
    private fun advanceFrame() {
        if (isPaused) return
        val step = steps.getOrNull(currentStepIdx) ?: return
        when {
            currentFrameIdx < step.frames.size - 1 ->
                showFrame(currentStepIdx, currentFrameIdx + 1)
            currentStepIdx < steps.size - 1 ->
                showFrame(currentStepIdx + 1, 0)
        }
    }

    private fun nextStep() = advanceFrame()

    private fun prevStep() {
        tts.stop()
        typeAnimator?.cancel()
        handWriter.visibility = View.INVISIBLE
        when {
            currentFrameIdx > 0 -> {
                // To safely go back, clear the whole board and fast-forward to the previous frame
                rebuildBoardUpTo(currentStepIdx, currentFrameIdx - 1)
            }
            currentStepIdx > 0 -> {
                val prevFrames = steps[currentStepIdx - 1].frames
                rebuildBoardUpTo(currentStepIdx - 1, prevFrames.size - 1)
            }
            else -> reSpeakCurrent()
        }
    }

    /** 
     * Rebuilds the entire visual state of the blackboard instantly up to a specific frame,
     * then triggers the playback for that target frame. 
     */
    private fun rebuildBoardUpTo(targetStepIdx: Int, targetFrameIdx: Int) {
        val board = boardLayout ?: return
        val dp = resources.displayMetrics.density
        val caveatFont = ResourcesCompat.getFont(this, R.font.kalam)
        
        board.removeAllViews()

        for (s in 0..targetStepIdx) {
            val step = steps[s]
            val maxFrame = if (s == targetStepIdx) targetFrameIdx - 1 else step.frames.size - 1

            // Append title if we're showing at least one frame of this step (which we always are if we're in the loop)
            val titleWrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (s > 0) (32 * dp).toInt() else 0 }
            }
            val titleText = if (step.title.isNotBlank()) "Step ${s + 1}  ·  ${step.title}"
                            else "Step ${s + 1}"
            val titleView = TextView(this).apply {
                text = titleText
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#F5E3A0"))
                typeface = Typeface.create(caveatFont, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            titleWrapper.addView(titleView)

            val separator = View(this).apply {
                setBackgroundColor(Color.parseColor("#8BAB8B"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                ).apply { topMargin = (10 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
            }
            titleWrapper.addView(separator)
            board.addView(titleWrapper)

            // Append all previous frames instantly (no animation)
            for (f in 0..maxFrame) {
                val frame = step.frames[f]
                val contentText = TextView(this).apply {
                    text = buildFrameText(frame.text, frame.highlight)
                    textSize = computedFontSp
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#F0EDD0"))
                    setLineSpacing(0f, 1.6f)
                    typeface = caveatFont
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (16 * dp).toInt() }
                }
                board.addView(contentText)
            }
        }

        // Finally run `showFrame` for the target frame so it animates in and speaks
        showFrame(targetStepIdx, targetFrameIdx)
    }

    private fun reSpeakCurrent() {
        tts.stop()
        if (!isPaused) speakFrame(currentStepIdx, currentFrameIdx)
    }

    private fun togglePause() {
        isPaused = !isPaused
        pauseBtn.text = if (isPaused) "▶" else "⏸"
        if (isPaused) {
            tts.stop()
        } else {
            speakFrame(currentStepIdx, currentFrameIdx)
        }
    }

    private fun updateCounterAndDots() {
        stepCounter.text = "${currentStepIdx + 1} / ${steps.size}"
        updateDots(currentStepIdx)
        val atStart = currentStepIdx == 0 && currentFrameIdx == 0
        val atEnd   = currentStepIdx == steps.size - 1 &&
                      currentFrameIdx == (steps.lastOrNull()?.frames?.size ?: 1) - 1
        prevBtn.alpha = if (atStart) 0.30f else 1f
        nextBtn.alpha = if (atEnd)   0.30f else 1f
    }

    // ── Frame text rendering ──────────────────────────────────────────────────

    /** Parse markdown then overlay highlight spans for emphasized words/digits. */
    private fun buildFrameText(text: String, highlights: List<String>): SpannableStringBuilder {
        val ssb = parseMarkdownForBlackboard(text)
        if (highlights.isEmpty()) return ssb
        val str = ssb.toString()
        highlights.forEach { hl ->
            if (hl.isBlank()) return@forEach
            var start = 0
            while (true) {
                val idx = str.indexOf(hl, start, ignoreCase = false)
                if (idx < 0) break
                val end = idx + hl.length
                ssb.setSpan(ForegroundColorSpan(Color.parseColor("#FFEB3B")), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(StyleSpan(Typeface.BOLD), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(RelativeSizeSpan(1.2f), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                start = end
            }
        }
        return ssb
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

    private fun makeTtsCallback(stepIdx: Int, frameIdx: Int) = object : TTSCallback {

        override fun onStart() { /* no-op */ }

        override fun onComplete() {
            // Only auto-advance if user hasn't already navigated away
            if (!isPaused && currentStepIdx == stepIdx && currentFrameIdx == frameIdx) {
                stepsScrollView.postDelayed({ advanceFrame() }, 300)
            }
        }

        override fun onError(error: String) {
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
                i < active  -> Color.parseColor("#9FA99F")   // revealed (past) — chalk gray
                i == active -> Color.parseColor("#D4C060")   // current — chalk yellow
                else        -> Color.parseColor("#4A5549")   // not yet shown — faint chalk
            }
            (dotsContainer.getChildAt(i).background as? GradientDrawable)?.setColor(color)
        }
    }

    private fun makeDotDrawable(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (active) Color.parseColor("#D4C060") else Color.parseColor("#4A5549"))
    }
}

