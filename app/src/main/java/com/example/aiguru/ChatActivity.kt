package com.example.aiguru

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
import android.view.Menu
import android.view.MenuItem
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
import com.example.aiguru.BuildConfig
import com.example.aiguru.adapters.MessageAdapter
import com.example.aiguru.chat.ChatHistoryRepository
import com.example.aiguru.chat.GroqApiClient
import com.example.aiguru.chat.NotesRepository
import com.example.aiguru.models.Flashcard
import com.example.aiguru.models.Message
import com.example.aiguru.models.TutorMode
import com.example.aiguru.models.TutorSession
import com.example.aiguru.utils.ChapterMetricsTracker
import com.example.aiguru.utils.MediaManager
import com.example.aiguru.utils.PromptRepository
import com.example.aiguru.utils.SessionManager
import com.example.aiguru.utils.TTSCallback
import com.example.aiguru.utils.TextToSpeechManager
import com.example.aiguru.utils.VoiceManager
import com.example.aiguru.utils.VoiceRecognitionCallback
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

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

    // ── Session ───────────────────────────────────────────────────────────────
    private lateinit var subjectName: String
    private lateinit var chapterName: String

    // ── Modular Components ────────────────────────────────────────────────────
    private lateinit var groqClient:   GroqApiClient
    private lateinit var historyRepo:  ChatHistoryRepository
    private lateinit var notesRepo:    NotesRepository
    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager:   TextToSpeechManager
    private lateinit var mediaManager: MediaManager
    private lateinit var metricsTracker: ChapterMetricsTracker

    private var selectedImageUri: Uri? = null
    private var cameraImageUri: Uri? = null
    private var isListening = false

    // ── Interactive Voice Chat Mode ────────────────────────────────────────────
    private var isVoiceModeActive = false
    private lateinit var voiceChatButton: MaterialButton
    private lateinit var voiceChatBar: LinearLayout
    private lateinit var voiceChatStatus: TextView
    // Interrupt / barge-in state
    private var currentTTSText = ""
    private var isInterrupted = false
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

        subjectName   = intent.getStringExtra("subjectName") ?: "General"
        chapterName   = intent.getStringExtra("chapterName") ?: "Study Session"
        saveNotesType = intent.getStringExtra("saveNotesType")

        // Init prompt repository (reads tutor_prompts.json from assets once)
        PromptRepository.init(this)

        val userId = SessionManager.getFirestoreUserId(this)
        val db     = FirebaseFirestore.getInstance()

        tutorSession = TutorSession(studentId = userId, subject = subjectName, chapter = chapterName)
        groqClient   = GroqApiClient(BuildConfig.GROQ_API_KEY)
        historyRepo  = ChatHistoryRepository(db, userId, subjectName, chapterName)
        notesRepo    = NotesRepository(db, userId, subjectName, chapterName)
        voiceManager = VoiceManager(this)
        ttsManager   = TextToSpeechManager(this)
        mediaManager = MediaManager(this)
        metricsTracker = ChapterMetricsTracker(subjectName, chapterName)

        initializeUI()

        historyRepo.loadHistory(
            onMessages = { msgs ->
                messageAdapter.addMessages(msgs)
                messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
            },
            onEmpty = { addWelcomeMessage() }
        )

        // Auto-prompt (e.g. from Notes button in ChapterActivity)
        intent.getStringExtra("autoPrompt")?.let { prompt ->
            messagesRecyclerView.post { sendMessage(prompt, autoSaveNotes = saveNotesType != null) }
        }

        // Pre-load PDF page passed from ChapterActivity (if any)
        val pdfPageFilePath = intent.getStringExtra("pdfPageFilePath")
        val pdfPageNumber   = intent.getIntExtra("pdfPageNumber", 1)
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
        voiceChatBar = findViewById(R.id.voiceChatBar)
        voiceChatStatus = findViewById(R.id.voiceChatStatus)

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
        voiceChatButton = findViewById(R.id.voiceChatButton)
        voiceChatButton.setOnClickListener {
            if (isVoiceModeActive) stopVoiceMode() else startVoiceMode()
        }
        voiceButton.setOnClickListener {
            if (isListening) voiceManager.stopListening() else checkPermissionAndStartListening()
        }
        imageButton.setOnClickListener { showImageSourceDialog() }
        pdfButton.setOnClickListener { openPdfPicker() }
        saveNotesButton.setOnClickListener { saveLastAIMessageAsNotes() }
        viewNotesButton.setOnClickListener { viewSavedNotes() }

        removeImageButton.setOnClickListener {
            selectedImageUri = null
            pdfPageBase64 = null
            imagePreviewStrip.visibility = View.GONE
            messageInput.setText("")
            bottomDescribeButton.visibility = View.GONE
        }

        formulaButton.setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.FORMULA_USED)
            sendMessage(PromptRepository.getQuickAction("formula", subjectName, chapterName))
        }
        practiceButton.setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.PRACTICE_USED)
            sendMessage(PromptRepository.getQuickAction("practice", subjectName, chapterName))
        }
        bottomDescribeButton.setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.EXPLAIN_USED)
            sendMessage(PromptRepository.getQuickAction("describe_image", subjectName, chapterName))
            messageInput.setText("")
        }
        findViewById<MaterialButton>(R.id.bottomExplainButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.EXPLAIN_USED)
            val hasMedia = selectedImageUri != null || pdfPageBase64 != null
            sendMessage(PromptRepository.getQuickAction(if (hasMedia) "explain_image" else "explain", subjectName, chapterName))
            if (hasMedia) messageInput.setText("")
        }
        findViewById<MaterialButton>(R.id.bottomSummarizeButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.SUMMARIZE_USED)
            val hasMedia = selectedImageUri != null || pdfPageBase64 != null
            sendMessage(PromptRepository.getQuickAction(if (hasMedia) "summarize_image" else "summarize", subjectName, chapterName))
            if (hasMedia) messageInput.setText("")
        }
        findViewById<MaterialButton>(R.id.bottomQuizButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.QUIZ_REQUESTED)
            sendMessage(PromptRepository.getQuickAction("quiz", subjectName, chapterName))
        }
        findViewById<MaterialButton>(R.id.bottomFormulaButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.FORMULA_USED)
            sendMessage(PromptRepository.getQuickAction("formula", subjectName, chapterName))
        }
        findViewById<MaterialButton>(R.id.bottomPracticeButton).setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.PRACTICE_USED)
            sendMessage(PromptRepository.getQuickAction("practice", subjectName, chapterName))
        }
    }

    private fun setupQuickActions() {
        mapOf(
            R.id.summarizeButton to "summarize",
            R.id.explainButton   to "explain",
            R.id.quizButton      to "quiz",
            R.id.notesButton     to "notes"
        ).forEach { (id, key) ->
            findViewById<MaterialButton>(id).setOnClickListener {
                sendMessage(PromptRepository.getQuickAction(key, subjectName, chapterName))
            }
        }
        findViewById<MaterialButton>(R.id.flashcardsButton).setOnClickListener { generateFlashcards() }
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

    private fun addWelcomeMessage() {
        messageAdapter.addMessage(Message(
            id          = UUID.randomUUID().toString(),
            content     = PromptRepository.getWelcomeMessage(subjectName, chapterName),
            isUser      = false,
            messageType = Message.MessageType.TEXT
        ))
    }

    private fun sendMessage(userText: String, autoSaveNotes: Boolean = false) {
        val imageUri          = selectedImageUri.also { selectedImageUri = null }
        val capturedPdfBase64 = pdfPageBase64.also   { pdfPageBase64 = null }
        imagePreviewStrip.visibility = View.GONE

        val userMessage = Message(
            id          = UUID.randomUUID().toString(),
            content     = userText,
            isUser      = true,
            imageUrl    = imageUri?.toString(),
            messageType = if (imageUri != null) Message.MessageType.IMAGE else Message.MessageType.TEXT
        )
        messageAdapter.addMessage(userMessage)
        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
        showLoading(true)

        val sysPrompt  = TutorController.buildSystemPrompt(tutorSession) +
                         PromptRepository.getLanguageInstruction(currentLang)
        val ctxMessage = "Subject: $subjectName | Chapter: $chapterName\n\n$userText"

        if (isVoiceModeActive) setVoiceModeStatus("🤖 AI is thinking…", "#E65100")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rawResponse = when {
                    imageUri != null -> {
                        val b64 = mediaManager.uriToBase64(imageUri)
                        if (b64 != null) groqClient.callWithImage(sysPrompt, ctxMessage, b64)
                        else             groqClient.callText(sysPrompt, userText)
                    }
                    capturedPdfBase64 != null -> groqClient.callWithImage(sysPrompt, ctxMessage, capturedPdfBase64)
                    else                      -> groqClient.callText(sysPrompt, userText)
                }

                historyRepo.saveMessage(userMessage.copy(imageUrl = null))

                runOnUiThread {
                    showLoading(false)
                    if (rawResponse != null) {
                        val reply = TutorController.parseResponse(rawResponse)
                        TutorController.updateSession(tutorSession, reply.intent, userText)
                        updateModeChipStates()
                        val aiMsg = Message(UUID.randomUUID().toString(), reply.response, false)
                        messageAdapter.addMessage(aiMsg)
                        historyRepo.saveMessage(aiMsg)
                        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                        if (lastInputWasVoice || isVoiceModeActive) {
                            lastInputWasVoice = false
                            val voiceText = TutorController.trimForVoice(reply.response)
                            if (isVoiceModeActive) {
                                currentTTSText = voiceText
                                setVoiceModeStatus("🔊 AI is speaking…", "#1565C0")
                            }
                            ttsManager.setLocale(Locale.forLanguageTag(currentLang))
                            ttsManager.speak(voiceText, object : TTSCallback {
                                override fun onStart() {
                                    // After 700ms warmup, start barge-in listener
                                    if (isVoiceModeActive) {
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            if (isVoiceModeActive && ttsManager.isSpeaking()) {
                                                voiceManager.startInterruptListening(interruptCallback, currentLang)
                                            }
                                        }, 700)
                                    }
                                }
                                override fun onComplete() {
                                    runOnUiThread {
                                        voiceManager.stopInterruptListening()
                                        if (isVoiceModeActive && !isInterrupted) startVoiceLoopListening()
                                        isInterrupted = false
                                    }
                                }
                                override fun onError(error: String) {
                                    runOnUiThread {
                                        voiceManager.stopInterruptListening()
                                        if (isVoiceModeActive) startVoiceLoopListening()
                                    }
                                }
                            })
                        }
                        if (autoSaveNotes && saveNotesType != null) {
                            notesRepo.save(reply.response, saveNotesType!!)
                        }
                    } else {
                        showError("Couldn't get a response. Check your connection and try again.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showLoading(false); showError("Error: ${e.message ?: "Unknown error"}") }
            }
        }
    }

    private fun generateFlashcards() {
        val prompt = PromptRepository.getQuickAction("flashcards", subjectName, chapterName)
        messageAdapter.addMessage(Message(UUID.randomUUID().toString(), "🃏 Generating revision flashcards for $chapterName…", true))
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val sysPrompt = TutorController.buildSystemPrompt(tutorSession)
            val raw = groqClient.callText(sysPrompt, prompt)
            runOnUiThread {
                showLoading(false)
                if (raw != null) {
                    val cards = parseFlashcards(raw)
                    if (cards.isNotEmpty()) {
                        messageAdapter.addMessage(Message(UUID.randomUUID().toString(), "✅ ${cards.size} flashcards ready! Opening revision mode…", false))
                        startActivity(Intent(this@ChatActivity, RevisionActivity::class.java).putExtra("flashcards", ArrayList(cards)))
                    } else showError("Could not parse flashcards. Please try again.")
                } else showError("Failed to generate flashcards. Check your connection.")
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
        val lastAi = messageAdapter.getLastAIMessage() ?: run {
            Toast.makeText(this, "Generate notes first, then tap 💾 Save Notes.", Toast.LENGTH_SHORT).show()
            return
        }
        notesRepo.save(
            content   = lastAi.content,
            type      = saveNotesType ?: "chapter",
            onSuccess = {
                metricsTracker.recordEvent(ChapterMetricsTracker.EventType.NOTES_SAVED)
                runOnUiThread { Toast.makeText(this, "✅ Notes saved!", Toast.LENGTH_SHORT).show() }
            },
            onFailure = {
                runOnUiThread { Toast.makeText(this, "Failed to save notes.", Toast.LENGTH_SHORT).show() }
            }
        )
    }

    private fun viewSavedNotes() {
        notesRepo.loadAll(
            onResult  = { text ->
                AlertDialog.Builder(this)
                    .setTitle("📋 Saved Notes — $chapterName")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
            },
            onEmpty   = { Toast.makeText(this, "No saved notes yet — generate and save notes first!", Toast.LENGTH_SHORT).show() },
            onFailure = { Toast.makeText(this, "Couldn't load notes.", Toast.LENGTH_SHORT).show() }
        )
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
            isListening = false
            if (!isVoiceModeActive) resetVoiceButton()
            if (text.isNotEmpty()) {
                lastInputWasVoice = true
                isInterrupted = false   // clear — good result received
                messageInput.setText("")
                if (isVoiceModeActive) setVoiceModeStatus("🤖 AI is thinking…", "#E65100")
                sendMessage(text)
            } else {
                if (isVoiceModeActive) startVoiceLoopListening()
                else messageInput.setText(text)
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
        runOnUiThread {
            if (isVoiceModeActive) {
                // Automatically retry — errors like no-match or timeout are common
                startVoiceLoopListening()
            } else {
                resetVoiceButton()
            }
        }
    }

    override fun onListeningStarted() {}

    override fun onListeningFinished() {
        runOnUiThread {
            isListening = false
            if (!isVoiceModeActive) resetVoiceButton()
        }
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

    // ── Interactive Voice Chat Mode ───────────────────────────────────────────

    private fun startVoiceMode() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE
            )
            return
        }
        isVoiceModeActive = true
        isInterrupted = false
        voiceButton.isEnabled = false
        voiceChatBar.visibility = View.VISIBLE
        listeningIndicator.visibility = View.GONE
        voiceChatButton.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#E53935"))
        Toast.makeText(this, "🎙️ Voice mode ON — just speak!", Toast.LENGTH_SHORT).show()
        startVoiceLoopListening()
    }

    private fun stopVoiceMode() {
        isVoiceModeActive = false
        isInterrupted = false
        ttsManager.stop()
        voiceManager.stopInterruptListening()
        if (isListening) { voiceManager.stopListening(); isListening = false }
        voiceButton.isEnabled = true
        voiceChatBar.visibility = View.GONE
        voiceChatButton.text = "🎙️"
        voiceChatButton.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#E8F5E9"))
        Toast.makeText(this, "Voice mode OFF", Toast.LENGTH_SHORT).show()
    }

    private fun startVoiceLoopListening() {
        if (!isVoiceModeActive) return
        isListening = true
        setVoiceModeStatus("🎙️ Listening… speak now", "#2E7D32")
        voiceManager.startListening(this, currentLang)
    }

    private fun setVoiceModeStatus(text: String, colorHex: String) {
        voiceChatStatus.text = text
        voiceChatStatus.setTextColor(android.graphics.Color.parseColor(colorHex))
    }

    /**
     * Called by the interrupt recognizer while TTS is speaking.
     * Uses partial results + echo filter to detect real user barge-in.
     */
    private val interruptCallback = object : VoiceRecognitionCallback {
        override fun onPartialResults(text: String) {
            if (!isVoiceModeActive || !ttsManager.isSpeaking() || text.length < 3) return
            // Echo filter: Skip if partial text matches the beginning of what TTS is saying
            val normalizedTTS = currentTTSText.lowercase()
            val normalizedText = text.lowercase().trim()
            if (normalizedTTS.contains(normalizedText)) return
            // Real user speech — barge-in!
            triggerBargein()
        }

        override fun onBeginningOfSpeech() {
            // Secondary signal: if TTS is playing and user starts speaking, stop TTS
            // (gives instant responsiveness; onPartialResults will confirm with real text)
        }

        override fun onResults(text: String) {
            // Interrupt recognizer captured a full utterance (user spoke while TTS was playing)
            // This can also handle the case where partial didn't trigger (short utterance).
            if (!isVoiceModeActive || text.length < 2) return
            val normalizedTTS = currentTTSText.lowercase()
            if (!normalizedTTS.contains(text.lowercase().trim())) {
                triggerBargein()
            }
        }

        override fun onError(error: String) { /* ignore — interrupt recognizer errors are expected */ }
        override fun onListeningStarted() {}
        override fun onListeningFinished() {}
    }

    private fun triggerBargein() {
        if (isInterrupted) return // already triggered
        isInterrupted = true
        ttsManager.stop()
        voiceManager.stopInterruptListening()
        runOnUiThread {
            setVoiceModeStatus("\ud83c\udf99\ufe0f Listening (interrupted)\u2026", "#6A1B9A")
        }
        // Now start the main recognizer to capture the full user utterance
        isListening = true
        voiceManager.startListening(this, currentLang)
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
        if (isVoiceModeActive) stopVoiceMode()
        voiceManager.destroy()
        ttsManager.destroy()
        super.onDestroy()
    }
}

