package com.example.aiguru
import com.example.aiguru.BuildConfig
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.io.File
import com.example.aiguru.adapters.MessageAdapter
import com.example.aiguru.models.Flashcard
import com.example.aiguru.models.Message
import com.example.aiguru.utils.ChapterMetricsTracker
import com.example.aiguru.utils.MediaManager
import com.example.aiguru.utils.SessionManager
import com.example.aiguru.utils.TextToSpeechManager
import com.example.aiguru.utils.TTSCallback
import com.example.aiguru.utils.VoiceManager
import com.example.aiguru.models.TutorIntent
import com.example.aiguru.models.TutorMode
import com.example.aiguru.models.TutorSession
import com.example.aiguru.utils.VoiceRecognitionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import android.view.Menu
import android.view.MenuItem

class ChatActivity : AppCompatActivity(), VoiceRecognitionCallback {

    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var toolbar: MaterialToolbar
    private lateinit var loadingLayout: LinearLayout
    private lateinit var voiceButton: MaterialButton
    private lateinit var imageButton: MaterialButton
    private lateinit var pdfButton: MaterialButton
    private lateinit var saveNotesButton: MaterialButton
    private lateinit var viewNotesButton: MaterialButton
    private lateinit var formulaButton: MaterialButton
    private lateinit var practiceButton: MaterialButton
    private lateinit var imagePreviewStrip: LinearLayout
    private lateinit var imagePreviewThumbnail: ImageView
    private lateinit var imagePreviewLabel: TextView
    private lateinit var removeImageButton: MaterialButton
    private lateinit var listeningIndicator: TextView
    private lateinit var bottomDescribeButton: MaterialButton

    private val client = OkHttpClient()
    private val API_KEY = BuildConfig.GROQ_API_KEY
    private val API_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val MODEL_TEXT = "llama-3.3-70b-versatile"
    private val MODEL_VISION = "meta-llama/llama-4-scout-17b-16e-instruct"

    private lateinit var db: FirebaseFirestore
    private lateinit var subjectName: String
    private lateinit var chapterName: String

    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var mediaManager: MediaManager
    private lateinit var metricsTracker: ChapterMetricsTracker

    private var selectedImageUri: Uri? = null
    private var cameraImageUri: Uri? = null
    private var isListening = false

    // Language for voice recognition, TTS, and LLM responses
    private var currentLang = "en-US"
    private var currentLangName = "English"
    private val LANGUAGES = linkedMapOf(
        "English"              to "en-US",
        "हिंदी (Hindi)"       to "hi-IN",
        "বাংলা (Bengali)"     to "bn-IN",
        "తెలుగు (Telugu)"     to "te-IN",
        "தமிழ் (Tamil)"       to "ta-IN",
        "मराठी (Marathi)"     to "mr-IN",
        "ಕನ್ನಡ (Kannada)"    to "kn-IN",
        "ગુજરાતી (Gujarati)" to "gu-IN"
    )

    // Pre-loaded PDF page (base64 encoded) passed from ChapterActivity
    private var pdfPageBase64: String? = null

    // When set, the AI response from the next auto-send is also saved as notes
    private var saveNotesType: String? = null

    // ── Tutor System ──────────────────────────────────────────────────────────
    private lateinit var tutorSession: TutorSession
    private var lastInputWasVoice = false
    private lateinit var modeAutoButton: MaterialButton
    private lateinit var modeExplainButton: MaterialButton
    private lateinit var modePracticeButton: MaterialButton
    private lateinit var modeEvaluateButton: MaterialButton

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) showImagePreview(uri)
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) showImagePreview(cameraImageUri!!)
        }

    private val pickPdfLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) Toast.makeText(this, "PDF support coming soon", Toast.LENGTH_SHORT).show()
        }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        db = FirebaseFirestore.getInstance()
        subjectName = intent.getStringExtra("subjectName") ?: "General"
        chapterName = intent.getStringExtra("chapterName") ?: "Study Session"

        voiceManager = VoiceManager(this)
        ttsManager = TextToSpeechManager(this)
        mediaManager = MediaManager(this)
        metricsTracker = ChapterMetricsTracker(subjectName, chapterName)

        tutorSession = TutorSession(
            studentId = SessionManager.getFirestoreUserId(this),
            subject   = subjectName,
            chapter   = chapterName
        )

        initializeUI()
        loadChatHistory()

        // Notes type to auto-save when notes are generated (e.g. "chapter", "page_1", "exercises")
        saveNotesType = intent.getStringExtra("saveNotesType")

        // Auto-prompt (e.g. from Notes button in ChapterActivity)
        val autoPrompt = intent.getStringExtra("autoPrompt")
        if (autoPrompt != null) {
            // Post it after the welcome message is added; if saveNotesType set, auto-save the response
            messagesRecyclerView.post { sendMessage(autoPrompt, autoSaveNotes = saveNotesType != null) }
        }

        // Load PDF page passed from ChapterActivity (if any)
        val pdfPageFilePath = intent.getStringExtra("pdfPageFilePath")
        val pdfPageNumber = intent.getIntExtra("pdfPageNumber", 1)
        if (pdfPageFilePath != null) {
            tutorSession.currentPage = pdfPageNumber
            preloadPdfPage(File(pdfPageFilePath), pdfPageNumber)
        }
    }

    private fun initializeUI() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.title = "📘 $chapterName"
        toolbar.subtitle = "👩\u200d🏫 AI Tutor · $subjectName"
        toolbar.setNavigationOnClickListener { finish() }
        setSupportActionBar(toolbar)

        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageAdapter = MessageAdapter(
            context = this,
            onVoiceClick = { msg ->
                ttsManager.setLocale(Locale.forLanguageTag(currentLang))
                ttsManager.speak(msg.content, object : com.example.aiguru.utils.TTSCallback {
                    override fun onStart() {}
                    override fun onComplete() {}
                    override fun onError(error: String) {}
                })
            },
            onStopClick = { ttsManager.stop() },
            onImageClick = { }
        )
        messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messagesRecyclerView.adapter = messageAdapter

        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        loadingLayout = findViewById(R.id.loadingLayout)
        voiceButton = findViewById(R.id.voiceButton)
        imageButton = findViewById(R.id.imageButton)
        pdfButton = findViewById(R.id.pdfButton)
        saveNotesButton = findViewById(R.id.saveNotesButton)
        viewNotesButton = findViewById(R.id.viewNotesButton)
        formulaButton = findViewById(R.id.formulaButton)
        practiceButton = findViewById(R.id.practiceButton)
        imagePreviewStrip = findViewById(R.id.imagePreviewStrip)
        imagePreviewThumbnail = findViewById(R.id.imagePreviewThumbnail)
        imagePreviewLabel = findViewById(R.id.imagePreviewLabel)
        removeImageButton = findViewById(R.id.removeImageButton)
        listeningIndicator = findViewById(R.id.listeningIndicator)
        bottomDescribeButton = findViewById(R.id.bottomDescribeButton)

        setupButtons()
        setupQuickActions()
        setupModeChips()

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            val hasImage = selectedImageUri != null
            if (text.isNotEmpty() || hasImage) {
                val prompt = text.ifEmpty {
                    "Please explain what you see in this image in the context of $chapterName."
                }
                sendMessage(prompt)
                messageInput.setText("")
            }
        }
    }

    private fun setupButtons() {
        voiceButton.setOnClickListener {
            if (isListening) {
                voiceManager.stopListening()
            } else {
                checkPermissionAndStartListening()
            }
        }

        imageButton.setOnClickListener { showImageSourceDialog() }
        pdfButton.setOnClickListener { openPdfPicker() }

        saveNotesButton.setOnClickListener { saveLastAIMessageAsNotes() }
        viewNotesButton.setOnClickListener { viewSavedNotes() }

        formulaButton.setOnClickListener {            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.FORMULA_USED)
            sendMessage(
                "Generate a complete formula sheet for \"$chapterName\" ($subjectName)." +
                " List every important formula, equation, and rule used in this topic." +
                " For each formula write:\n• The formula itself (use ^{} for superscripts, _{} for subscripts)" +
                "\n• What each symbol means\n• When/how it is used\n• A quick example."
            )
        }

        practiceButton.setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.PRACTICE_USED)
            sendMessage(
                "Create 5 practice problems for \"$chapterName\" ($subjectName) at different difficulty levels." +
                " Label them Easy / Medium / Hard." +
                " Show the full step-by-step solution for each, with working and final answer."
            )
        }

        removeImageButton.setOnClickListener {
            selectedImageUri = null
            pdfPageBase64 = null
            imagePreviewStrip.visibility = View.GONE
            messageInput.setText("")
            bottomDescribeButton.visibility = View.GONE
        }

        // Bottom quick-action chips
        findViewById<MaterialButton>(R.id.bottomExplainButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.EXPLAIN_USED)
            val hasMedia = selectedImageUri != null || pdfPageBase64 != null
            if (hasMedia) {
                sendMessage("Explain what you see in this image in the context of $chapterName ($subjectName). Describe key concepts, labels, or information shown.")
                messageInput.setText("")
            } else {
                sendMessage("Explain \"$chapterName\" in simple, easy-to-understand language. Use real-world examples and analogies.")
            }
        }
        findViewById<MaterialButton>(R.id.bottomSummarizeButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.SUMMARIZE_USED)
            val hasMedia = selectedImageUri != null || pdfPageBase64 != null
            if (hasMedia) {
                sendMessage("Summarize the key information visible in this image or page in structured bullet points.")
                messageInput.setText("")
            } else {
                sendMessage("Summarize the key points of \"$chapterName\" in a structured format with clear headings and bullet points.")
            }
        }
        findViewById<MaterialButton>(R.id.bottomQuizButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.QUIZ_REQUESTED)
            sendMessage("Create 5 quiz questions about \"$chapterName\". For each question show the answer after it.\nFormat:\nQ: [question]\nA: [answer]")
        }
        findViewById<MaterialButton>(R.id.bottomFormulaButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.FORMULA_USED)
            sendMessage("Generate a complete formula sheet for \"$chapterName\" ($subjectName). List every important formula with explanation and an example.")
        }
        findViewById<MaterialButton>(R.id.bottomPracticeButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.PRACTICE_USED)
            sendMessage("Create 5 practice problems for \"$chapterName\" ($subjectName) at different difficulty levels (Easy/Medium/Hard). Show full step-by-step solutions.")
        }
        bottomDescribeButton.setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.EXPLAIN_USED)
            sendMessage("Describe everything you see in this image in detail. Identify all objects, text, diagrams, or concepts visible and explain them in the context of $chapterName.")
            messageInput.setText("")
        }
    }

    private fun setupQuickActions() {
        findViewById<MaterialButton>(R.id.summarizeButton).setOnClickListener {
            sendMessage(
                "Please summarize the key points of \"$chapterName\" in a structured format with clear headings and bullet points."
            )
        }
        findViewById<MaterialButton>(R.id.explainButton).setOnClickListener {
            sendMessage(
                "Explain \"$chapterName\" in simple, easy-to-understand language. Use real-world examples and analogies to make it clear for a student."
            )
        }
        findViewById<MaterialButton>(R.id.quizButton).setOnClickListener {
            sendMessage(
                "Create 5 quiz questions about \"$chapterName\". For each question show the answer after it. Use this exact format:\nQ: [question]\nA: [answer]\n\nMake the questions test key concepts and definitions."
            )
        }
        findViewById<MaterialButton>(R.id.notesButton).setOnClickListener {
            sendMessage(
                "Create comprehensive study notes for \"$chapterName\" with:\n• Key concepts and definitions\n• Important facts to remember\n• Summary of main points\n• Any formulas or rules to know"
            )
        }
        findViewById<MaterialButton>(R.id.flashcardsButton).setOnClickListener {
            generateFlashcards()
        }
    }

    private fun checkPermissionAndStartListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else startVoiceInput()
    }

    private fun startVoiceInput() {
        isListening = true
        metricsTracker.recordEvent(ChapterMetricsTracker.EventType.VOICE_INPUT)
        voiceButton.text = "⏹️"
        voiceButton.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#E53935"))
        listeningIndicator.visibility = View.VISIBLE
        voiceManager.startListening(this, currentLang)
    }

    private fun resetVoiceButton() {
        isListening = false
        voiceButton.text = "🎤"
        voiceButton.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#E3F2FD"))
        listeningIndicator.visibility = View.GONE
    }

    private fun showImagePreview(uri: Uri) {
        selectedImageUri = uri
        metricsTracker.recordEvent(ChapterMetricsTracker.EventType.IMAGE_UPLOADED)
        imagePreviewStrip.visibility = View.VISIBLE
        Glide.with(this).load(uri).centerCrop().into(imagePreviewThumbnail)
        imagePreviewLabel.text = mediaManager.getFileInfo(uri)
        // Auto-populate input with "Explain" and show image-specific chip
        messageInput.setText("Explain this")
        messageInput.setSelection(messageInput.text.length)
        bottomDescribeButton.visibility = View.VISIBLE
    }

    /**
     * Loads a rendered PDF page file as Base64 and shows it in the image preview strip
     * so the user can immediately ask questions about that page.
     */
    private fun preloadPdfPage(pageFile: File, pageNumber: Int) {
        if (!pageFile.exists()) return
        val bmp = BitmapFactory.decodeFile(pageFile.absolutePath) ?: return
        val baos = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
        bmp.recycle()
        pdfPageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        imagePreviewStrip.visibility = View.VISIBLE
        Glide.with(this).load(pageFile).centerCrop().into(imagePreviewThumbnail)
        imagePreviewLabel.text = "Page $pageNumber"
        // Auto-populate input with "Explain" and show image-specific chip
        messageInput.setText("Explain this page")
        messageInput.setSelection(messageInput.text.length)
        bottomDescribeButton.visibility = View.VISIBLE
    }

    private fun showImageSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add Image")
            .setItems(arrayOf("📷  Take Photo", "🖼️  Choose from Gallery")) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> pickImageLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE
            )
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraImageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        cameraImageUri?.let { cameraLauncher.launch(it) }
    }

    private fun openPdfPicker() = pickPdfLauncher.launch("application/pdf")

    private fun loadChatHistory() {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("chats")
            .document("${subjectName}_${chapterName}")
            .collection("messages")
            .orderBy("timestamp")
            .limit(50)
            .get()
            .addOnSuccessListener { snapshot ->
                val messages = snapshot.documents.mapNotNull {
                    try {
                        Message(
                            id = it.id,
                            content = it.getString("content") ?: "",
                            isUser = it.getBoolean("isUser") ?: true,
                            timestamp = it.getLong("timestamp") ?: System.currentTimeMillis(),
                            messageType = Message.MessageType.valueOf(
                                it.getString("messageType") ?: "TEXT"
                            )
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                if (messages.isEmpty()) {
                    addWelcomeMessage()
                } else {
                    messageAdapter.addMessages(messages)
                    messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                }
            }
            .addOnFailureListener { addWelcomeMessage() }
    }

    private fun addWelcomeMessage() {
        messageAdapter.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                content = "👋 Hi! I'm your AI Tutor for **$chapterName** ($subjectName).\n\n🎯 **Let's learn this together!**\n\n• Ask me any doubt — I'll guide you step by step\n• Use your 🎤 voice to ask (I'll reply out loud!)\n• Tap **Practice** for problems, **Quiz Me** to test yourself\n• Choose a **Tutor Mode** above to focus your session\n\nWhat would you like to understand first? 😊",
                isUser = false,
                messageType = Message.MessageType.TEXT
            )
        )
    }

    private fun sendMessage(userText: String, autoSaveNotes: Boolean = false) {
        val imageUri = selectedImageUri
        selectedImageUri = null
        imagePreviewStrip.visibility = View.GONE

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            content = userText,
            isUser = true,
            imageUrl = imageUri?.toString(),
            messageType = if (imageUri != null) Message.MessageType.IMAGE else Message.MessageType.TEXT
        )
        messageAdapter.addMessage(userMessage)
        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
        showLoading(true)

        val capturedPdfBase64 = pdfPageBase64
        pdfPageBase64 = null  // consume so it isn't re-used on next message

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val responseText = when {
                    imageUri != null -> {
                        val base64 = mediaManager.uriToBase64(imageUri)
                        if (base64 != null) callGroqAPIWithImage(userText, base64) else callGroqAPI(userText)
                    }
                    capturedPdfBase64 != null -> callGroqAPIWithImage(userText, capturedPdfBase64)
                    else -> callGroqAPI(userText)
                }

                saveMessageToFirestore(userMessage.copy(imageUrl = null))

                runOnUiThread {
                    showLoading(false)
                    if (responseText != null) {
                        val tutorReply = TutorController.parseResponse(responseText)
                        TutorController.updateSession(tutorSession, tutorReply.intent, userText)
                        updateModeChipStates() // reflect any auto mode-switch
                        val aiMessage = Message(UUID.randomUUID().toString(), tutorReply.response, false)
                        messageAdapter.addMessage(aiMessage)
                        saveMessageToFirestore(aiMessage)
                        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                        // Auto-TTS when the student used voice input
                        if (lastInputWasVoice) {
                            lastInputWasVoice = false
                            val voiceText = TutorController.trimForVoice(tutorReply.response)
                            ttsManager.setLocale(java.util.Locale.forLanguageTag(currentLang))
                            ttsManager.speak(voiceText, object : TTSCallback {
                                override fun onStart() {}
                                override fun onComplete() {}
                                override fun onError(error: String) {}
                            })
                        }
                        // Auto-save as notes if triggered from ChapterActivity notes flow
                        if (autoSaveNotes && saveNotesType != null) {
                            persistNotesToFirestore(tutorReply.response, saveNotesType!!)
                        }
                    } else {
                        showError("Couldn't get a response. Check your connection and try again.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showLoading(false)
                    showError("Error: ${e.message ?: "Unknown error"}")
                }
            }
        }
    }

    private fun callGroqAPI(userText: String): String? {
        val json = JSONObject().apply {
            put("model", MODEL_TEXT)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", TutorController.buildSystemPrompt(tutorSession) + getLanguageInstruction()))
                put(JSONObject().put("role", "user").put("content", userText))
            })
            put("temperature", 0.7)
            put("max_tokens", 2048)
        }
        return executeGroqRequest(json)
    }

    private fun callGroqAPIWithImage(userText: String, base64Image: String): String? {
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Subject: $subjectName | Chapter: $chapterName\n\n$userText")
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
            })
        }
        val json = JSONObject().apply {
            put("model", MODEL_VISION)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", TutorController.buildSystemPrompt(tutorSession) + getLanguageInstruction()))
                put(JSONObject().put("role", "user").put("content", contentArray))
            })
            put("temperature", 0.7)
            put("max_tokens", 2048)
        }
        return executeGroqRequest(json)
    }

    private fun executeGroqRequest(json: JSONObject): String? {
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) return null
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: IOException) {
            null
        }
    }

    private fun generateFlashcards() {
        val prompt = """Generate exactly 5 educational flashcards for "$chapterName" (Subject: $subjectName).
Use ONLY this exact format with no extra text between cards:
Q: [question]
A: [answer]

Q: [question]
A: [answer]

Make questions test key concepts, definitions, or important facts."""

        messageAdapter.addMessage(
            Message(UUID.randomUUID().toString(),
                "🃏 Generating revision flashcards for $chapterName…", true)
        )
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val responseText = callGroqAPI(prompt)
            runOnUiThread {
                showLoading(false)
                if (responseText != null) {
                    val cards = parseFlashcards(responseText)
                    if (cards.isNotEmpty()) {
                        messageAdapter.addMessage(
                            Message(UUID.randomUUID().toString(),
                                "✅ ${cards.size} flashcards ready! Opening revision mode…", false)
                        )
                        startActivity(
                            Intent(this@ChatActivity, RevisionActivity::class.java)
                                .putExtra("flashcards", ArrayList(cards))
                        )
                    } else {
                        showError("Could not parse flashcards. Please try again.")
                    }
                } else {
                    showError("Failed to generate flashcards. Check your connection.")
                }
            }
        }
    }

    private fun parseFlashcards(text: String): List<Flashcard> {
        val cards = mutableListOf<Flashcard>()
        var question = ""
        for (line in text.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Q:") -> question = trimmed.removePrefix("Q:").trim()
                trimmed.startsWith("A:") && question.isNotEmpty() -> {
                    cards.add(Flashcard(question, trimmed.removePrefix("A:").trim()))
                    question = ""
                }
            }
        }
        return cards
    }

    // ─── Notes: save & view ─────────────────────────────────────────────────

    private fun saveLastAIMessageAsNotes() {
        val lastAi = messageAdapter.getLastAIMessage()
        if (lastAi == null) {
            Toast.makeText(this, "Generate notes first, then tap 💾 Save Notes.", Toast.LENGTH_SHORT).show()
            return
        }
        val type = saveNotesType ?: "chapter"
        persistNotesToFirestore(lastAi.content, type)
    }

    private fun persistNotesToFirestore(content: String, type: String) {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("notes").document(type)
            .set(hashMapOf(
                "content"   to content,
                "type"      to type,
                "updatedAt" to System.currentTimeMillis()
            ))
            .addOnSuccessListener {
                metricsTracker.recordEvent(ChapterMetricsTracker.EventType.NOTES_SAVED)
                runOnUiThread {
                    Toast.makeText(this, "✅ Notes saved!", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                runOnUiThread {
                    Toast.makeText(this, "Failed to save notes.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun viewSavedNotes() {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("notes")
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    Toast.makeText(this, "No saved notes yet — generate and save notes first!", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val docs = snapshot.documents.sortedBy { doc ->
                    when (doc.id) { "chapter" -> "0"; "exercises" -> "z"; else -> doc.id }
                }
                val sb = StringBuilder()
                docs.forEach { doc ->
                    val type = doc.getString("type") ?: doc.id
                    val content = doc.getString("content") ?: return@forEach
                    if (content.isBlank()) return@forEach
                    val heading = when {
                        type == "chapter"          -> "📖 Chapter Notes"
                        type.startsWith("page_")  -> "📄 Page ${type.removePrefix("page_")} Notes"
                        type == "exercises"        -> "✏️ Exercise Notes"
                        else -> "📋 $type"
                    }
                    sb.append("$heading\n\n$content\n\n──────────────\n\n")
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "No notes content found.", Toast.LENGTH_SHORT).show()
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("📋 Saved Notes — $chapterName")
                        .setMessage(text)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Couldn't load notes.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveMessageToFirestore(message: Message) {
        val data = hashMapOf(
            "id" to message.id,
            "content" to message.content,
            "isUser" to message.isUser,
            "timestamp" to message.timestamp,
            "messageType" to message.messageType.name
        )
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("chats").document("${subjectName}_${chapterName}")
            .collection("messages").document(message.id)
            .set(data)
    }

    private fun showLoading(show: Boolean) {
        loadingLayout.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // VoiceRecognitionCallback
    override fun onResults(text: String) {
        runOnUiThread {
            resetVoiceButton()
            if (text.isNotEmpty()) {
                lastInputWasVoice = true
                messageInput.setText("")
                sendMessage(text)  // auto-send voice → tutor loop
            } else {
                messageInput.setText(text)
            }
        }
    }

    override fun onPartialResults(text: String) {
        runOnUiThread {
            messageInput.setText(text)
            messageInput.setSelection(text.length)
        }
    }

    override fun onError(error: String) {
        runOnUiThread { resetVoiceButton() }
    }

    override fun onListeningStarted() {}

    override fun onListeningFinished() {
        runOnUiThread { resetVoiceButton() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            // Re-try whichever permission was just granted
            val perm = permissions.firstOrNull()
            if (perm == android.Manifest.permission.RECORD_AUDIO) startVoiceInput()
            else if (perm == android.Manifest.permission.CAMERA) openCamera()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        menu.findItem(R.id.action_language)?.title = "🌐 $currentLangName"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_language) {
            showLanguagePicker()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showLanguagePicker() {
        val names = LANGUAGES.keys.toTypedArray()
        val currentIdx = names.indexOf(currentLangName).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("🌐 Select Response Language")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                currentLangName = names[which]
                currentLang = LANGUAGES[currentLangName] ?: "en-US"
                invalidateOptionsMenu()
                ttsManager.setLocale(Locale.forLanguageTag(currentLang))
                dialog.dismiss()
                Toast.makeText(this, "Language: $currentLangName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getLanguageInstruction(): String = when (currentLang) {
        "hi-IN" -> "\n\nIMPORTANT: Respond in simple Hindi (हिंदी). Use Devanagari script. Technical/scientific terms may remain in English."
        "bn-IN" -> "\n\nIMPORTANT: Respond in Bengali (বাংলা). Technical terms may remain in English."
        "te-IN" -> "\n\nIMPORTANT: Respond in Telugu (తెలుగు). Technical terms may remain in English."
        "ta-IN" -> "\n\nIMPORTANT: Respond in Tamil (தமிழ்). Technical terms may remain in English."
        "mr-IN" -> "\n\nIMPORTANT: Respond in Marathi (मराठी). Technical terms may remain in English."
        "kn-IN" -> "\n\nIMPORTANT: Respond in Kannada (ಕನ್ನಡ). Technical terms may remain in English."
        "gu-IN" -> "\n\nIMPORTANT: Respond in Gujarati (ગુજરાતી). Technical terms may remain in English."
        else -> ""
    }

    // ── Tutor Mode Chips ─────────────────────────────────────────────────────

    private fun setupModeChips() {
        modeAutoButton     = findViewById(R.id.modeAutoButton)
        modeExplainButton  = findViewById(R.id.modeExplainButton)
        modePracticeButton = findViewById(R.id.modePracticeButton)
        modeEvaluateButton = findViewById(R.id.modeEvaluateButton)
        updateModeChipStates()
        modeAutoButton.setOnClickListener {
            tutorSession.mode = TutorMode.AUTO
            updateModeChipStates()
            Toast.makeText(this, "🤖 Auto — I'll adapt to what you need", Toast.LENGTH_SHORT).show()
        }
        modeExplainButton.setOnClickListener {
            tutorSession.mode = TutorMode.EXPLAIN
            updateModeChipStates()
            Toast.makeText(this, "💡 Explain mode — simple explanations with examples", Toast.LENGTH_SHORT).show()
        }
        modePracticeButton.setOnClickListener {
            tutorSession.mode = TutorMode.PRACTICE
            updateModeChipStates()
            Toast.makeText(this, "✍️ Practice mode — let's solve problems together", Toast.LENGTH_SHORT).show()
        }
        modeEvaluateButton.setOnClickListener {
            tutorSession.mode = TutorMode.EVALUATE
            updateModeChipStates()
            Toast.makeText(this, "🧪 Test mode — I'll check your understanding", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateModeChipStates() {
        val activeColor  = android.graphics.Color.parseColor("#1565C0")
        val inactiveColor = android.graphics.Color.parseColor("#EEF2FF")
        mapOf(
            modeAutoButton     to (tutorSession.mode == TutorMode.AUTO),
            modeExplainButton  to (tutorSession.mode == TutorMode.EXPLAIN),
            modePracticeButton to (tutorSession.mode == TutorMode.PRACTICE),
            modeEvaluateButton to (tutorSession.mode == TutorMode.EVALUATE)
        ).forEach { (btn, isActive) ->
            btn.backgroundTintList = ColorStateList.valueOf(if (isActive) activeColor else inactiveColor)
            btn.setTextColor(if (isActive) android.graphics.Color.WHITE else activeColor)
        }
    }

    override fun onStop() {
        super.onStop()
        metricsTracker.endSession(this)
    }

    override fun onDestroy() {
        voiceManager.destroy()
        ttsManager.destroy()
        super.onDestroy()
    }
}

