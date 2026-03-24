package com.example.aiguru

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.aiguru.models.TutorMode
import com.example.aiguru.models.TutorSession
import com.example.aiguru.streaming.GeminiLiveClient
import com.example.aiguru.utils.PromptRepository
import com.example.aiguru.utils.SessionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONException

/**
 * Full-screen Gemini Live API voice/text conversation activity.
 *
 * Launched from ChatActivity with extras:
 *   - "subjectName"  (String)
 *   - "chapterName"  (String)
 *   - "tutorMode"    (String, TutorMode.name)
 *   - "userId"       (String)
 *
 * State machine: IDLE → CONNECTING → LISTENING → AI_SPEAKING → LISTENING → …
 */
class GeminiLiveActivity : AppCompatActivity() {

    // ── State ─────────────────────────────────────────────────────────────────
    private enum class LiveState { IDLE, CONNECTING, LISTENING, AI_SPEAKING }
    private var state = LiveState.IDLE

    // ── UI refs ───────────────────────────────────────────────────────────────
    private lateinit var toolbar: MaterialToolbar
    private lateinit var modeVoiceBtn: MaterialButton
    private lateinit var modeTextBtn: MaterialButton
    private lateinit var transcriptScrollView: ScrollView
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var voicePanel: LinearLayout
    private lateinit var textPanel: LinearLayout
    private lateinit var liveWave1: View
    private lateinit var liveWave2: View
    private lateinit var liveWave3: View
    private lateinit var liveWave4: View
    private lateinit var liveWave5: View
    private lateinit var liveStateLabel: TextView
    private lateinit var liveMicButton: MaterialButton
    private lateinit var liveTextInput: EditText
    private lateinit var liveSendButton: MaterialButton

    // ── Session data ──────────────────────────────────────────────────────────
    private lateinit var subjectName: String
    private lateinit var chapterName: String
    private lateinit var tutorMode: TutorMode
    private lateinit var userId: String

    // ── Gemini Live client ────────────────────────────────────────────────────
    private var client: GeminiLiveClient? = null
    private var isVoiceMode = true                // true = voice in/out, false = text in/voice out
    private val waveAnimations = mutableListOf<Animation>()

    // ── Wave animation heights (dp) ───────────────────────────────────────────
    private val idleHeights   = listOf(6, 10, 14, 10, 6)
    private val activeHeights = listOf(12, 28, 44, 28, 12)

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        const val EXTRA_SUBJECT   = "subjectName"
        const val EXTRA_CHAPTER   = "chapterName"
        const val EXTRA_MODE      = "tutorMode"
        const val EXTRA_USER_ID   = "userId"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gemini_live)

        subjectName = intent.getStringExtra(EXTRA_SUBJECT) ?: "General"
        chapterName = intent.getStringExtra(EXTRA_CHAPTER) ?: "Study Session"
        tutorMode   = try {
            TutorMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "AUTO")
        } catch (_: IllegalArgumentException) { TutorMode.AUTO }
        userId      = intent.getStringExtra(EXTRA_USER_ID)
            ?: SessionManager.getFirestoreUserId(this)

        PromptRepository.init(this)
        bindViews()
        setupToolbar()
        setupModeToggle()
        setupMicButton()
        setupTextInput()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWaveAnimation()
        client?.disconnect()
        client = null
    }

    override fun onBackPressed() {
        client?.disconnect()
        client = null
        super.onBackPressed()
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        toolbar              = findViewById(R.id.liveToolbar)
        modeVoiceBtn         = findViewById(R.id.modeVoiceBtn)
        modeTextBtn          = findViewById(R.id.modeTextBtn)
        transcriptScrollView = findViewById(R.id.transcriptScrollView)
        transcriptContainer  = findViewById(R.id.transcriptContainer)
        voicePanel           = findViewById(R.id.voicePanel)
        textPanel            = findViewById(R.id.textPanel)
        liveWave1            = findViewById(R.id.liveWave1)
        liveWave2            = findViewById(R.id.liveWave2)
        liveWave3            = findViewById(R.id.liveWave3)
        liveWave4            = findViewById(R.id.liveWave4)
        liveWave5            = findViewById(R.id.liveWave5)
        liveStateLabel       = findViewById(R.id.liveStateLabel)
        liveMicButton        = findViewById(R.id.liveMicButton)
        liveTextInput        = findViewById(R.id.liveTextInput)
        liveSendButton       = findViewById(R.id.liveSendButton)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        toolbar.subtitle = "$subjectName · $chapterName"
        toolbar.setNavigationOnClickListener {
            client?.disconnect()
            client = null
            finish()
        }
    }

    // ── Mode toggle ───────────────────────────────────────────────────────────

    private fun setupModeToggle() {
        modeVoiceBtn.setOnClickListener { switchToVoiceMode() }
        modeTextBtn.setOnClickListener  { switchToTextMode()  }
    }

    private fun switchToVoiceMode() {
        if (isVoiceMode) return
        isVoiceMode = true
        voicePanel.visibility = View.VISIBLE
        textPanel.visibility  = View.GONE
        modeVoiceBtn.setBackgroundColor(getColor(android.R.color.transparent))
        modeVoiceBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1A73E8.toInt())
        modeVoiceBtn.setTextColor(0xFFFFFFFF.toInt())
        modeTextBtn.backgroundTintList  = android.content.res.ColorStateList.valueOf(0xFFE8F0FE.toInt())
        modeTextBtn.setTextColor(0xFF1A73E8.toInt())
    }

    private fun switchToTextMode() {
        if (!isVoiceMode) return
        isVoiceMode = false
        voicePanel.visibility = View.GONE
        textPanel.visibility  = View.VISIBLE
        modeTextBtn.backgroundTintList  = android.content.res.ColorStateList.valueOf(0xFF1A73E8.toInt())
        modeTextBtn.setTextColor(0xFFFFFFFF.toInt())
        modeVoiceBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8F0FE.toInt())
        modeVoiceBtn.setTextColor(0xFF1A73E8.toInt())
    }

    // ── Mic button ────────────────────────────────────────────────────────────

    private fun setupMicButton() {
        liveMicButton.setOnClickListener {
            when (state) {
                LiveState.IDLE -> startLiveSession()
                else           -> endLiveSession()
            }
        }
    }

    private fun startLiveSession() {
        if (!checkAudioPermission()) return
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            Toast.makeText(this, "Gemini API key not set in local.properties", Toast.LENGTH_LONG).show()
            return
        }

        val session = TutorSession(
            studentId = userId,
            subject   = subjectName,
            chapter   = chapterName,
            mode      = tutorMode
        )
        val systemPrompt = TutorController.buildSystemPrompt(session)

        client = GeminiLiveClient(
            apiKey         = apiKey,
            systemPrompt   = systemPrompt,
            voiceName      = "Charon",
            onSetupComplete = {
                runOnUiThread {
                    setState(LiveState.LISTENING)
                    appendTranscript("✅ Connected! Just start speaking…", isSystem = true)
                }
            },
            onTranscript = { text, isUser ->
                runOnUiThread { appendTranscript(text, isUser = isUser) }
            },
            onTurnComplete = {
                runOnUiThread {
                    if (state == LiveState.AI_SPEAKING) {
                        setState(LiveState.LISTENING)
                    }
                }
            },
            onError = { msg ->
                runOnUiThread {
                    setState(LiveState.IDLE)
                    appendTranscript("❌ Error: $msg", isSystem = true)
                    Toast.makeText(this, "Live error: $msg", Toast.LENGTH_LONG).show()
                }
            },
            onDisconnected = {
                runOnUiThread {
                    setState(LiveState.IDLE)
                    appendTranscript("👋 Session ended.", isSystem = true)
                }
            }
        )

        setState(LiveState.CONNECTING)
        client!!.connect()
    }

    private fun endLiveSession() {
        client?.disconnect()
        client = null
        setState(LiveState.IDLE)
    }

    // ── Text input ────────────────────────────────────────────────────────────

    private fun setupTextInput() {
        liveSendButton.setOnClickListener {
            val text = liveTextInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val curClient = client
            if (curClient == null || !curClient.isConnected) {
                // Auto-start session in text mode (mic won't stream)
                startLiveSession()
                // Queue the text after setup — onSetupComplete will fire first
                pendingText = text
            } else {
                sendTextToGemini(text)
            }
            liveTextInput.setText("")
        }
    }

    private var pendingText: String? = null

    private fun sendTextToGemini(text: String) {
        appendTranscript(text, isUser = true)
        setState(LiveState.AI_SPEAKING)
        client?.sendText(text)
    }

    // ── State machine ─────────────────────────────────────────────────────────

    private fun setState(newState: LiveState) {
        state = newState
        when (newState) {
            LiveState.IDLE -> {
                liveStateLabel.text = "Tap mic to start"
                liveMicButton.text  = "🎙️"
                liveMicButton.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF1A73E8.toInt())
                stopWaveAnimation()
            }
            LiveState.CONNECTING -> {
                liveStateLabel.text = "Connecting…"
                liveMicButton.text  = "⏳"
                liveMicButton.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF9AA0A6.toInt())
                stopWaveAnimation()
            }
            LiveState.LISTENING -> {
                liveStateLabel.text = "Listening…"
                liveMicButton.text  = "⏹"
                liveMicButton.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
                startWaveAnimation(active = false)
                // Flush any leftover pending text
                pendingText?.let { txt -> pendingText = null; sendTextToGemini(txt) }
            }
            LiveState.AI_SPEAKING -> {
                liveStateLabel.text = "AI speaking…"
                startWaveAnimation(active = true)
            }
        }
    }

    // ── Transcript helpers ────────────────────────────────────────────────────

    private fun appendTranscript(text: String, isUser: Boolean = false, isSystem: Boolean = false) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            val padding = (10 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (6 * resources.displayMetrics.density).toInt()

            when {
                isSystem -> {
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 13f
                    lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                isUser -> {
                    setBackgroundResource(R.drawable.rounded_edittext_bg)
                    setTextColor(0xFF111827.toInt())
                    lp.gravity = android.view.Gravity.END
                    lp.marginEnd = (8 * resources.displayMetrics.density).toInt()
                    lp.marginStart = (40 * resources.displayMetrics.density).toInt()
                }
                else -> {
                    setTextColor(0xFF1A237E.toInt())
                    lp.gravity = android.view.Gravity.START
                    lp.marginStart = (8 * resources.displayMetrics.density).toInt()
                    lp.marginEnd = (40 * resources.displayMetrics.density).toInt()
                }
            }
            layoutParams = lp
        }
        transcriptContainer.addView(tv)
        transcriptScrollView.post {
            transcriptScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    // ── Wave animation ────────────────────────────────────────────────────────

    private fun startWaveAnimation(active: Boolean) {
        stopWaveAnimation()
        val waves   = listOf(liveWave1, liveWave2, liveWave3, liveWave4, liveWave5)
        val heights = if (active) activeHeights else idleHeights
        val density = resources.displayMetrics.density
        waves.forEachIndexed { i, view ->
            val targetH = (heights[i] * density).toInt()
            val anim = ScaleAnimation(
                1f, 1f,
                1f, heights[i].toFloat() / (view.height.coerceAtLeast(1)),
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 1f
            ).apply {
                duration         = (600 + i * 80).toLong()
                repeatCount      = Animation.INFINITE
                repeatMode       = Animation.REVERSE
                startOffset      = (i * 120).toLong()
            }
            view.startAnimation(anim)
            waveAnimations += anim
        }
    }

    private fun stopWaveAnimation() {
        listOf(liveWave1, liveWave2, liveWave3, liveWave4, liveWave5).forEach { it.clearAnimation() }
        waveAnimations.clear()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun checkAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startLiveSession()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice mode", Toast.LENGTH_SHORT).show()
        }
    }
}
