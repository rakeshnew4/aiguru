package com.example.aiguru

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.remote.creation.random
import androidx.core.animation.ValueAnimator
import androidx.lifecycle.lifecycleScope
import com.example.aiguru.chat.BlackboardGenerator
import com.example.aiguru.utils.TTSCallback
import com.example.aiguru.utils.TextToSpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen "blackboard" step-by-step lesson mode.
 * Launched from a chat message via the "🎯 Explain" button.
 *
 * Flow:
 *   1. Shows a loading state while AI generates up to 5 focused steps.
 *   2. Steps auto-play: keyword text fades in → TTS speaks it → next step.
 *   3. Student can also use ◀ / ▶ / ⏸ / ↺ controls at the bottom.
 */
class BlackboardActivity : AppCompatActivity() {

    companion object {
        /** The full AI message text to convert into steps. */
        const val EXTRA_MESSAGE = "extra_message"
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var loadingGroup:   View
    private lateinit var loadingText:    TextView
    private lateinit var contentGroup:   View
    private lateinit var stepText:       TextView
    private lateinit var stepCounter:    TextView
    private lateinit var dotsContainer:  LinearLayout
    private lateinit var closeBtn:       TextView
    private lateinit var prevBtn:        TextView
    private lateinit var pauseBtn:       TextView
    private lateinit var replayBtn:      TextView
    private lateinit var nextBtn:        TextView
    private lateinit var teacherAvatar:  TeacherAvatarView

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeechManager
    private var steps        = listOf<BlackboardGenerator.BlackboardStep>()
    private var currentIndex = 0
    private var isPaused     = false

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blackboard)

        loadingGroup   = findViewById(R.id.loadingGroup)
        loadingText    = findViewById(R.id.loadingText)
        contentGroup   = findViewById(R.id.contentGroup)
        stepText       = findViewById(R.id.stepText)
        stepCounter    = findViewById(R.id.stepCounter)
        dotsContainer  = findViewById(R.id.dotsContainer)
        closeBtn       = findViewById(R.id.closeButton)
        prevBtn        = findViewById(R.id.prevButton)
        pauseBtn       = findViewById(R.id.pauseButton)
        replayBtn      = findViewById(R.id.replayButton)
        nextBtn        = findViewById(R.id.nextButton)
        teacherAvatar  = findViewById(R.id.teacherAvatar)

        closeBtn.setOnClickListener   { finish() }
        prevBtn.setOnClickListener    { prevStep() }
        nextBtn.setOnClickListener    { nextStep() }
        replayBtn.setOnClickListener  { showStep(currentIndex) }
        pauseBtn.setOnClickListener   { togglePause() }

        tts = TextToSpeechManager(this)

        generateSteps(intent.getStringExtra(EXTRA_MESSAGE) ?: "")
    }

    override fun onDestroy() {
        tts.destroy()
        super.onDestroy()
    }

    // ── Generation ────────────────────────────────────────────────────────────

    private fun generateSteps(message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            BlackboardGenerator.generate(
                messageContent = message,
                onSuccess = { generated ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        steps = generated
                        loadingGroup.visibility = View.GONE
                        contentGroup.visibility = View.VISIBLE
                        buildDots()
                        showStep(0)
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

    // ── Step display ──────────────────────────────────────────────────────────

    private fun showStep(index: Int) {
        if (steps.isEmpty()) return
        currentIndex = index
        tts.stop()

        // Fade out → update text → fade in for a smooth transition
        stepText.animate().alpha(0f).setDuration(150).withEndAction {
            stepText.text = steps[index].text
            stepText.animate().alpha(1f).setDuration(350).start()
        }.start()

        stepCounter.text = "${index + 1} / ${steps.size}"
        updateDots(index)
        prevBtn.alpha = if (index == 0)            0.30f else 1f
        nextBtn.alpha = if (index == steps.size - 1) 0.30f else 1f

        if (!isPaused) {
            // Slight delay so the fade-in finishes before voice starts
            stepText.postDelayed({
                tts.speak(steps[index].speech, makeTtsCallback())
            }, 250)
        }
    }

    private fun nextStep() {
        if (currentIndex < steps.size - 1) showStep(currentIndex + 1)
    }

    private fun prevStep() {
        if (currentIndex > 0) showStep(currentIndex - 1)
    }

    private fun togglePause() {
        isPaused = !isPaused
        pauseBtn.text = if (isPaused) "▶" else "⏸"
        if (isPaused) {
            tts.stop()
            teacherAvatar.setSpeaking(false)
        } else {
            steps.getOrNull(currentIndex)?.let { step ->
                tts.speak(step.speech, makeTtsCallback())
            }
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

//    private fun makeTtsCallback() = object : TTSCallback {
//        override fun onStart() {
//            runOnUiThread { teacherAvatar.setSpeaking(true) }
//        }
//        override fun onComplete() {
//            runOnUiThread { teacherAvatar.setSpeaking(false) }
//            if (!isPaused && currentIndex < steps.size - 1) {
//                // Auto-advance after a short natural pause between steps
//                stepText.postDelayed({ nextStep() }, 700)
//            }
//        }
//        override fun onError(error: String) {
//            runOnUiThread { teacherAvatar.setSpeaking(false) }
//            android.util.Log.w("Blackboard", "TTS: $error")
//        }
//    }

    private fun makeTtsCallback() = object : TTSCallback {

        // 🔊 Fake audio animator (for mouth sync)
        private var fakeAudioAnim: ValueAnimator? = null

        override fun onStart() {
            runOnUiThread {
                teacherAvatar.setSpeaking(true)

                // Start fake audio animation
                fakeAudioAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 120
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE

                    var t = 0f

                    addUpdateListener {
                        t += 0.15f

                        val base = ((Math.sin(t.toDouble()) + 1.0) / 2.0).toFloat()
                        val randomFactor = 0.3f + Math.random().toFloat() * 0.7f

                        val level = base * randomFactor
                        teacherAvatar.updateAudioLevel(level)
                    }

                    start()
                }
            }
        }

        override fun onComplete() {
            runOnUiThread {
                teacherAvatar.setSpeaking(false)

                // Stop audio animation
                fakeAudioAnim?.cancel()
                fakeAudioAnim = null

                // Reset mouth
                teacherAvatar.updateAudioLevel(0f)
            }

            if (!isPaused && currentIndex < steps.size - 1) {
                // More natural delay (instead of fixed 700ms)
                val delay = (500..900).random().toLong()
                stepText.postDelayed({ nextStep() }, delay)
            }
        }

        override fun onError(error: String) {
            runOnUiThread {
                teacherAvatar.setSpeaking(false)

                // Stop audio animation safely
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
            (dotsContainer.getChildAt(i).background as? GradientDrawable)
                ?.setColor(if (i == active) 0xFFFFFFFF.toInt() else 0x33FFFFFF.toInt())
        }
    }

    private fun makeDotDrawable(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (active) 0xFFFFFFFF.toInt() else 0x33FFFFFF.toInt())
    }
}
