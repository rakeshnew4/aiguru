package com.aiguruapp.student.bb

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.aiguruapp.student.R
import com.aiguruapp.student.auth.TokenManager
import com.aiguruapp.student.chat.BlackboardGenerator.BlackboardFrame
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Shows an interactive quiz popup for BB mode interactive frame types.
 * Supports:  quiz_mcq   — 4-option MCQ, immediate local validation
 *            quiz_typed — typed open answer, AI-graded via /bb/grade
 *            quiz_voice — spoken answer  , AI-graded via /bb/grade
 *            quiz_fill  — fill-in-the-blank with per-blank EditTexts
 *            quiz_order — tap-to-order shuffled steps
 *
 * A confidence meter is shown before every quiz popup.
 */
object BbInteractivePopup {

    data class QuizResult(
        val correct: Boolean,
        val score: Int = 0,
        val feedback: String = ""
    )

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    // Shared OkHttp client (single instance)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun show(
        activity: Activity,
        frame: BlackboardFrame,
        serverUrl: String,
        languageTag: String = "en-US",
        onResult: (QuizResult) -> Unit,
        /**
         * Called immediately after the quiz dialog is shown, with a lambda that the
         * caller can invoke later to start the countdown timer.
         * If null, the timer starts automatically when the dialog opens.
         */
        onTimerStart: ((startTimer: () -> Unit) -> Unit)? = null
    ) {
        val quizTypes = setOf("quiz_mcq", "quiz_typed", "quiz_voice", "quiz_fill", "quiz_order")
        if (frame.frameType !in quizTypes) { onResult(QuizResult(true)); return }

        // Guard: ensure onResult is called at most once, regardless of which path fires
        // (skip button, timer expiry, and continue button can all race each other).
        var resultDelivered = false
        val safeResult: (QuizResult) -> Unit = { result ->
            if (!resultDelivered) {
                resultDelivered = true
                onResult(result)
            }
        }

        when (frame.frameType) {
            "quiz_mcq"             -> showMcq(activity, frame, safeResult, onTimerStart)
            "quiz_typed",
            "quiz_voice"           -> showTyped(activity, frame, serverUrl, safeResult, onTimerStart)
            "quiz_fill"            -> showFillBlank(activity, frame, safeResult, onTimerStart)
            "quiz_order"           -> showOrderSteps(activity, frame, safeResult, onTimerStart)
            else                   -> safeResult(QuizResult(true))
        }
    }

    // ── Confidence meter ──────────────────────────────────────────────────────

    /**
     * Shows a brief pre-quiz confidence check. Calls [onReady] once the user
     * picks a confidence level (or after a short timeout).
     */
    fun showConfidenceMeter(activity: Activity, onReady: (confidence: Int) -> Unit) {
        val dp     = activity.resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(activity, R.font.kalam)

        val root = buildRootLayout(activity)

        root.addView(textView(activity, "Before you answer…", 14f, "#88857070", caveat).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (6 * dp).toInt() }
        })
        root.addView(textView(activity, "How confident are you?", 17f, "#F0EDD0", caveat).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (20 * dp).toInt() }
        })

        val dialog = buildDialog(activity, root)

        fun pick(confidence: Int) { dialog.dismiss(); onReady(confidence) }

        data class Btn(val label: String, val color: String, val value: Int)
        listOf(
            Btn("🟢  I know this!",    "#2E7D32", 3),
            Btn("🟡  Not sure…",       "#F9A825", 2),
            Btn("🔴  Just guessing",   "#B71C1C", 1)
        ).forEach { btn ->
            root.addView(textView(activity, btn.label, 15f, "#F0EDD0", caveat).apply {
                gravity = Gravity.CENTER
                background = roundedBorder(activity, btn.color, 12f, fill = true).apply {
                    alpha = 200
                }
                setPadding((16 * dp).toInt(), (13 * dp).toInt(), (16 * dp).toInt(), (13 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (10 * dp).toInt() }
                setOnClickListener { pick(btn.value) }
            })
        }

        dialog.show()
    }

    // ── MCQ ────────────────────────────────────────────────────────────────────

    private fun showMcq(
        activity: Activity,
        frame: BlackboardFrame,
        onResult: (QuizResult) -> Unit,
        onTimerStart: ((startTimer: () -> Unit) -> Unit)? = null
    ) {
        val dp     = activity.resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(activity, R.font.kalam)

        val root = buildRootLayout(activity)

        val timerTv = buildTimerLabel(activity, caveat)
        root.addView(timerTv)

        // Question
        root.addView(textView(activity, "❓  ${frame.text}", 17f, "#F0EDD0", caveat).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (20 * dp).toInt() }
        })

        val feedbackTv = textView(activity, "", 14f, "#69F0AE", caveat).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin    = (4 * dp).toInt()
                bottomMargin = (12 * dp).toInt()
            }
        }

        val continueBtn = actionButton(activity, "Continue →", dp, caveat)
        continueBtn.visibility = View.GONE

        var answered = false
        var timer: CountDownTimer? = null

        frame.quizOptions.forEachIndexed { idx, option ->
            val label = listOf("A", "B", "C", "D").getOrElse(idx) { "${idx + 1}" }
            val optBtn = textView(activity, "$label.  $option", 15f, "#F0EDD0", caveat).apply {
                background = roundedBorder(activity, "#5C5BD4", 12f)
                setPadding((16 * dp).toInt(), (13 * dp).toInt(), (16 * dp).toInt(), (13 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = (10 * dp).toInt()
                }
            }
            root.addView(optBtn)

            optBtn.setOnClickListener {
                if (answered) return@setOnClickListener
                answered = true
                timer?.cancel()
                timerTv.visibility = View.GONE
                val correct = idx == frame.quizCorrectIndex
                val bg = if (correct) "#2E7D32" else "#B71C1C"
                optBtn.background = roundedBorder(activity, bg, 12f, fill = true)
                if (correct) {
                    feedbackTv.text = "✅  Correct!"
                    feedbackTv.setTextColor(Color.parseColor("#69F0AE"))
                } else {
                    val correctText = frame.quizOptions.getOrNull(frame.quizCorrectIndex) ?: ""
                    feedbackTv.text = "❌  Wrong — correct answer: $correctText"
                    feedbackTv.setTextColor(Color.parseColor("#FF8A80"))
                }
                feedbackTv.visibility = View.VISIBLE
                continueBtn.visibility = View.VISIBLE
                val dialog = optBtn.tag as? Dialog
                continueBtn.setOnClickListener {
                    dialog?.dismiss()
                    onResult(QuizResult(correct = correct, score = if (correct) 100 else 0))
                }
            }
        }

        root.addView(feedbackTv)
        root.addView(continueBtn)
        root.addView(skipButton(activity, dp, caveat) { dialog.dismiss(); onResult(QuizResult(false, 0, "Skipped")) })

        val dialog = buildDialog(activity, root)
        // Store dialog ref on each option button for the click handler
        val container = root
        for (i in 0 until container.childCount) {
            container.getChildAt(i).tag = dialog
        }

        val startTimerFn = {
            if (!answered) {
                timer = startQuizTimer(timerTv, onTick = null) {
                    if (!answered) {
                        answered = true
                        dialog.dismiss()
                        onResult(QuizResult(false, 0, "Time's up"))
                    }
                }
            }
        }
        dialog.setOnDismissListener { timer?.cancel() }
        if (onTimerStart != null) onTimerStart(startTimerFn) else startTimerFn()
        dialog.show()
    }

    private fun showTyped(
        activity: Activity,
        frame: BlackboardFrame,
        serverUrl: String,
        onResult: (QuizResult) -> Unit,
        onTimerStart: ((startTimer: () -> Unit) -> Unit)? = null
    ) {
        val dp     = activity.resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(activity, R.font.kalam)

        val root = buildRootLayout(activity)

        val timerTv = buildTimerLabel(activity, caveat)
        root.addView(timerTv)

        root.addView(textView(activity, "✏️  ${frame.text}", 17f, "#F0EDD0", caveat).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (16 * dp).toInt() }
        })

        val editText = EditText(activity).apply {
            hint = "Type your answer here…"
            textSize = 15f
            setTextColor(Color.parseColor("#F0EDD0"))
            setHintTextColor(Color.parseColor("#88857070"))
            typeface = caveat
            setBackgroundColor(Color.parseColor("#0D1F0D"))
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            gravity = Gravity.TOP
            minLines = 3
            maxLines = 6
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        root.addView(editText)

        val progressBar = ProgressBar(activity).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (8 * dp).toInt()
            }
        }
        root.addView(progressBar)

        val feedbackTv = textView(activity, "", 14f, "#69F0AE", caveat).apply {
            visibility = View.GONE
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin    = (12 * dp).toInt()
                bottomMargin = (8 * dp).toInt()
            }
        }
        root.addView(feedbackTv)

        val actionBtn = actionButton(activity, "Check Answer", dp, caveat)
        root.addView(actionBtn)
        root.addView(skipButton(activity, dp, caveat) { dialog.dismiss(); onResult(QuizResult(false, 0, "Skipped")) })

        var gradeResult: QuizResult? = null
        var timer: CountDownTimer? = null
        val dialog = buildDialog(activity, ScrollView(activity).apply { addView(root) })

        actionBtn.setOnClickListener {
            val answer = editText.text.toString().trim()
            if (answer.isBlank()) return@setOnClickListener

            if (gradeResult != null) {
                dialog.dismiss()
                onResult(gradeResult!!)
                return@setOnClickListener
            }

            timer?.cancel()
            timerTv.visibility = View.GONE
            editText.isEnabled = false
            progressBar.visibility = View.VISIBLE
            actionBtn.isEnabled = false
            actionBtn.alpha = 0.5f

            gradeAnswer(serverUrl, frame.text, answer, frame.quizModelAnswer, frame.quizKeywords) { result ->
                activity.runOnUiThread {
                    progressBar.visibility = View.GONE
                    gradeResult = result
                    val color = if (result.correct) "#69F0AE" else "#FF8A80"
                    feedbackTv.text = "${if (result.correct) "✅" else "❌"}  ${result.feedback}"
                    feedbackTv.setTextColor(Color.parseColor(color))
                    feedbackTv.visibility = View.VISIBLE
                    actionBtn.text = "Continue →"
                    actionBtn.isEnabled = true
                    actionBtn.alpha = 1f
                }
            }
        }

        val startTimerFn = {
            if (gradeResult == null) {
                timer = startQuizTimer(timerTv, onTick = null) {
                    if (gradeResult == null) {
                        dialog.dismiss()
                        onResult(QuizResult(false, 0, "Time's up"))
                    }
                }
            }
        }
        dialog.setOnDismissListener { timer?.cancel() }
        if (onTimerStart != null) onTimerStart(startTimerFn) else startTimerFn()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ── Voice answer ─────────────────────────────────────────────────────────

    private fun showVoice(
        activity: Activity,
        frame: BlackboardFrame,
        serverUrl: String,
        languageTag: String,
        onResult: (QuizResult) -> Unit
    ) {
        val dp     = activity.resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(activity, R.font.kalam)

        val root = buildRootLayout(activity)

        root.addView(textView(activity, "🎙️  ${frame.text}", 17f, "#F0EDD0", caveat).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (20 * dp).toInt() }
        })

        val timerTv = buildTimerLabel(activity, caveat)
        root.addView(timerTv)

        val statusTv = textView(activity, "🎙️ Starting mic…", 13f, "#88857070", caveat).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (12 * dp).toInt() }
        }
        root.addView(statusTv)

        val transcriptTv = textView(activity, "", 15f, "#F0EDD0", caveat).apply {
            setBackgroundColor(Color.parseColor("#0D1F0D"))
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (12 * dp).toInt() }
        }
        root.addView(transcriptTv)

        // Animated mic indicator (pulse ring around the mic emoji)
        val micBtn = textView(activity, "🎙️", 44f, "#69F0AE", null).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (8 * dp).toInt() }
        }
        root.addView(micBtn)

        val progressBar = ProgressBar(activity).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        root.addView(progressBar)

        val feedbackTv = textView(activity, "", 14f, "#69F0AE", caveat).apply {
            visibility = View.GONE
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin    = (12 * dp).toInt()
                bottomMargin = (8 * dp).toInt()
            }
        }
        root.addView(feedbackTv)

        val continueBtn = actionButton(activity, "Continue →", dp, caveat).apply { visibility = View.GONE }
        root.addView(continueBtn)
        root.addView(skipButton(activity, dp, caveat) { dialog.dismiss(); onResult(QuizResult(false, 0, "Skipped")) })

        var gradeResult: QuizResult? = null
        var recognizer: SpeechRecognizer? = null
        var voiceTimer: CountDownTimer? = null

        val dialog = buildDialog(activity, ScrollView(activity).apply { addView(root) })
        dialog.setOnDismissListener {
            recognizer?.destroy()
            voiceTimer?.cancel()
        }

        fun startListening() {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    activity.runOnUiThread {
                        statusTv.text = "🔴 Listening… speak now"
                        micBtn.setTextColor(Color.parseColor("#FF5252"))
                        // Start 10-second countdown while listening
                        voiceTimer?.cancel()
                        voiceTimer = startQuizTimer(timerTv, onTick = null) {
                            // Time up — stop recognizer, it will fire onResults or onError
                            recognizer?.stopListening()
                        }
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {
                    activity.runOnUiThread {
                        voiceTimer?.cancel()
                        timerTv.visibility = View.GONE
                        statusTv.text = "Processing…"
                        micBtn.setTextColor(Color.parseColor("#F0EDD0"))
                    }
                }
                override fun onPartialResults(r: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()

                    activity.runOnUiThread {
                        transcriptTv.text = "\"$transcript\""
                        transcriptTv.visibility = View.VISIBLE
                        progressBar.visibility = View.VISIBLE
                        micBtn.alpha = 0.4f
                        statusTv.visibility = View.GONE
                    }

                    gradeAnswer(serverUrl, frame.text, transcript, frame.quizModelAnswer, frame.quizKeywords) { result ->
                        activity.runOnUiThread {
                            gradeResult = result
                            progressBar.visibility = View.GONE
                            val color = if (result.correct) "#69F0AE" else "#FF8A80"
                            feedbackTv.text = "${if (result.correct) "✅" else "❌"}  ${result.feedback}"
                            feedbackTv.setTextColor(Color.parseColor(color))
                            feedbackTv.visibility = View.VISIBLE
                            continueBtn.visibility = View.VISIBLE
                        }
                    }
                }

                override fun onError(error: Int) {
                    activity.runOnUiThread {
                        voiceTimer?.cancel()
                        timerTv.visibility = View.GONE
                        statusTv.text = "Couldn't hear — tap 🎙️ to retry"
                        statusTv.visibility = View.VISIBLE
                        micBtn.alpha = 1f
                        micBtn.setTextColor(Color.parseColor("#F0EDD0"))
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }
            recognizer?.startListening(intent)
        }

        // Tap mic to retry after error
        micBtn.setOnClickListener {
            if (gradeResult == null) startListening()
        }
        continueBtn.setOnClickListener {
            dialog.dismiss()
            onResult(gradeResult ?: QuizResult(false))
        }

        dialog.show()
        // Auto-start mic after a short delay so the dialog is fully laid out
        micBtn.postDelayed({ startListening() }, 400)
    }

    // ── Fill-in-the-blank ────────────────────────────────────────────────────

    private fun showFillBlank(
        activity: Activity,
        frame: BlackboardFrame,
        onResult: (QuizResult) -> Unit,
        onTimerStart: ((startTimer: () -> Unit) -> Unit)? = null
    ) {
        val dp     = activity.resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(activity, R.font.kalam)

        val root = buildRootLayout(activity)
        val timerTv = buildTimerLabel(activity, caveat)
        root.addView(timerTv)

        // Render text with blanks highlighted and EditTexts below each blank label
        val blanksCount = frame.fillBlanks.size.coerceAtLeast(1)
        // Replace each [_] placeholder in the text with a numbered blank marker
        var displayText = frame.text
        for (i in 1..blanksCount) {
            displayText = displayText.replaceFirst("___", "(${i})")
        }

        root.addView(textView(activity, "📝  $displayText", 16f, "#F0EDD0", caveat).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (16 * dp).toInt() }
        })

        val editTexts = mutableListOf<EditText>()
        for (i in 0 until blanksCount) {
            root.addView(textView(activity, "Blank ${i + 1}:", 13f, "#88857070", caveat).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (8 * dp).toInt() }
            })
            val et = EditText(activity).apply {
                hint = "Fill in blank ${i + 1}"
                textSize = 15f
                setTextColor(Color.parseColor("#F0EDD0"))
                setHintTextColor(Color.parseColor("#88857070"))
                typeface = caveat
                setBackgroundColor(Color.parseColor("#0D1F0D"))
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (4 * dp).toInt() }
            }
            root.addView(et)
            editTexts.add(et)
        }

        val feedbackTv = textView(activity, "", 14f, "#69F0AE", caveat).apply {
            visibility = View.GONE
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin    = (12 * dp).toInt()
                bottomMargin = (8 * dp).toInt()
            }
        }
        root.addView(feedbackTv)

        val actionBtn = actionButton(activity, "Check Answer", dp, caveat)
        root.addView(actionBtn)
        root.addView(skipButton(activity, dp, caveat) { dialog.dismiss(); onResult(QuizResult(false, 0, "Skipped")) })

        var checked = false
        var timer: CountDownTimer? = null
        val dialog = buildDialog(activity, ScrollView(activity).apply { addView(root) })

        actionBtn.setOnClickListener {
            if (checked) { dialog.dismiss(); return@setOnClickListener }
            timer?.cancel()
            timerTv.visibility = View.GONE

            val answers = editTexts.map { it.text.toString().trim() }
            val corrects = frame.fillBlanks
            var allCorrect = true
            val feedback = StringBuilder()

            corrects.forEachIndexed { i, expected ->
                val given = answers.getOrElse(i) { "" }
                val ok = given.equals(expected, ignoreCase = true) ||
                         given.lowercase().contains(expected.lowercase())
                if (!ok) {
                    allCorrect = false
                    feedback.append("Blank ${i + 1}: expected \"$expected\"\n")
                }
            }

            checked = true
            editTexts.forEach { it.isEnabled = false }

            if (allCorrect) {
                feedbackTv.text = "✅  All blanks correct! Well done."
                feedbackTv.setTextColor(Color.parseColor("#69F0AE"))
            } else {
                feedbackTv.text = "❌  Not quite:\n${feedback.toString().trimEnd()}"
                feedbackTv.setTextColor(Color.parseColor("#FF8A80"))
            }
            feedbackTv.visibility = View.VISIBLE
            actionBtn.text = "Continue →"

            val score = if (allCorrect) 100
                        else (answers.zip(corrects).count { (a, e) ->
                            a.equals(e, ignoreCase = true) || a.lowercase().contains(e.lowercase())
                        } * 100 / corrects.size.coerceAtLeast(1))

            actionBtn.setOnClickListener {
                dialog.dismiss()
                onResult(QuizResult(correct = allCorrect, score = score,
                    feedback = if (allCorrect) "All blanks correct!" else feedback.toString().trimEnd()))
            }
        }

        val startTimerFn = {
            if (!checked) {
                timer = startQuizTimer(timerTv, null) {
                    if (!checked) {
                        checked = true
                        dialog.dismiss()
                        onResult(QuizResult(false, 0, "Time's up"))
                    }
                }
            }
        }
        dialog.setOnDismissListener { timer?.cancel() }
        if (onTimerStart != null) onTimerStart(startTimerFn) else startTimerFn()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ── Order steps ──────────────────────────────────────────────────────────

    private fun showOrderSteps(
        activity: Activity,
        frame: BlackboardFrame,
        onResult: (QuizResult) -> Unit,
        onTimerStart: ((startTimer: () -> Unit) -> Unit)? = null
    ) {
        val dp     = activity.resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(activity, R.font.kalam)

        val root = buildRootLayout(activity)
        val timerTv = buildTimerLabel(activity, caveat)
        root.addView(timerTv)
        var orderTimer: CountDownTimer? = null

        root.addView(textView(activity, "🔢  Tap the steps in the correct order:", 17f, "#F0EDD0", caveat).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (6 * dp).toInt() }
        })
        root.addView(textView(activity, frame.text, 14f, "#88857070", caveat).apply {
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (16 * dp).toInt() }
        })

        val stepTexts = frame.quizOptions  // shuffled texts from LLM
        val correctOrder = frame.quizCorrectOrder  // indices into stepTexts mapping to correct positions

        val selectedOrder = mutableListOf<Int>()  // indices in stepTexts in tap-order

        val stepBtns = mutableListOf<TextView>()
        val feedbackTv = textView(activity, "", 14f, "#69F0AE", caveat).apply {
            visibility = View.GONE
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin    = (12 * dp).toInt()
                bottomMargin = (8 * dp).toInt()
            }
        }

        stepTexts.forEachIndexed { idx, stepText ->
            val btn = textView(activity, stepText, 14f, "#F0EDD0", caveat).apply {
                background = roundedBorder(activity, "#5C5BD4", 10f)
                setPadding((14 * dp).toInt(), (11 * dp).toInt(), (14 * dp).toInt(), (11 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = (8 * dp).toInt() }
            }
            root.addView(btn)
            stepBtns.add(btn)

            btn.setOnClickListener {
                if (idx in selectedOrder || feedbackTv.visibility == View.VISIBLE) return@setOnClickListener
                selectedOrder.add(idx)
                val pos = selectedOrder.size
                btn.text = "${pos}. $stepText"
                btn.background = roundedBorder(activity, "#F9A825", 10f, fill = false)

                // All steps tapped — evaluate
                if (selectedOrder.size == stepTexts.size) {
                    val isCorrect = if (correctOrder.size == stepTexts.size) {
                        selectedOrder == correctOrder
                    } else {
                        // Fallback: treat selected order as indices 0..n-1
                        selectedOrder.mapIndexed { i, v -> v == i }.all { it }
                    }

                    stepBtns.forEachIndexed { i, b ->
                        val tappedPos = selectedOrder.indexOf(i)
                        val expectedPos = if (correctOrder.size == stepTexts.size) correctOrder.indexOf(i) else i
                        val stepOk = tappedPos == expectedPos
                        b.background = roundedBorder(activity, if (stepOk) "#2E7D32" else "#B71C1C", 10f, fill = true)
                    }

                    if (isCorrect) {
                        feedbackTv.text = "✅  Perfect order! Well done."
                        feedbackTv.setTextColor(Color.parseColor("#69F0AE"))
                    } else {
                        feedbackTv.text = "❌  Not quite. Review the highlighted steps."
                        feedbackTv.setTextColor(Color.parseColor("#FF8A80"))
                    }
                    feedbackTv.visibility = View.VISIBLE
                    orderTimer?.cancel()
                    timerTv.visibility = View.GONE
                }
            }
        }

        root.addView(feedbackTv)
        val continueBtn = actionButton(activity, "Continue →", dp, caveat).apply { visibility = View.GONE }
        root.addView(continueBtn)
        root.addView(skipButton(activity, dp, caveat) { dialog.dismiss(); onResult(QuizResult(false, 0, "Skipped")) })

        val dialog = buildDialog(activity, ScrollView(activity).apply { addView(root) })

        // Show continue button once all steps are tapped
        feedbackTv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (feedbackTv.visibility == View.VISIBLE && continueBtn.visibility == View.GONE) {
                continueBtn.visibility = View.VISIBLE
                val isCorrect = if (correctOrder.size == stepTexts.size)
                    selectedOrder == correctOrder
                else
                    selectedOrder.mapIndexed { i, v -> v == i }.all { it }
                continueBtn.setOnClickListener {
                    dialog.dismiss()
                    onResult(QuizResult(
                        correct  = isCorrect,
                        score    = if (isCorrect) 100 else 40,
                        feedback = if (isCorrect) "Steps in correct order!" else "Order wasn't quite right."
                    ))
                }
            }
        }

        val startTimerFn = {
            if (feedbackTv.visibility != View.VISIBLE) {
                orderTimer = startQuizTimer(timerTv, null) {
                    if (feedbackTv.visibility != View.VISIBLE) {
                        dialog.dismiss()
                        onResult(QuizResult(false, 0, "Time's up"))
                    }
                }
            }
        }
        dialog.setOnDismissListener { orderTimer?.cancel() }
        if (onTimerStart != null) onTimerStart(startTimerFn) else startTimerFn()
        dialog.show()
    }

    internal fun gradeAnswer(
        serverUrl: String,
        question: String,
        studentAnswer: String,
        modelAnswer: String,
        keywords: List<String>,
        callback: (QuizResult) -> Unit
    ) {
        val body = JSONObject().apply {
            put("question", question)
            put("student_answer", studentAnswer)
            put("model_answer", modelAnswer)
            put("keywords", JSONArray(keywords))
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val reqBuilder = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/bb/grade")
            .post(body)
            .header("Content-Type", "application/json")

        val authHeader = TokenManager.buildAuthHeader(false)
        if (authHeader != null) reqBuilder.header("Authorization", authHeader)

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.isSuccessful) {
                    val j = JSONObject(text)
                    callback(
                        QuizResult(
                            correct  = j.optBoolean("correct", false),
                            score    = j.optInt("score", 0),
                            feedback = j.optString("feedback", "")
                        )
                    )
                } else {
                    callback(localKeywordGrade(studentAnswer, modelAnswer, keywords))
                }
            }
        } catch (_: Exception) {
            callback(localKeywordGrade(studentAnswer, modelAnswer, keywords))
        }
    }

    /** Offline fallback: count how many keywords appear in the answer. */
    private fun localKeywordGrade(answer: String, modelAnswer: String, keywords: List<String>): QuizResult {
        if (answer.isBlank()) return QuizResult(false, 0, "No answer given.")
        if (keywords.isEmpty()) {
            return QuizResult(
                correct  = answer.length > 5,
                score    = if (answer.length > 5) 60 else 0,
                feedback = if (answer.length > 5) "Answer noted! Verify accuracy with your teacher."
                           else "Please write a full answer."
            )
        }
        val lower   = answer.lowercase()
        val matched = keywords.count { lower.contains(it.lowercase()) }
        val ratio   = matched.toFloat() / keywords.size
        return when {
            ratio >= 0.75f -> QuizResult(true,  85, "Great answer! You covered the key points. 🎉")
            ratio >= 0.40f -> QuizResult(false, 45, "Partially right. Key idea: $modelAnswer")
            else           -> QuizResult(false, 10, "Not quite. The answer is: $modelAnswer")
        }
    }

    // ── Timer helpers ─────────────────────────────────────────────────────────

    private fun buildTimerLabel(activity: Activity, typeface: android.graphics.Typeface?): TextView {
        val dp = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = "⏱  10s"
            textSize = 13f
            gravity = Gravity.END
            setTextColor(Color.parseColor("#F9A825"))
            if (typeface != null) this.typeface = typeface
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = (4 * dp).toInt()
            }
        }
    }

    private fun startQuizTimer(
        timerTv: TextView,
        onTick: ((secondsLeft: Int) -> Unit)?,
        onFinish: () -> Unit
    ): CountDownTimer {
        val timer = object : CountDownTimer(10_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val s = (millisUntilFinished / 1000).toInt() + 1
                timerTv.text = "⏱  ${s}s"
                timerTv.setTextColor(Color.parseColor(if (s <= 3) "#FF5252" else "#F9A825"))
                onTick?.invoke(s)
            }
            override fun onFinish() {
                timerTv.text = "⏱  0s"
                timerTv.setTextColor(Color.parseColor("#FF5252"))
                timerTv.postDelayed({ timerTv.visibility = View.GONE }, 300)
                onFinish()
            }
        }
        timer.start()
        return timer
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun buildRootLayout(activity: Activity): LinearLayout {
        val dp = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A2B1A"))
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }
    }

    private fun buildDialog(activity: Activity, content: View): Dialog {
        return Dialog(activity, R.style.Theme_BB_QuizDialog).apply {
            setContentView(content)
            setCancelable(false)
            window?.apply {
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                setBackgroundDrawableResource(android.R.color.transparent)
                // Rounded corners via background drawable set on root layout
            }
        }
    }

    private fun textView(
        activity: Activity,
        text: String,
        sizeSp: Float,
        colorHex: String,
        typeface: android.graphics.Typeface?
    ) = TextView(activity).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(Color.parseColor(colorHex))
        if (typeface != null) this.typeface = typeface
        setLineSpacing(0f, 1.4f)
    }

    private fun actionButton(activity: Activity, label: String, dp: Float, typeface: android.graphics.Typeface?) =
        TextView(activity).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1A0A"))
            background = roundedBorder(activity, "#F5E3A0", 20f, fill = true)
            if (typeface != null) this.typeface = typeface
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (12 * dp).toInt() }
        }

    private fun skipButton(activity: Activity, dp: Float, typeface: android.graphics.Typeface?, onClick: () -> Unit) =
        TextView(activity).apply {
            text = "Skip"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#88857070"))
            if (typeface != null) this.typeface = typeface
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (4 * dp).toInt() }
            setOnClickListener { onClick() }
        }

    private fun roundedBorder(
        activity: Activity,
        colorHex: String,
        radiusDp: Float,
        fill: Boolean = false
    ): GradientDrawable {
        val dp = activity.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * dp
            if (fill) {
                setColor(Color.parseColor(colorHex))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke((1.5 * dp).toInt(), Color.parseColor(colorHex))
            }
        }
    }
}
