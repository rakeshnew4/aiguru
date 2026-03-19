package com.example.aiguru

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.BuildConfig
import com.example.aiguru.adapters.MessageAdapter
import com.example.aiguru.models.Message
import com.example.aiguru.utils.TTSCallback
import com.example.aiguru.utils.TextToSpeechManager
import com.example.aiguru.utils.ChapterMetricsTracker
import com.example.aiguru.utils.VoiceManager
import com.example.aiguru.utils.VoiceRecognitionCallback
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID

class RealTeacherActivity : AppCompatActivity(), VoiceRecognitionCallback {

    // ─── State ────────────────────────────────────────────────────────────────

    private enum class SessionState { IDLE, LISTENING, THINKING, SPEAKING }
    private var state = SessionState.IDLE

    // ─── Views ────────────────────────────────────────────────────────────────

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusChip: Chip
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var messageInput: TextInputEditText
    private lateinit var sendButton: MaterialButton
    private lateinit var micFab: FloatingActionButton
    private lateinit var autoListenSwitch: SwitchMaterial

    // ─── Adapters & utils ─────────────────────────────────────────────────────

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TextToSpeechManager
    private var metricsTracker: ChapterMetricsTracker? = null

    // ─── API ──────────────────────────────────────────────────────────────────

    private val client = OkHttpClient()
    private val API_KEY = BuildConfig.GROQ_API_KEY
    private val API_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val MODEL = "llama-3.3-70b-versatile"

    // ─── Context ──────────────────────────────────────────────────────────────

    private lateinit var subjectName: String
    private lateinit var chapterName: String

    private val mainHandler = Handler(Looper.getMainLooper())
    private val conversationHistory = mutableListOf<JSONObject>()

    private var micPulseAnimator: ObjectAnimator? = null

    companion object {
        private const val PERMISSION_CODE = 201
        private const val MENU_END_SESSION = 1001
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_real_teacher)

        subjectName = intent.getStringExtra("subjectName") ?: "General"
        chapterName = intent.getStringExtra("chapterName") ?: "General Studies"

        // Record Real Teacher usage if launched from a chapter context
        if (intent.hasExtra("subjectName") && intent.hasExtra("chapterName")) {
            metricsTracker = ChapterMetricsTracker(subjectName, chapterName).also {
                it.recordEvent(ChapterMetricsTracker.EventType.REAL_TEACHER_USED)
            }
        }

        voiceManager = VoiceManager(this)
        ttsManager = TextToSpeechManager(this)

        bindViews()
        setupToolbar()
        setupRecyclerView()
        setupInputListeners()

        // Let TTS finish initializing (it's async), then kick off the session
        messagesRecyclerView.postDelayed({ startSession() }, 1200)
    }

    override fun onStop() {
        super.onStop()
        metricsTracker?.endSession(this)
    }

    override fun onDestroy() {
        micPulseAnimator?.cancel()
        ttsManager.destroy()
        voiceManager.destroy()
        super.onDestroy()
    }

    // ─── View binding ─────────────────────────────────────────────────────────

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        statusChip = findViewById(R.id.statusChip)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        loadingLayout = findViewById(R.id.loadingLayout)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        micFab = findViewById(R.id.micFab)
        autoListenSwitch = findViewById(R.id.autoListenSwitch)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        toolbar.subtitle = if (chapterName == "General Studies") subjectName
                           else "$subjectName › $chapterName"
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            context = this,
            onVoiceClick = { msg ->
                ttsManager.speak(msg.content, object : TTSCallback {
                    override fun onStart() {}
                    override fun onComplete() {}
                    override fun onError(error: String) {}
                })
            },
            onStopClick = { ttsManager.stop() },
            onImageClick = {}
        )
        messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messagesRecyclerView.adapter = messageAdapter
    }

    private fun setupInputListeners() {
        sendButton.setOnClickListener {
            val text = messageInput.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                messageInput.setText("")
                handleStudentInput(text)
            }
        }

        micFab.setOnClickListener {
            when (state) {
                SessionState.LISTENING -> stopListening()
                SessionState.SPEAKING  -> {
                    ttsManager.stop()
                    startListeningIfAllowed()
                }
                else -> startListeningIfAllowed()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_END_SESSION, Menu.NONE, "End Session")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_END_SESSION) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ─── Session kickoff ──────────────────────────────────────────────────────

    private fun startSession() {
        // Prime conversation history with system prompt
        conversationHistory.clear()
        conversationHistory.add(buildSystemMessage())

        // Kick off with opening greeting — no user turn yet
        sendToTeacher(null)
    }

    private fun buildSystemMessage(): JSONObject {
        val isGeneral = chapterName == "General Studies"
        val topic = if (isGeneral) "any subject the student wants to discuss"
                    else "\"$chapterName\" ($subjectName)"

        val prompt = """You are a warm, patient, and encouraging school teacher named "AI Guru Teacher".
You are having a one-on-one voice conversation with a student about $topic.

CRITICAL RULES — follow these strictly at all times:
1. Keep every response SHORT — 2 to 3 sentences maximum for each turn. This is a spoken conversation.
2. NEVER use markdown, bullet points, asterisks, hashes, or any special formatting. Plain sentences only.
3. ALWAYS end your response with ONE clear, open-ended question to check the student's understanding or invite them to continue.
4. Be warm, encouraging, and Socratic — guide the student to think rather than just giving answers.
5. If a student is struggling, give a small hint or analogy first before the full answer.
6. Vary your check-in questions: "Does that make sense?", "Can you explain that back to me?", "What do you think would happen if...?", "Have you seen this concept before?", "What part is still confusing?"
7. Start the session by warmly greeting the student and asking what they want to learn or clarify about $topic.
8. React naturally to answers — praise correct ones briefly ('Great thinking!'), gently correct wrong ones.
9. Do NOT produce lists, numbered items, or multi-paragraph answers. Always prefer short spoken sentences.

Remember: You are speaking aloud, not writing. Keep it conversational and human."""

        return JSONObject().put("role", "system").put("content", prompt)
    }

    // ─── Core conversation flow ───────────────────────────────────────────────

    /**
     * Called when the student sends a message (text or voice).
     * null = first turn with no student input (trigger opening greeting).
     */
    private fun sendToTeacher(studentText: String?) {
        if (studentText != null) {
            // Show student bubble
            val studentMsg = Message(
                id = UUID.randomUUID().toString(),
                content = studentText,
                isUser = true,
                messageType = Message.MessageType.TEXT
            )
            messageAdapter.addMessage(studentMsg)
            scrollToBottom()

            // Add to history
            conversationHistory.add(JSONObject().put("role", "user").put("content", studentText))
        }

        setState(SessionState.THINKING)

        // Snapshot history on the main thread before dispatching to IO (thread-safety)
        val historySnapshot = ArrayList(conversationHistory)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = callGroqAPI(historySnapshot)
                if (!isDestroyed && !isFinishing) {
                    runOnUiThread {
                        if (response != null) {
                            val cleanResponse = stripMarkdown(response)
                            conversationHistory.add(JSONObject().put("role", "assistant").put("content", cleanResponse))
                            val teacherMsg = Message(
                                id = UUID.randomUUID().toString(),
                                content = cleanResponse,
                                isUser = false,
                                messageType = Message.MessageType.TEXT
                            )
                            messageAdapter.addMessage(teacherMsg)
                            scrollToBottom()
                            speakTeacherMessage(cleanResponse)
                        } else {
                            setState(SessionState.IDLE)
                            showBubble("I couldn't reach the server. Check your internet connection and tap the mic to try again.", false)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isDestroyed && !isFinishing) {
                    runOnUiThread {
                        setState(SessionState.IDLE)
                        showBubble("Something went wrong (${e.message ?: "unknown error"}). Tap the mic to continue.", false)
                    }
                }
            }
        }
    }

    private fun speakTeacherMessage(text: String) {
        setState(SessionState.SPEAKING)
        ttsManager.speak(text, object : TTSCallback {
            override fun onStart() {}
            override fun onComplete() {
                // Small delay to let audio session release before mic starts
                mainHandler.postDelayed({
                    if (!isDestroyed && !isFinishing) {
                        setState(SessionState.IDLE)
                        if (autoListenSwitch.isChecked) {
                            startListeningIfAllowed()
                        }
                    }
                }, 600)
            }
            override fun onError(error: String) {
                // TTS failed — still start auto-listen so conversation can continue
                mainHandler.postDelayed({
                    if (!isDestroyed && !isFinishing) {
                        setState(SessionState.IDLE)
                        if (autoListenSwitch.isChecked) {
                            startListeningIfAllowed()
                        }
                    }
                }, 400)
            }
        })
    }

    private fun handleStudentInput(text: String) {
        // Stop any current TTS or listening
        ttsManager.stop()
        if (state == SessionState.LISTENING) stopListening()
        sendToTeacher(text)
    }

    // ─── API call ─────────────────────────────────────────────────────────────

    private fun callGroqAPI(history: List<JSONObject>): String? {
        val messages = JSONArray().apply {
            history.forEach { put(it) }
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("temperature", 0.85)
            put("max_tokens", 300)
        }
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return null
            if (!response.isSuccessful) return null
            JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } catch (e: Exception) {
            null
        }
    }

    // ─── Voice: VoiceRecognitionCallback ──────────────────────────────────────

    override fun onResults(text: String) {
        runOnUiThread {
            stopMicPulse()
            messageInput.setText("")
            setState(SessionState.IDLE)
            if (text.isNotBlank()) {
                handleStudentInput(text)
            }
        }
    }

    override fun onPartialResults(text: String) {
        runOnUiThread {
            messageInput.setText(text)
        }
    }

    /**
     * On many devices, SpeechRecognizer fires onError(ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT)
     * instead of onResults even when speech was partly recognised. If the input box
     * already has text from onPartialResults, we submit it instead of discarding it.
     */
    override fun onError(error: String) {
        runOnUiThread {
            stopMicPulse()
            val partial = messageInput.text?.toString()?.trim() ?: ""
            messageInput.setText("")
            setState(SessionState.IDLE)
            if (partial.isNotBlank()) {
                // Submit whatever partial text the recognizer captured
                handleStudentInput(partial)
            } else {
                Toast.makeText(this, "Couldn't hear you — please try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onListeningStarted() {}

    override fun onListeningFinished() {
        runOnUiThread {
            stopMicPulse()
            if (state == SessionState.LISTENING) setState(SessionState.IDLE)
        }
    }

    // ─── Voice helpers ────────────────────────────────────────────────────────

    private fun startListeningIfAllowed() {
        if (state == SessionState.THINKING || state == SessionState.SPEAKING) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), PERMISSION_CODE
            )
            return
        }
        messageInput.setText("")
        setState(SessionState.LISTENING)
        startMicPulse()
        voiceManager.startListening(this, "en-US")
    }

    private fun stopListening() {
        voiceManager.stopListening()
        stopMicPulse()
        setState(SessionState.IDLE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListeningIfAllowed()
        }
    }

    // ─── Mic pulse animation ──────────────────────────────────────────────────

    private fun startMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = ObjectAnimator.ofFloat(micFab, "scaleX", 1f, 1.3f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(micFab, "scaleY", 1f, 1.3f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        micFab.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#E53935"))
        micFab.setColorFilter(android.graphics.Color.WHITE)
    }

    private fun stopMicPulse() {
        micPulseAnimator?.cancel()
        micFab.scaleX = 1f
        micFab.scaleY = 1f
        micFab.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#E3F2FD"))
        micFab.clearColorFilter()
        micFab.setColorFilter(android.graphics.Color.parseColor("#1565C0"))
    }

    // ─── State management ─────────────────────────────────────────────────────

    private fun setState(newState: SessionState) {
        state = newState
        when (newState) {
            SessionState.IDLE -> {
                statusChip.text = "Tap mic to speak"
                loadingLayout.visibility = View.GONE
                micFab.isEnabled = true
            }
            SessionState.LISTENING -> {
                statusChip.text = "🎤 Listening…"
                loadingLayout.visibility = View.GONE
                micFab.isEnabled = true
            }
            SessionState.THINKING -> {
                statusChip.text = "⏳ Teacher is thinking…"
                loadingLayout.visibility = View.VISIBLE
                micFab.isEnabled = false
                stopMicPulse()
            }
            SessionState.SPEAKING -> {
                statusChip.text = "🔊 Teacher is speaking…"
                loadingLayout.visibility = View.GONE
                micFab.isEnabled = true
                stopMicPulse()
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun scrollToBottom() {
        messagesRecyclerView.post {
            val last = messageAdapter.itemCount - 1
            if (last >= 0) messagesRecyclerView.smoothScrollToPosition(last)
        }
    }

    private fun showBubble(text: String, isUser: Boolean) {
        messageAdapter.addMessage(
            Message(UUID.randomUUID().toString(), text, isUser, messageType = Message.MessageType.TEXT)
        )
        scrollToBottom()
    }

    /**
     * Remove common markdown so TTS reads cleanly.
     * Strips: **, *, ##, #, __, _, backticks, bullet symbols.
     */
    private fun stripMarkdown(text: String): String =
        text
            .replace(Regex("#{1,6}\\s*"), "")
            .replace(Regex("\\*{1,2}(.*?)\\*{1,2}"), "$1")
            .replace(Regex("_{1,2}(.*?)_{1,2}"), "$1")
            .replace(Regex("`{1,3}(.*?)`{1,3}", RegexOption.DOT_MATCHES_ALL), "$1")
            .replace(Regex("^[-*•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\^\\{([^}]+)}", RegexOption.MULTILINE), "$1")
            .replace(Regex("_\\{([^}]+)}", RegexOption.MULTILINE), "$1")
            .trim()

}
