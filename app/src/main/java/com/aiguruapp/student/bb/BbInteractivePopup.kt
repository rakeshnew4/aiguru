package com.aiguruapp.student.bb

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
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
        onResult: (QuizResult) -> Unit
    ) {
        when (frame.frameType) {
            "quiz_mcq"   -> showMcq(activity, frame, onResult)
            "quiz_typed" -> showTyped(activity, frame, serverUrl, onResult)
            "quiz_voice" -> showVoice(activity, frame, serverUrl, languageTag, onResult)
            else         -> onResult(QuizResult(true))
        }
    }

    // ── MCQ ────────────────────────────────────────────────────────────────────

    private fun showMcq(
        activity: Activity,
        frame: BlackboardFrame,
        onResult: (QuizResult) -> Unit
    ) {
        val dp     = activity.resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(activity, R.font.kalam)

        val root = buildRootLayout(activity)

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
        root.addView(skipButton(activity, dp, caveat) { onResult(QuizResult(false, 0, "Skipped")) })

        val dialog = buildDialog(activity, root)
        // Store dialog ref on each option button for the click handler
        val container = root
        for (i in 0 until container.childCount) {
            container.getChildAt(i).tag = dialog
        }
        dialog.show()
    }

    // ── Typed answer ─────────────────────────────────────────────────────────

    private fun showTyped(
        activity: Activity,
        frame: BlackboardFrame,
        serverUrl: String,
        onResult: (QuizResult) -> Unit
    ) {
        val dp     = activity.resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(activity, R.font.kalam)

        val root = buildRootLayout(activity)

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
        root.addView(skipButton(activity, dp, caveat) { onResult(QuizResult(false, 0, "Skipped")) })

        var gradeResult: QuizResult? = null
        val dialog = buildDialog(activity, ScrollView(activity).apply { addView(root) })

        actionBtn.setOnClickListener {
            val answer = editText.text.toString().trim()
            if (answer.isBlank()) return@setOnClickListener

            if (gradeResult != null) {
                dialog.dismiss()
                onResult(gradeResult!!)
                return@setOnClickListener
            }

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

        val statusTv = textView(activity, "Tap 🎤 to speak", 13f, "#88857070", caveat).apply {
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

        val micBtn = textView(activity, "🎤", 40f, "#F0EDD0", null).apply {
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
        root.addView(skipButton(activity, dp, caveat) { onResult(QuizResult(false, 0, "Skipped")) })

        var gradeResult: QuizResult? = null
        var recognizer: SpeechRecognizer? = null

        val dialog = buildDialog(activity, ScrollView(activity).apply { addView(root) })
        dialog.setOnDismissListener { recognizer?.destroy() }

        fun startListening() {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    activity.runOnUiThread { statusTv.text = "Listening… speak now" }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {
                    activity.runOnUiThread { statusTv.text = "Processing…" }
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
                        statusTv.text = "Couldn't hear you — tap 🎤 to retry"
                        statusTv.visibility = View.VISIBLE
                        micBtn.alpha = 1f
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_MATCHES, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer?.startListening(intent)
        }

        micBtn.setOnClickListener {
            if (gradeResult == null) startListening()
        }
        continueBtn.setOnClickListener {
            dialog.dismiss()
            onResult(gradeResult ?: QuizResult(false))
        }

        dialog.show()
    }

    // ── AI grading  ──────────────────────────────────────────────────────────

    private fun gradeAnswer(
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
