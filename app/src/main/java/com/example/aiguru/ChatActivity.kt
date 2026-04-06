package com.example.aiguru

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.aiguru.BuildConfig
import com.example.aiguru.adapters.PageListAdapter
import com.example.aiguru.adapters.MessageAdapter
import com.example.aiguru.chat.ChatHistoryRepository
import com.example.aiguru.chat.AiClient
import com.example.aiguru.chat.ConversationSummarizer
import com.example.aiguru.chat.GroqApiClient
import com.example.aiguru.chat.NotesRepository
import com.example.aiguru.chat.ServerProxyClient
import com.example.aiguru.models.ModelConfig
import com.example.aiguru.models.Flashcard
import com.example.aiguru.models.Message
import com.example.aiguru.models.TutorMode
import com.example.aiguru.models.TutorSession
import com.example.aiguru.utils.ChapterMetricsTracker
import com.example.aiguru.utils.MediaManager
import com.example.aiguru.utils.PdfPageManager
import com.example.aiguru.utils.PromptRepository
import com.example.aiguru.QuizSetupActivity
import com.example.aiguru.utils.SessionManager
import com.example.aiguru.utils.TTSCallback
import com.example.aiguru.utils.TextToSpeechManager
import com.example.aiguru.utils.VoiceManager
import com.example.aiguru.utils.VoiceRecognitionCallback
import com.example.aiguru.chat.PageAnalyzer
import com.example.aiguru.config.AdminConfigRepository
import com.example.aiguru.config.PlanEnforcer
import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.models.PageContent
import com.example.aiguru.models.UserMetadata
import com.google.android.material.button.MaterialButton
import com.yalantis.ucrop.UCrop
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

class ChatActivity : BaseActivity(), VoiceRecognitionCallback {

    private enum class LiveMicMode { PARTIAL, FULL }

    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var loadingLayout: LinearLayout
    private lateinit var voiceButton: MaterialButton
    private lateinit var imageButton: MaterialButton
    private lateinit var saveNotesButton: MaterialButton
    private lateinit var viewNotesButton: MaterialButton
    private lateinit var formulaButton: MaterialButton
    private lateinit var practiceButton: MaterialButton
    private lateinit var imagePreviewStrip: LinearLayout
    private lateinit var imagePreviewThumbnail: ImageView
    private lateinit var imagePreviewLabel: TextView
    private lateinit var removeImageButton: MaterialButton
    private lateinit var languageButton: MaterialButton
    private lateinit var listeningIndicator: TextView
    private lateinit var bottomDescribeButton: MaterialButton
    private lateinit var plusButton: MaterialButton
    private lateinit var quickActionsPanel: android.view.View
    private var isQuickActionsOpen = false

    // ── Chapter Workspace Drawer ─────────────────────────────────────────────
    private lateinit var chatDrawerLayout: DrawerLayout
    private lateinit var openPagesDrawerButton: MaterialButton
    private lateinit var pagesDrawerCloseButton: MaterialButton
    private lateinit var pagesDrawerAddPageButton: MaterialButton
    private lateinit var pagesDrawerTitle: TextView
    private lateinit var pagesDrawerHint: TextView
    private lateinit var pagesDrawerList: RecyclerView
    private val chapterPages = mutableListOf<String>()
    private val chapterImagePaths = mutableListOf<String>()
    private lateinit var chapterPagesAdapter: PageListAdapter
    private var isPdfChapterWorkspace = false
    private var chapterPdfAssetPath = ""
    private var chapterPdfId = ""
    private var chapterPdfPageCount = 0
    private var saveNextPickedImageToChapter = false
    private lateinit var pdfPageManager: PdfPageManager

    // ── Auto Explain Mode ──────────────────────────────────────────────────────
    // Global default: on. State is persisted in SharedPreferences so it stays
    // consistent across all chats and app restarts.
    private var isAutoExplainActive = true
    private lateinit var autoExplainButton: MaterialButton

    // ── Interactive Voice Chat Mode ────────────────────────────────────────────
    private var isVoiceModeActive = false
    private var liveMicMode = LiveMicMode.PARTIAL
    private lateinit var voiceChatButton: MaterialButton
    private lateinit var voiceChatBar: LinearLayout
    private lateinit var voiceChatStatus: TextView
    private lateinit var voiceModeBadge: TextView
    private lateinit var liveModeChip: TextView
    private lateinit var waveBarContainer: LinearLayout
    private lateinit var waveBar1: View
    private lateinit var waveBar2: View
    private lateinit var waveBar3: View
    private lateinit var waveBar4: View
    private var currentTTSText = ""
    private var isInterrupted = false
    private val voiceStopWords = listOf("stop", "stop it", "wait", "wait wait", "hold on")

    // ── Session ───────────────────────────────────────────────────────────────
    private lateinit var subjectName: String
    private lateinit var chapterName: String
    private var userId = ""
    private var cachedMetadata = UserMetadata()

    // ── Modular Components ────────────────────────────────────────────────────
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var notesRepo: NotesRepository
    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var mediaManager: MediaManager
    private lateinit var metricsTracker: ChapterMetricsTracker

    private var selectedImageUri: Uri? = null
    private var cameraImageUri: Uri? = null
    /** URI used only for displaying a thumbnail in the user's message bubble (not sent to LLM). */
    private var pendingDisplayUri: Uri? = null
    private var isListening = false

    // ── Page Analysis ─────────────────────────────────────────────────────────
    /** Holds the analysis result for the currently-attached image/PDF page. */
    private var currentPageContent: PageContent? = null

    // ── Language ───────────────────────────────────────────────────────────────
    // Language for voice recognition, TTS, and LLM responses
    private var currentLang = "en-US"
    private var currentLangName = "English"
    private val LANGUAGES = linkedMapOf(
        "English" to "en-US",
        "हिंदी (Hindi)" to "hi-IN",
        "বাংলা (Bengali)" to "bn-IN",
        "తెలుగు (Telugu)" to "te-IN",
        "தமிழ் (Tamil)" to "ta-IN",
        "मराठी (Marathi)" to "mr-IN",
        "ಕನ್ನಡ (Kannada)" to "kn-IN",
        "ગુજરાતી (Gujarati)" to "gu-IN"
    )

    // Pre-loaded PDF page (base64 encoded) passed from ChapterActivity
    private var pdfPageBase64: String? = null

    // Base64 of the currently-attached gallery/camera image, stored at attach time
    // so sendMessage never needs to re-decode the URI (mirrors pdfPageBase64 for images).
    private var pendingImageBase64: String? = null

    // When set, the AI response from the next auto-send is also saved as notes
    private var saveNotesType: String? = null

    // ── Crop state ────────────────────────────────────────────────────────────
    /** true when UCrop was launched for a PDF page (vs gallery/camera image) */
    private var pendingCropIsPdf = false
    private var pendingCropPdfPageNumber = 0

    /** Kept so we can fall back to the full page if user cancels crop */
    private var pendingCropPdfFile: File? = null

    // ── Tutor System ──────────────────────────────────────────────────────────
    private lateinit var tutorSession: TutorSession
    private var lastInputWasVoice = false

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) launchCrop(uri, isPdf = false)
            else saveNextPickedImageToChapter = false
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) launchCrop(cameraImageUri!!, isPdf = false)
            else saveNextPickedImageToChapter = false
        }

    /** Receives the cropped image result from UCrop. */
    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            when (result.resultCode) {
                RESULT_OK -> {
                    val croppedUri =
                        data?.let { UCrop.getOutput(it) } ?: return@registerForActivityResult
                    if (pendingCropIsPdf) {
                        // Read cropped region → base64 → show preview + run analysis
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val stream =
                                    contentResolver.openInputStream(croppedUri) ?: return@launch
                                val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                                stream.close()
                                if (bmp == null) return@launch
                                val baos = ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                                bmp.recycle()
                                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                val pageLabel = if (pendingCropPdfPageNumber > 0)
                                    "Page $pendingCropPdfPageNumber \u2702\ufe0f cropped" else "\u2702\ufe0f Cropped region"
                                withContext(Dispatchers.Main) {
                                    pdfPageBase64 = b64
                                    pendingDisplayUri = croppedUri
                                    currentPageContent = null
                                    imagePreviewStrip.visibility = View.VISIBLE
                                    Glide.with(this@ChatActivity).load(croppedUri).centerCrop()
                                        .into(imagePreviewThumbnail)
                                    imagePreviewLabel.text = pageLabel
                                    messageInput.setText("Explain this")
                                    messageInput.setSelection(messageInput.text.length)
                                    bottomDescribeButton.visibility = View.VISIBLE
                                }
                                PageAnalyzer.analyze(
                                    base64Image = b64,
                                    subject = subjectName,
                                    chapter = chapterName,
                                    pageNumber = pendingCropPdfPageNumber,
                                    sourceType = "pdf",
                                    onSuccess = { content ->
                                        currentPageContent = content
                                        persistLatestPageContext(content)
                                        val analyzed = if (pendingCropPdfPageNumber > 0)
                                            "Page $pendingCropPdfPageNumber \u2705 analyzed" else "\u2705 analyzed"
                                        runOnUiThread { imagePreviewLabel.text = analyzed }
                                    },
                                    onError = { err ->
                                        android.util.Log.w(
                                            "PageAnalyzer",
                                            "Cropped region analysis: $err"
                                        )
                                    }
                                )
                            } catch (e: Exception) {
                                android.util.Log.e(
                                    "ChatActivity",
                                    "Crop result processing failed: ${e.message}"
                                )
                            }
                        }
                    } else {
                        // Gallery / camera image — run normal image preview + analysis
                        showImagePreview(croppedUri)
                    }
                }

                UCrop.RESULT_ERROR -> {
                    android.util.Log.w(
                        "ChatActivity",
                        "UCrop error: ${data?.let { UCrop.getError(it)?.message }}"
                    )
                    if (pendingCropIsPdf) pendingCropPdfFile?.let {
                        applyFullPdfPage(
                            it,
                            pendingCropPdfPageNumber
                        )
                    } else {
                        saveNextPickedImageToChapter = false
                    }
                }

                else -> {
                    // User pressed back — fall back to full page (PDF) or do nothing (gallery)
                    if (pendingCropIsPdf) pendingCropPdfFile?.let {
                        applyFullPdfPage(
                            it,
                            pendingCropPdfPageNumber
                        )
                    } else {
                        saveNextPickedImageToChapter = false
                    }
                }
            }
        }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        pdfPageManager = PdfPageManager(this)

        // Load global blackboard-mode preference (defaults to ON)
        isAutoExplainActive = getSharedPreferences("user_prefs", MODE_PRIVATE)
            .getBoolean("blackboard_mode_on", true)

        subjectName = intent.getStringExtra("subjectName") ?: "General"
        chapterName = intent.getStringExtra("chapterName") ?: "Study Session"
        saveNotesType = intent.getStringExtra("saveNotesType")

        // Init prompt repository (reads tutor_prompts.json from assets once)
        PromptRepository.init(this)

        userId = SessionManager.getFirestoreUserId(this)
        cachedMetadata = cachedMetadata.copy(userId = userId)

        // Prime admin config + plans from Firestore (non-blocking, cached 1 h)
        AdminConfigRepository.fetchIfStale()

        // Load user plan metadata for enforcement
        FirestoreManager.getUserMetadata(userId, onSuccess = { meta ->
            if (meta != null) cachedMetadata = meta
        })

        tutorSession =
            TutorSession(
                studentId = userId,
                subject = subjectName,
                chapter = chapterName,
                mode = TutorMode.EXPLAIN
            )

        FirestoreManager.loadChapterContext(
            userId = userId,
            subject = subjectName,
            chapter = chapterName,
            onSuccess = { summary, systemContext ->
                tutorSession.chapterSummary = summary.orEmpty()
                tutorSession.latestPageContext = systemContext.orEmpty()
            },
            onFailure = {
                android.util.Log.w("ChatActivity", "Failed to load chapter context: ${it?.message}")
            }
        )

        historyRepo = ChatHistoryRepository(userId, subjectName, chapterName)
        notesRepo = NotesRepository(this, userId, subjectName, chapterName)
        voiceManager = VoiceManager(this)
        ttsManager = TextToSpeechManager(this)
        mediaManager = MediaManager(this)
        metricsTracker = ChapterMetricsTracker(subjectName, chapterName)

        initializeUI()
        initializeChapterWorkspaceDrawer()
        if (isAutoExplainActive) {
            Toast.makeText(this, "Blackboard mode on", Toast.LENGTH_SHORT).show()
        }

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
        val pdfPageNumber = intent.getIntExtra("pdfPageNumber", 1)
        if (pdfPageFilePath != null) {
            tutorSession.currentPage = pdfPageNumber
            preloadPdfPage(File(pdfPageFilePath), pdfPageNumber)
        }

        // Pre-load an image page passed from ChapterActivity (image chapters)
        intent.getStringExtra("imagePath")?.takeIf { it.isNotBlank() }?.let { path ->
            val uri = Uri.parse(path)
            showImagePreview(uri)
            messageInput.setText("Explain this page")
            messageInput.setSelection(messageInput.text.length)
        }
    }

    private fun initializeUI() {
        chatDrawerLayout = findViewById(R.id.chatDrawerLayout)

        findViewById<TextView>(R.id.chatHeaderTitle).text = chapterName
        findViewById<TextView>(R.id.chatHeaderSubtitle).text = "$subjectName · Tutor Session"

        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageAdapter = MessageAdapter(
            context = this,
            onVoiceClick = { msg ->
                ttsManager.setLocale(Locale.forLanguageTag(currentLang))
                val speechText = TutorController.prepareSpeechText(msg.content)
                ttsManager.speak(speechText, object : com.example.aiguru.utils.TTSCallback {
                    override fun onStart() {}
                    override fun onComplete() {}
                    override fun onError(error: String) {}
                })
            },
            onStopClick = { ttsManager.stop() },
            onImageClick = { msg ->
                msg.imageUrl?.let { uri ->
                    startActivity(
                        Intent(this, FullscreenImageActivity::class.java)
                            .putExtra(FullscreenImageActivity.EXTRA_IMAGE_URI, uri)
                    )
                }
            },
            onExplainClick = { msg ->
                val bbLimits = AdminConfigRepository.resolveEffectiveLimits(
                    cachedMetadata.planId, cachedMetadata.planLimits
                )
                val bbCheck = PlanEnforcer.check(
                    cachedMetadata, bbLimits, PlanEnforcer.FeatureType.BLACKBOARD
                )
                if (!bbCheck.allowed) {
                    showError(bbCheck.upgradeMessage)
                } else {
                    val convId = "${FirestoreManager.safeId(subjectName)}__${FirestoreManager.safeId(chapterName)}"
                    startActivity(
                        android.content.Intent(this, BlackboardActivity::class.java)
                            .putExtra(BlackboardActivity.EXTRA_MESSAGE, msg.content)
                            .putExtra(BlackboardActivity.EXTRA_MESSAGE_ID, msg.id)
                            .putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
                            .putExtra(BlackboardActivity.EXTRA_CONVERSATION_ID, convId)
                            .putExtra(BlackboardActivity.EXTRA_LANGUAGE_TAG, currentLang)
                    )
                }
            }
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

        saveNotesButton = findViewById(R.id.saveNotesButton)
        viewNotesButton = findViewById(R.id.viewNotesButton)
        formulaButton = findViewById(R.id.formulaButton)
        practiceButton = findViewById(R.id.practiceButton)
        imagePreviewStrip = findViewById(R.id.imagePreviewStrip)
        imagePreviewThumbnail = findViewById(R.id.imagePreviewThumbnail)
        imagePreviewLabel = findViewById(R.id.imagePreviewLabel)
        removeImageButton = findViewById(R.id.removeImageButton)
        languageButton = findViewById(R.id.languageButton)
        openPagesDrawerButton = findViewById(R.id.openPagesDrawerButton)
        pagesDrawerCloseButton = findViewById(R.id.pagesDrawerCloseButton)
        pagesDrawerAddPageButton = findViewById(R.id.pagesDrawerAddPageButton)
        pagesDrawerTitle = findViewById(R.id.pagesDrawerTitle)
        pagesDrawerHint = findViewById(R.id.pagesDrawerHint)
        pagesDrawerList = findViewById(R.id.pagesDrawerList)
        listeningIndicator = findViewById(R.id.listeningIndicator)
        bottomDescribeButton = findViewById(R.id.bottomDescribeButton)
        plusButton = findViewById(R.id.plusButton)
        quickActionsPanel = findViewById(R.id.quickActionsPanel)
        plusButton.setOnClickListener { toggleQuickActions() }
        voiceChatBar = findViewById(R.id.voiceChatBar)
        voiceChatStatus = findViewById(R.id.voiceChatStatus)
        voiceModeBadge = findViewById(R.id.voiceModeBadge)
        liveModeChip = findViewById(R.id.liveModeChip)
        waveBarContainer = findViewById(R.id.waveBarContainer)
        waveBar1 = findViewById(R.id.waveBar1)
        waveBar2 = findViewById(R.id.waveBar2)
        waveBar3 = findViewById(R.id.waveBar3)
        waveBar4 = findViewById(R.id.waveBar4)

        languageButton.setOnClickListener { showLanguagePicker() }
        openPagesDrawerButton.setOnClickListener {
            chatDrawerLayout.openDrawer(GravityCompat.START)
        }
        pagesDrawerCloseButton.setOnClickListener {
            chatDrawerLayout.closeDrawer(GravityCompat.START)
        }
        updateLanguageButton()
        updateLiveModeUi()

        setupButtons()
        setupQuickActions()

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
            if (isVoiceModeActive) stopVoiceMode() else showLiveModePickerAndStart()
        }

        autoExplainButton = findViewById(R.id.autoExplainButton)
        autoExplainButton.setOnClickListener {
            val limits = AdminConfigRepository.resolveEffectiveLimits(
                cachedMetadata.planId, cachedMetadata.planLimits
            )
            val check = PlanEnforcer.check(cachedMetadata, limits, PlanEnforcer.FeatureType.BLACKBOARD)
            if (!check.allowed) { showError(check.upgradeMessage); return@setOnClickListener }
            isAutoExplainActive = !isAutoExplainActive
            // Persist globally so all future chats open with the same setting
            getSharedPreferences("user_prefs", MODE_PRIVATE)
                .edit().putBoolean("blackboard_mode_on", isAutoExplainActive).apply()
            updateAutoExplainButton()
            Toast.makeText(
                this,
                if (isAutoExplainActive) "Blackboard mode on" else "Blackboard mode off",
                Toast.LENGTH_SHORT
            ).show()
        }
        updateAutoExplainButton()

        voiceButton.setOnClickListener {
            if (isListening) voiceManager.stopListening() else checkPermissionAndStartListening()
        }
        imageButton.setOnClickListener { showImageSourceDialog() }
        saveNotesButton.setOnClickListener { saveLastAIMessageAsNotes() }
        viewNotesButton.setOnClickListener { viewSavedNotes() }

        imagePreviewThumbnail.setOnClickListener {
            pendingDisplayUri?.let { uri ->
                startActivity(
                    Intent(this, FullscreenImageActivity::class.java)
                        .putExtra(FullscreenImageActivity.EXTRA_IMAGE_URI, uri.toString())
                )
            }
        }

        removeImageButton.setOnClickListener {
            selectedImageUri = null
            pdfPageBase64 = null
            pendingImageBase64 = null
            pendingDisplayUri = null
            currentPageContent = null
            imagePreviewStrip.visibility = View.GONE
            messageInput.setText("")
            bottomDescribeButton.visibility = View.GONE
        }

        formulaButton.setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.FORMULA_USED)
            sendMessage(PromptRepository.getQuickAction("formula", subjectName, chapterName))
            closeQuickActions()
        }
        practiceButton.setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.PRACTICE_USED)
            sendMessage(PromptRepository.getQuickAction("practice", subjectName, chapterName))
            closeQuickActions()
        }
        bottomDescribeButton.setOnClickListener {
            metricsTracker.recordEvent(ChapterMetricsTracker.EventType.EXPLAIN_USED)
            sendMessage(PromptRepository.getQuickAction("describe_image", subjectName, chapterName))
            messageInput.setText("")
            closeQuickActions()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.clearChatButton)
            .setOnClickListener { showClearChatConfirmation() }

    }

    private fun setupQuickActions() {
        mapOf(
            R.id.explainButton to "explain",
            R.id.notesButton to "notes"
        ).forEach { (id, key) ->
            findViewById<MaterialButton>(id).setOnClickListener {
                sendMessage(PromptRepository.getQuickAction(key, subjectName, chapterName))
                closeQuickActions()
            }
        }

        findViewById<MaterialButton>(R.id.quizButton).setOnClickListener {
            closeQuickActions()
            val chapterId = "${subjectName}_${chapterName}"
                .replace(" ", "_").lowercase().take(64)
            startActivity(
                Intent(this, QuizSetupActivity::class.java)
                    .putExtra("subjectName", subjectName)
                    .putExtra("chapterId", chapterId)
                    .putExtra("chapterTitle", chapterName)
            )
        }
        findViewById<MaterialButton>(R.id.flashcardsButton).setOnClickListener {
            generateFlashcards()
            closeQuickActions()
        }
    }

    private fun toggleQuickActions() {
        if (isQuickActionsOpen) {
            closeQuickActions()
        } else {
            openQuickActions()
        }
    }

    private fun openQuickActions() {
        quickActionsPanel.visibility = android.view.View.VISIBLE
        plusButton.text = "✕"
        isQuickActionsOpen = true
    }

    private fun closeQuickActions() {
        quickActionsPanel.visibility = android.view.View.GONE
        plusButton.text = "+"
        isQuickActionsOpen = false
    }

    private fun initializeChapterWorkspaceDrawer() {
        chapterPagesAdapter = PageListAdapter(
            pages = chapterPages,
            onView = { index -> onWorkspaceViewPage(index) },
            onAsk = { index -> onWorkspaceAskPage(index) }
        )
        pagesDrawerList.layoutManager = LinearLayoutManager(this)
        pagesDrawerList.adapter = chapterPagesAdapter

        pagesDrawerAddPageButton.setOnClickListener {
            saveNextPickedImageToChapter = true
            showImageSourceDialog()
        }

        val metaRaw = getSharedPreferences("chapters_prefs", MODE_PRIVATE)
            .getString("meta_${subjectName}_${chapterName}", null)
        if (metaRaw.isNullOrBlank()) {
            isPdfChapterWorkspace = false
            pagesDrawerTitle.text = "Pages • Image Chapter"
            pagesDrawerHint.text = "Select or add image pages to ask AI."
            loadImageWorkspacePages()
            return
        }

        try {
            val meta = org.json.JSONObject(metaRaw)
            isPdfChapterWorkspace = meta.optBoolean("isPdf", false)
            if (isPdfChapterWorkspace) {
                chapterPdfAssetPath = meta.optString("pdfAssetPath", "")
                chapterPdfId = meta.optString("pdfId", "")
                pagesDrawerTitle.text = "Pages • PDF Chapter"
                pagesDrawerHint.text = "View any page or attach a page to this chat."
                pagesDrawerAddPageButton.visibility = View.VISIBLE
                pagesDrawerAddPageButton.text = "Add Extra Image"
                loadPdfWorkspacePages()
            } else {
                pagesDrawerTitle.text = "Pages • Image Chapter"
                pagesDrawerHint.text = "Select or add image pages to ask AI."
                pagesDrawerAddPageButton.visibility = View.VISIBLE
                pagesDrawerAddPageButton.text = "Add Page"
                loadImageWorkspacePages()
            }
        } catch (_: Exception) {
            isPdfChapterWorkspace = false
            pagesDrawerTitle.text = "Pages"
            pagesDrawerHint.text = "Select or add image pages to ask AI."
            pagesDrawerAddPageButton.visibility = View.VISIBLE
            pagesDrawerAddPageButton.text = "Add Page"
            loadImageWorkspacePages()
        }
    }

    private fun loadPdfWorkspacePages() {
        if (chapterPdfAssetPath.isBlank() || chapterPdfId.isBlank()) {
            chapterPages.clear()
            chapterPages.add("PDF metadata missing")
            chapterPagesAdapter.notifyDataSetChanged()
            return
        }

        chapterPages.clear()
        chapterPages.add("Loading PDF pages...")
        chapterPagesAdapter.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = pdfPageManager.getPageCount(chapterPdfId, chapterPdfAssetPath)
                chapterPdfPageCount = count
                withContext(Dispatchers.Main) {
                    chapterPages.clear()
                    for (i in 1..count) chapterPages.add("Page $i")
                    chapterPagesAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    chapterPages.clear()
                    chapterPages.add("Failed to load PDF pages: ${e.message}")
                    chapterPagesAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun loadImageWorkspacePages() {
        chapterImagePaths.clear()
        chapterPages.clear()
        val key = "imgpages_${subjectName}_${chapterName}"
        val raw = getSharedPreferences("chapters_prefs", MODE_PRIVATE).getString(key, "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                if (item != null) {
                    val path = item.optString("path", "")
                    val ts = item.optString("timestamp", "")
                    if (path.isNotBlank()) {
                        chapterImagePaths.add(path)
                        chapterPages.add(if (ts.isBlank()) "Image Page ${i + 1}" else "Page • $ts")
                    }
                }
            }
        } catch (_: Exception) {
            // Keep empty state
        }
        if (chapterPages.isEmpty()) chapterPages.add("No pages yet. Tap Add Page.")
        chapterPagesAdapter.notifyDataSetChanged()
    }

    private fun onWorkspaceViewPage(index: Int) {
        if (isPdfChapterWorkspace) {
            if (chapterPdfPageCount <= 0) return
            startActivity(
                Intent(this, PageViewerActivity::class.java)
                    .putExtra("subjectName", subjectName)
                    .putExtra("chapterName", chapterName)
                    .putExtra("pdfId", chapterPdfId)
                    .putExtra("pdfAssetPath", chapterPdfAssetPath)
                    .putExtra("pageCount", chapterPdfPageCount)
                    .putExtra("startPage", index)
            )
            return
        }

        if (chapterImagePaths.isEmpty() || index !in chapterImagePaths.indices) return
        val uri = Uri.parse(chapterImagePaths[index])
        showImagePreview(uri)
        chatDrawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun onWorkspaceAskPage(index: Int) {
        if (isPdfChapterWorkspace) {
            if (chapterPdfPageCount <= 0) return
            val pageIndex = index
            tutorSession.currentPage = pageIndex + 1
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val pageFile = pdfPageManager.getPage(chapterPdfId, chapterPdfAssetPath, pageIndex)
                    withContext(Dispatchers.Main) {
                        preloadPdfPage(pageFile, pageIndex + 1)
                        chatDrawerLayout.closeDrawer(GravityCompat.START)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "Failed to render page: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }

        if (chapterImagePaths.isEmpty() || index !in chapterImagePaths.indices) return
        val uri = Uri.parse(chapterImagePaths[index])
        showImagePreview(uri)
        messageInput.setText("Explain this page")
        messageInput.setSelection(messageInput.text.length)
        chatDrawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun saveImagePageToChapter(uri: Uri) {
        val key = "imgpages_${subjectName}_${chapterName}"
        val prefs = getSharedPreferences("chapters_prefs", MODE_PRIVATE)
        val raw = prefs.getString(key, "[]") ?: "[]"
        val arr = try { org.json.JSONArray(raw) } catch (_: Exception) { org.json.JSONArray() }
        val timestamp = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        arr.put(org.json.JSONObject().apply {
            put("path", uri.toString())
            put("timestamp", timestamp)
        })
        prefs.edit().putString(key, arr.toString()).apply()
        loadImageWorkspacePages()
        Toast.makeText(this, "Page added to chapter", Toast.LENGTH_SHORT).show()
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
        voiceButton.backgroundTintList =
            ColorStateList.valueOf(android.graphics.Color.parseColor("#E53935"))
        listeningIndicator.visibility = View.VISIBLE
        voiceManager.startListening(this, currentLang)
    }

    private fun resetVoiceButton() {
        isListening = false
        voiceButton.text = "🎤"
        voiceButton.backgroundTintList =
            ColorStateList.valueOf(android.graphics.Color.parseColor("#E3F2FD"))
        listeningIndicator.visibility = View.GONE
    }

    private fun updateAutoExplainButton() {
        autoExplainButton.backgroundTintList = ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (isAutoExplainActive) "#FFF9C4" else "#F3F4F6")
        )
        autoExplainButton.setTextColor(
            android.graphics.Color.parseColor(if (isAutoExplainActive) "#E65100" else "#757575")
        )
        autoExplainButton.contentDescription =
            if (isAutoExplainActive) "Blackboard mode on" else "Blackboard mode off"
    }

    private fun showImagePreview(uri: Uri) {
        selectedImageUri = uri
        pendingDisplayUri = uri
        pendingImageBase64 = null   // will be set once base64 is decoded below
        if (saveNextPickedImageToChapter) {
            saveNextPickedImageToChapter = false
            if (!isPdfChapterWorkspace) saveImagePageToChapter(uri)
        }
        currentPageContent = null   // clear any previous analysis
        metricsTracker.recordEvent(ChapterMetricsTracker.EventType.IMAGE_UPLOADED)
        imagePreviewStrip.visibility = View.VISIBLE
        Glide.with(this).load(uri).centerCrop().into(imagePreviewThumbnail)
        imagePreviewLabel.text = mediaManager.getFileInfo(uri)
        // Auto-populate input with "Explain" and show image-specific chip
        messageInput.setText("Explain this")
        messageInput.setSelection(messageInput.text.length)
        bottomDescribeButton.visibility = View.VISIBLE

        // Kick off background page analysis using Groq vision
        lifecycleScope.launch(Dispatchers.IO) {
            val b64 = mediaManager.uriToBase64(uri) ?: return@launch
            // Pre-store base64 so sendMessage never re-decodes the URI
            pendingImageBase64 = b64
            PageAnalyzer.analyze(
                base64Image = b64,
                subject = subjectName,
                chapter = chapterName,
                sourceType = "image",
                onSuccess = { content ->
                    currentPageContent = content
                    persistLatestPageContext(content)
                    runOnUiThread {
                        imagePreviewLabel.text = "${mediaManager.getFileInfo(uri)} · ✅ analyzed"
                    }
                },
                onError = { err ->
                    android.util.Log.w("PageAnalyzer", "Image analysis failed: $err")
                }
            )
        }
    }

    /**
     * Loads a rendered PDF page file as Base64 and shows it in the image preview strip
     * so the user can immediately ask questions about that page.
     */
    /**
     * Opens UCrop so the student can select a specific region of the PDF page to ask about.
     * Falls back to [applyFullPdfPage] if the user cancels or UCrop fails.
     */
    private fun preloadPdfPage(pageFile: File, pageNumber: Int) {
        if (!pageFile.exists()) return
        val sourceUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", pageFile)
        // Post so the activity window is fully attached before UCrop launches
        messagesRecyclerView.post {
            launchCrop(sourceUri, isPdf = true, pdfPageNumber = pageNumber, pdfFile = pageFile)
        }
    }

    /**
     * Loads the entire (uncropped) PDF page into the preview strip and kicks off background
     * analysis. Used when the student cancels the crop UI or UCrop fails.
     */
    private fun applyFullPdfPage(pageFile: File, pageNumber: Int) {
        if (!pageFile.exists()) return
        val bmp = BitmapFactory.decodeFile(pageFile.absolutePath) ?: return
        val baos = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
        bmp.recycle()
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        pdfPageBase64 = b64
        pendingDisplayUri = Uri.fromFile(pageFile)
        currentPageContent = null

        imagePreviewStrip.visibility = View.VISIBLE
        Glide.with(this).load(pageFile).centerCrop().into(imagePreviewThumbnail)
        imagePreviewLabel.text = "Page $pageNumber"
        messageInput.setText("Explain this page")
        messageInput.setSelection(messageInput.text.length)
        bottomDescribeButton.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            PageAnalyzer.analyze(
                base64Image = b64,
                subject = subjectName,
                chapter = chapterName,
                pageNumber = pageNumber,
                sourceType = "pdf",
                onSuccess = { content ->
                    currentPageContent = content
                    persistLatestPageContext(content)
                    runOnUiThread { imagePreviewLabel.text = "Page $pageNumber · ✅ analyzed" }
                },
                onError = { err ->
                    android.util.Log.w("PageAnalyzer", "PDF page analysis failed: $err")
                }
            )
        }
    }

    /**
     * Launches UCrop for [sourceUri].
     * For PDF pages, saves state so the result handler knows which page to update.
     */
    private fun launchCrop(
        sourceUri: Uri,
        isPdf: Boolean,
        pdfPageNumber: Int = 0,
        pdfFile: File? = null
    ) {
        pendingCropIsPdf = isPdf
        pendingCropPdfPageNumber = pdfPageNumber
        pendingCropPdfFile = pdfFile

        // Save to permanent filesDir (not cacheDir) so the image survives app restarts
        val imagesDir = File(filesDir, "chat_images").also { it.mkdirs() }
        val destFile = File(imagesDir, "crop_${System.currentTimeMillis()}.jpg")
        val options = UCrop.Options().apply {
            setToolbarTitle(if (isPdf) "Select Region to Ask About" else "Crop Image")
            setToolbarColor(getColor(android.R.color.white))
            setToolbarWidgetColor(getColor(android.R.color.black))
            setActiveControlsWidgetColor(android.graphics.Color.parseColor("#1565C0"))
            setFreeStyleCropEnabled(true)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setHideBottomControls(false)
            withMaxResultSize(1920, 1920)
            setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            // Make the overlay outside crop area a semi-transparent black
            setDimmedLayerColor(android.graphics.Color.parseColor("#88000000"))
        }
        try {
            // Decode image size to set a large initial crop window
            val uCrop = UCrop.of(sourceUri, Uri.fromFile(destFile))
                .withOptions(options)
                .withAspectRatio(0f, 0f)
                .withMaxResultSize(1920, 1920)
            cropLauncher.launch(uCrop.getIntent(this))
        } catch (e: Exception) {
            android.util.Log.w("ChatActivity", "UCrop launch failed: ${e.message}")
            if (isPdf) pdfFile?.let { applyFullPdfPage(it, pdfPageNumber) }
            else showImagePreview(sourceUri)
        }
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
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE
            )
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraImageUri =
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        cameraImageUri?.let { cameraLauncher.launch(it) }
    }

    private fun addWelcomeMessage() {
        messageAdapter.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                content = PromptRepository.getWelcomeMessage(subjectName, chapterName),
                isUser = false,
                messageType = Message.MessageType.TEXT
            )
        )
    }

    private fun showClearChatConfirmation() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear Chat")
            .setMessage("Delete all messages in this chat? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                historyRepo.clearHistory(
                    onSuccess = {
                        messageAdapter.clear()
                        addWelcomeMessage()
                        Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = {
                        Toast.makeText(this, "Failed to clear chat", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildAiClient(): AiClient {
        val cfg = AdminConfigRepository.config
        return ServerProxyClient(
            serverUrl = cfg.serverUrl.ifBlank { "http://108.181.187.227:8003" },
            modelName = "",
            apiKey = cfg.serverApiKey,
            userId = userId
        )
    }

    private fun sendMessage(userText: String, autoSaveNotes: Boolean = false) {
        val imageUri = selectedImageUri.also { selectedImageUri = null }
        val capturedPdfBase64 = pdfPageBase64.also { pdfPageBase64 = null }
        // Capture pre-stored image base64 — avoids re-decoding the URI at send time.
        // For PDF, the bytes were already in capturedPdfBase64. For gallery/camera,
        // they were stored in pendingImageBase64 at attach time (analogous to PDF).
        val capturedImageBase64 = pendingImageBase64.also { pendingImageBase64 = null }
        var capturedPageContent = currentPageContent.also { currentPageContent = null }
        val capturedDisplayUri = pendingDisplayUri.also { pendingDisplayUri = null }
        var serverPageTranscript: String? = null
        val hadVisualAttachment = imageUri != null || capturedPdfBase64 != null

        // ── Plan enforcement check ────────────────────────────────────────────
        val featureType = when {
            imageUri != null -> PlanEnforcer.FeatureType.IMAGE_UPLOAD
            capturedPdfBase64 != null -> PlanEnforcer.FeatureType.PDF_UPLOAD
            else -> PlanEnforcer.FeatureType.TEXT_CHAT
        }
        val effectiveLimits = AdminConfigRepository.resolveEffectiveLimits(
            cachedMetadata.planId, cachedMetadata.planLimits
        )
        val planCheck = PlanEnforcer.check(cachedMetadata, effectiveLimits, featureType)
        if (!planCheck.allowed) {
            if (imageUri != null) selectedImageUri = imageUri
            if (capturedPdfBase64 != null) pdfPageBase64 = capturedPdfBase64
            if (capturedDisplayUri != null) pendingDisplayUri = capturedDisplayUri
            showError(planCheck.upgradeMessage)
            // Navigate to subscription screen directly when the plan has expired
            if (planCheck.limitType == PlanEnforcer.LimitType.PLAN_EXPIRED) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startActivity(
                        android.content.Intent(this, SubscriptionActivity::class.java)
                            .putExtra("schoolId", com.example.aiguru.utils.SessionManager.getSchoolId(this))
                    )
                }, 1500)
            }
            return
        }

        imagePreviewStrip.visibility = View.GONE
        bottomDescribeButton.visibility = View.GONE

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            content = userText,
            isUser = true,
            imageUrl = (imageUri ?: capturedDisplayUri)?.toString(),
            messageType = if (imageUri != null || capturedDisplayUri != null)
                Message.MessageType.IMAGE else Message.MessageType.TEXT
        )
        messageAdapter.addMessage(userMessage)
        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
        showLoading(true)

        val sysPrompt = TutorController.buildSystemPrompt(tutorSession) +
                PromptRepository.getLanguageInstruction(currentLang)
        val ctxMessage = userText   // plain question — context is conveyed via history + page_id

        // Snapshot messages on main thread — adapter must not be read from IO thread
        val recentMsgs = messageAdapter.getMessages()
            .filter { it.content.isNotBlank() && it.id != userMessage.id }
            .takeLast(10)
        // historyStrings is built inside the coroutine after optional inline page analysis
        val pageId =
            "${FirestoreManager.safeId(subjectName)}__${FirestoreManager.safeId(chapterName)}"
        val studentLevel = cachedMetadata.grade.filter { it.isDigit() }.toIntOrNull() ?: 5

        // Streaming state — mutated only from runOnUiThread
        val streamingId = UUID.randomUUID().toString()
        val streamingMsg = Message(id = streamingId, content = "", isUser = false)
        val accumulated = StringBuilder()
        var loadingHidden = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                historyRepo.saveMessage(userMessage)

                // ── Inline page analysis at send time ─────────────────────────────
                // Runs if the background analysis (triggered on attach) wasn't completed
                // or failed (e.g. invalid API key). PageAnalyzer.analyze() is synchronous
                // on IO thread — it blocks until the Groq vision response is received.
                if (capturedPageContent == null && (imageUri != null || capturedPdfBase64 != null)) {
                    android.util.Log.d("PageContext", "Background analysis not ready — running inline Groq analysis")
                    // Use pre-stored base64 for images (never re-decode the URI)
                    val b64 = if (imageUri != null) capturedImageBase64 ?: mediaManager.uriToBase64(imageUri)
                    else capturedPdfBase64
                    if (b64 != null) {
                        val srcType = if (capturedPdfBase64 != null) "pdf" else "image"
                        val pNum = if (srcType == "pdf") (tutorSession.currentPage.takeIf { it > 0 }
                            ?: 1) else 0
                        PageAnalyzer.analyze(
                            base64Image = b64,
                            subject = subjectName,
                            chapter = chapterName,
                            pageNumber = pNum,
                            sourceType = srcType,
                            onSuccess = { content ->
                                android.util.Log.d("PageContext",
                                    "Inline Groq analysis SUCCESS: transcript=${content.transcript.length} chars")
                                capturedPageContent = content
                                persistLatestPageContext(content)
                            },
                            onError = { err ->
                                android.util.Log.e("PageContext",
                                    "Inline Groq analysis FAILED: $err")
                            }
                        )
                    } else {
                        android.util.Log.e("PageContext", "Inline analysis: could not decode image to base64")
                    }
                } else if (capturedPageContent != null) {
                    android.util.Log.d("PageContext",
                        "Background Groq analysis was ready: transcript=${capturedPageContent?.transcript?.length} chars")
                }

                // Build history now — capturedPageContent may have been set by inline analysis above
                val recentHistory =
                    recentMsgs.map { if (it.isUser) "user: ${it.content}" else "assistant: ${it.content}" }

                // ── Page context for history ───────────────────────────────────────
                // First message with an image: use the freshly-analyzed PageContent.
                // Follow-up messages (no new image): inject the saved transcript so the
                // LLM always knows what page the student is referring to.
                val pageContextEntry: List<String> = when {
                    capturedPageContent != null ->
                        listOf("system_context: ${capturedPageContent!!.toContextSummary()}")
                    tutorSession.latestPageContext.isNotBlank() ->
                        listOf("system_context: Page transcript: ${
                            tutorSession.latestPageContext.take(500)
                                .let { if (tutorSession.latestPageContext.length > 500) "$it…" else it }
                        }")
                    else -> emptyList()
                }
                val historyStrings = pageContextEntry + recentHistory

                val onToken: (String) -> Unit = { token ->
                    accumulated.append(token)
                    runOnUiThread {
                        if (!loadingHidden) {
                            loadingHidden = true
                            showLoading(false)
                            messageAdapter.addMessage(streamingMsg)
                        }
                        messageAdapter.updateMessage(streamingId, accumulated.toString())
                        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                    }
                }

                val onDone: (Int, Int, Int) -> Unit = { inputTok, outputTok, totalTok ->
                    android.util.Log.d(
                        "TokenDebug",
                        "[ChatActivity] onDone received in=$inputTok out=$outputTok total=$totalTok"
                    )
                    // Persist token counters to Firestore (async, runs on IO thread)
                    if (totalTok > 0) PlanEnforcer.recordTokensUsed(userId, totalTok, inputTok, outputTok)
                    // Save page analysis to Firestore after it has been used in this response
                    android.util.Log.d("PageContext",
                        "onDone: capturedPageContent=${capturedPageContent != null} " +
                        "hadVisualAttachment=$hadVisualAttachment serverTranscript=${serverPageTranscript?.length ?: 0} chars")
                    if (capturedPageContent != null) {
                        persistLatestPageContext(capturedPageContent)
                    } else if (hadVisualAttachment && !serverPageTranscript.isNullOrBlank()) {
                        val sourceType = if (capturedPdfBase64 != null) "pdf" else "image"
                        val inferredPageNumber = if (sourceType == "pdf") {
                            tutorSession.currentPage.takeIf { it > 0 } ?: 1
                        } else 0
                        val serverPage = PageContent(
                            pageId = PageAnalyzer.generatePageId(subjectName, chapterName),
                            subject = subjectName,
                            chapter = chapterName,
                            pageNumber = inferredPageNumber,
                            sourceType = sourceType,
                            transcript = serverPageTranscript.orEmpty(),
                            analyzedAt = System.currentTimeMillis()
                        )
                        persistLatestPageContext(serverPage)
                    }
                    runOnUiThread {
                        // Update in-memory counter so next enforcement check uses fresh numbers
                        if (totalTok > 0) {
                            cachedMetadata = cachedMetadata.copy(
                                tokensToday = cachedMetadata.tokensToday + totalTok,
                                tokensThisMonth = cachedMetadata.tokensThisMonth + totalTok,
                                tokensUpdatedAt = System.currentTimeMillis()
                            )
                        }
                        showLoading(false)
                        val rawResponse = accumulated.toString()
                        if (rawResponse.isNotEmpty()) {
                            val reply = TutorController.parseResponse(rawResponse)
                            TutorController.updateSession(tutorSession, reply.intent, userText)
                            messageAdapter.updateMessage(streamingId, reply.response)
                            val finalMsg = Message(streamingId, reply.response, false)
                            val tokensToSave = totalTok.takeIf { it > 0 }
                            android.util.Log.d(
                                "TokenDebug",
                                "[ChatActivity] saving AI msg tokens=$tokensToSave in=$inputTok out=$outputTok"
                            )
                            historyRepo.saveMessage(finalMsg, tokens = tokensToSave,
                                inputTokens = inputTok.takeIf { it > 0 },
                                outputTokens = outputTok.takeIf { it > 0 })
                            messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                            if ((lastInputWasVoice || isVoiceModeActive) && !isAutoExplainActive) {
                                lastInputWasVoice = false
                                val voiceText = if (isVoiceModeActive && liveMicMode == LiveMicMode.FULL)
                                    TutorController.prepareSpeechText(reply.response)
                                else
                                    TutorController.prepareSpeechTextBrief(reply.response)
                                if (isVoiceModeActive) {
                                    currentTTSText = voiceText
                                    val speakingLabel = if (liveMicMode == LiveMicMode.FULL) {
                                        "🔊 AI is speaking (Full Live)…"
                                    } else {
                                        "🔊 AI is speaking (Partial Live)…"
                                    }
                                    setVoiceModeStatus(speakingLabel, "#1565C0")
                                }
                                ttsManager.setLocale(Locale.forLanguageTag(currentLang))
                                ttsManager.speak(voiceText, object : TTSCallback {
                                    override fun onStart() {
                                        if (isVoiceModeActive && liveMicMode == LiveMicMode.FULL) {
                                            android.os.Handler(android.os.Looper.getMainLooper())
                                                .postDelayed({
                                                    if (isVoiceModeActive &&
                                                        liveMicMode == LiveMicMode.FULL &&
                                                        ttsManager.isSpeaking()) {
                                                        voiceManager.startInterruptListening(
                                                            interruptCallback,
                                                            currentLang
                                                        )
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
                            // ── Auto Explain Mode ──────────────────────────────────────────────
                            if (isAutoExplainActive) {
                                // Skip TTS — blackboard handles audio; clean up voice state
                                lastInputWasVoice = false
                                if (isVoiceModeActive) {
                                    setVoiceModeStatus("🎯 Blackboard open…", "#6A1B9A")
                                    voiceManager.stopInterruptListening()
                                }
                                val convId = "${FirestoreManager.safeId(subjectName)}__${FirestoreManager.safeId(chapterName)}"
                                startActivity(
                                    android.content.Intent(this@ChatActivity, BlackboardActivity::class.java)
                                        .putExtra(BlackboardActivity.EXTRA_MESSAGE, reply.response)
                                        .putExtra(BlackboardActivity.EXTRA_MESSAGE_ID, streamingId)
                                        .putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
                                        .putExtra(BlackboardActivity.EXTRA_CONVERSATION_ID, convId)
                                        .putExtra(BlackboardActivity.EXTRA_LANGUAGE_TAG, currentLang)
                                )
                            }
                        } else {
                            showError("Couldn't get a response. Check your connection and try again.")
                        }
                    }
                }

                val onError: (String) -> Unit = { err ->
                    runOnUiThread { showLoading(false); showError("Error: $err") }
                }

                val client = buildAiClient()
                val imageDataJson = capturedPageContent?.toImageDataJson()
                when {
                    imageUri != null -> {
                        // Use pre-stored base64 (encoded at attach time) — same pattern as PDF.
                        val b64 = capturedImageBase64 ?: mediaManager.uriToBase64(imageUri)
                        if (client is ServerProxyClient) {
                            client.streamChat(
                                ctxMessage,
                                pageId,
                                "normal",
                                currentLang,
                                studentLevel,
                                historyStrings,
                                imageDataJson,
                                b64,
                                { transcript -> serverPageTranscript = transcript },
                                onToken,
                                onDone,
                                onError
                            )
                        } else {
                            if (b64 != null) client.streamWithImage(
                                sysPrompt,
                                ctxMessage,
                                b64,
                                onToken,
                                onDone,
                                onError
                            )
                            else client.streamText(sysPrompt, ctxMessage, onToken, onDone, onError)
                        }
                    }

                    capturedPdfBase64 != null ->
                        if (client is ServerProxyClient)
                            client.streamChat(
                                ctxMessage,
                                pageId,
                                "normal",
                                currentLang,
                                studentLevel,
                                historyStrings,
                                imageDataJson,
                                capturedPdfBase64,
                                { transcript -> serverPageTranscript = transcript },
                                onToken,
                                onDone,
                                onError
                            )
                        else
                            client.streamWithImage(
                                sysPrompt,
                                ctxMessage,
                                capturedPdfBase64,
                                onToken,
                                onDone,
                                onError
                            )

                    else ->
                        if (client is ServerProxyClient)
                            client.streamChat(
                                ctxMessage,
                                pageId,
                                "normal",
                                currentLang,
                                studentLevel,
                                historyStrings,
                                null,
                                null,
                                null,
                                onToken,
                                onDone,
                                onError
                            )
                        else
                            client.streamText(sysPrompt, ctxMessage, onToken, onDone, onError)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "sendMessage crash: ${e.message}", e)
                runOnUiThread { showLoading(false); showError("Error: ${e.message}") }
            }
        }
    }

    private fun persistLatestPageContext(pageContent: PageContent?) {
        val page = pageContent ?: run {
            android.util.Log.w("PageContext", "persistLatestPageContext: pageContent is null — skipping")
            return
        }
        // Re-resolve userId at call time — Firebase Auth may not have been ready at onCreate
        val resolvedUserId = SessionManager.getFirestoreUserId(this).also { uid ->
            if (uid != userId) {
                android.util.Log.w("PageContext", "userId changed from '$userId' to '$uid' — updating")
                userId = uid
            }
        }
        android.util.Log.d("PageContext",
            "persistLatestPageContext: userId='$resolvedUserId' subject='${page.subject}' " +
            "chapter='${page.chapter}' transcript=${page.transcript.length} chars")
        tutorSession.latestPageContext = page.transcript
        FirestoreManager.saveChapterContext(
            userId = resolvedUserId,
            page = page,
            onSuccess = {
                android.util.Log.d("PageContext",
                    "saveChapterContext SUCCESS → users/$resolvedUserId/conversations/" +
                    "${FirestoreManager.safeId(page.subject)}__${FirestoreManager.safeId(page.chapter)}")
            },
            onFailure = { e ->
                android.util.Log.e("PageContext",
                    "saveChapterContext FAILED userId='$userId': ${e?.message}")
            }
        )
    }

    private fun generateFlashcards() {
        val prompt = PromptRepository.getQuickAction("flashcards", subjectName, chapterName)
        messageAdapter.addMessage(
            Message(
                UUID.randomUUID().toString(),
                "🃏 Generating revision flashcards for $chapterName…",
                true
            )
        )
        showLoading(true)

        val sysPrompt = TutorController.buildSystemPrompt(tutorSession)
        val fullResponse = StringBuilder()
        lifecycleScope.launch(Dispatchers.IO) {
            buildAiClient().streamText(
                systemPrompt = sysPrompt,
                userText = prompt,
                onToken = { token -> fullResponse.append(token) },
                onDone = { _, _, _ ->
                    runOnUiThread {
                        showLoading(false)
                        val cards = parseFlashcards(fullResponse.toString())
                        if (cards.isNotEmpty()) {
                            messageAdapter.addMessage(
                                Message(
                                    UUID.randomUUID().toString(),
                                    "✅ ${cards.size} flashcards ready! Opening revision mode…",
                                    false
                                )
                            )
                            startActivity(
                                Intent(
                                    this@ChatActivity,
                                    RevisionActivity::class.java
                                ).putExtra("flashcards", ArrayList(cards))
                            )
                        } else showError("Could not parse flashcards. Please try again.")
                    }
                },
                onError = { err ->
                    runOnUiThread { showLoading(false); showError("Failed to generate flashcards: $err") }
                }
            )
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
            Toast.makeText(this, "Generate notes first, then tap 💾 Save Notes.", Toast.LENGTH_SHORT)
                .show()
            return
        }
        notesRepo.save(
            content = lastAi.content,
            type = saveNotesType ?: "chapter",
            onSuccess = {
                metricsTracker.recordEvent(ChapterMetricsTracker.EventType.NOTES_SAVED)
                runOnUiThread { Toast.makeText(this, "✅ Notes saved!", Toast.LENGTH_SHORT).show() }
            },
            onFailure = {
                runOnUiThread {
                    Toast.makeText(this, "Failed to save notes.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun viewSavedNotes() {
        notesRepo.loadAll(
            onResult = { text ->
                AlertDialog.Builder(this)
                    .setTitle("📋 Saved Notes — $chapterName")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
            },
            onEmpty = {
                Toast.makeText(
                    this,
                    "No saved notes yet — generate and save notes first!",
                    Toast.LENGTH_SHORT
                ).show()
            },
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
                isInterrupted = false
                messageInput.setText("")
                if (isVoiceModeActive) setVoiceModeStatus("🤖 AI is thinking…", "#E65100")
                sendMessage(text)
            } else {
                if (isVoiceModeActive) startVoiceLoopListening()
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
            if (isVoiceModeActive) startVoiceLoopListening()
            else resetVoiceButton()
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

    private fun showLanguagePicker() {
        val names = LANGUAGES.keys.toTypedArray()
        val currentIdx = names.indexOf(currentLangName).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("🌐 Select Response Language")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                currentLangName = names[which]
                currentLang = LANGUAGES[currentLangName] ?: "en-US"
                updateLanguageButton()
                ttsManager.setLocale(Locale.forLanguageTag(currentLang))
                dialog.dismiss()
                Toast.makeText(this, "Lang:$currentLangName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateLanguageButton() {
        val label = when (currentLang) {
            "en-US" -> "English"
            "hi-IN" -> "Hindi"
            "bn-IN" -> "Bengali"
            "te-IN" -> "Telugu"
            "ta-IN" -> "Tamil"
            "mr-IN" -> "Marathi"
            "kn-IN" -> "Kannada"
            "gu-IN" -> "Gujarati"
            else -> currentLangName
        }
        languageButton.text = "Language: $label ▾"
    }

    // ── Interactive Voice Chat Mode (single-shot mic only) ──────────────────

    /**
     * When returning from BlackboardActivity (auto explain mode), resume voice loop
     * listening if voice mode was still active while the blackboard was open.
     */
    override fun onResume() {
        super.onResume()
        if (isVoiceModeActive && isAutoExplainActive && !isListening) {
            setVoiceModeStatus("🎙️ Listening… speak now", "#2E7D32")
            startVoiceLoopListening()
        }
    }

    /**
     * Called when this ChatActivity is brought to front via FLAG_ACTIVITY_CLEAR_TOP
     * (e.g. from PageViewerActivity's "Ask AI" button). Loads the new page into the chat.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pdfPath = intent.getStringExtra("pdfPageFilePath")
        if (pdfPath != null) {
            val pageNum = intent.getIntExtra("pdfPageNumber", 1)
            tutorSession.currentPage = pageNum
            preloadPdfPage(java.io.File(pdfPath), pageNum)
        }
        intent.getStringExtra("imagePath")?.takeIf { it.isNotBlank() }?.let { path ->
            showImagePreview(android.net.Uri.parse(path))
            messageInput.setText("Explain this page")
            messageInput.setSelection(messageInput.text.length)
        }
    }

    private fun showLiveModePickerAndStart() {
        val options = arrayOf(
            "Full Live (stop-words can interrupt)",
            "Partial Live (no interruptions)"
        )
        AlertDialog.Builder(this)
            .setTitle("Select Live Mic Mode")
            .setItems(options) { _, which ->
                liveMicMode = if (which == 0) LiveMicMode.FULL else LiveMicMode.PARTIAL
                updateLiveModeUi()
                if (liveMicMode == LiveMicMode.FULL) {
                    AlertDialog.Builder(this)
                        .setTitle("Full Live Enabled")
                        .setMessage("To stop TTS while it is speaking, say: 'stop it' or 'wait wait'.")
                        .setPositiveButton("Start") { _, _ -> startVoiceMode() }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    // Ensure no leftover secondary interrupt recognizer from previous FULL run.
                    voiceManager.stopInterruptListening()
                    startVoiceMode()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startVoiceMode() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE
            )
            return
        }
        isVoiceModeActive = true
        isInterrupted = false
        if (liveMicMode == LiveMicMode.PARTIAL) {
            // Hard guarantee: partial mode should never run secondary interrupt listener.
            voiceManager.stopInterruptListening()
        }
        voiceButton.isEnabled = false
        voiceChatBar.visibility = View.VISIBLE
        listeningIndicator.visibility = View.GONE
        voiceChatButton.backgroundTintList =
            ColorStateList.valueOf(android.graphics.Color.parseColor("#E53935"))
        val modeLabel = if (liveMicMode == LiveMicMode.FULL) "Full Live" else "Partial Live"
        Toast.makeText(this, "🎙️ Voice mode ON ($modeLabel)", Toast.LENGTH_SHORT).show()
        updateLiveModeUi()
        startVoiceLoopListening()
    }

    private fun stopVoiceMode() {
        isVoiceModeActive = false
        isInterrupted = false
        ttsManager.stop()
        voiceManager.stopInterruptListening()
        if (isListening) {
            voiceManager.stopListening(); isListening = false
        }
        stopWaveAnimation()
        stopMicPulse()
        voiceButton.isEnabled = true
        voiceChatBar.visibility = View.GONE
        voiceChatButton.backgroundTintList =
            ColorStateList.valueOf(android.graphics.Color.parseColor("#E8F5E9"))
        updateLiveModeUi()
        Toast.makeText(this, "Voice mode OFF", Toast.LENGTH_SHORT).show()
    }

    private fun startVoiceLoopListening() {
        if (!isVoiceModeActive) return
        isListening = true
        val listeningLabel = if (liveMicMode == LiveMicMode.FULL) {
            "🎙️ Full Live listening… say stop it / wait wait to interrupt"
        } else {
            "🎙️ Partial Live listening… no interruptions"
        }
        setVoiceModeStatus(listeningLabel, "#2E7D32")
        voiceManager.startListening(this, currentLang)
        startMicPulse()
    }

    private fun updateLiveModeUi() {
        val isFull = liveMicMode == LiveMicMode.FULL
        liveModeChip.text = if (isFull) "Live: Full" else ""
        voiceModeBadge.text = if (isFull) "FULL" else "PARTIAL"
        val chipBg = if (isFull) "#FFF3E0" else "#EAF2FF"
        val chipText = if (isFull) "#B45309" else "#0B4AA2"
        liveModeChip.setBackgroundColor(android.graphics.Color.parseColor(chipBg))
        liveModeChip.setTextColor(android.graphics.Color.parseColor(chipText))
        voiceModeBadge.setBackgroundColor(android.graphics.Color.parseColor(chipBg))
        voiceModeBadge.setTextColor(android.graphics.Color.parseColor(chipText))
    }

    private fun setVoiceModeStatus(text: String, colorHex: String) {
        voiceChatStatus.text = text
        voiceChatStatus.setTextColor(android.graphics.Color.parseColor(colorHex))
        when (colorHex) {
            "#2E7D32", "#6A1B9A" -> startWaveAnimation(colorHex)
            "#1565C0" -> {
                startWaveAnimation("#1565C0"); stopMicPulse()
            }

            else -> {
                stopWaveAnimation(); stopMicPulse()
            }
        }
    }

    private val interruptCallback = object : VoiceRecognitionCallback {
        override fun onPartialResults(text: String) {
            if (!isVoiceModeActive || !ttsManager.isSpeaking() || text.length < 3) return
            if (shouldInterruptForText(text)) triggerBargein()
        }

        override fun onBeginningOfSpeech() {}
        override fun onResults(text: String) {
            if (!isVoiceModeActive || text.length < 2) return
            if (shouldInterruptForText(text)) triggerBargein()
        }

        override fun onError(error: String) {}
        override fun onListeningStarted() {}
        override fun onListeningFinished() {}
    }

    private fun shouldInterruptForText(text: String): Boolean {
        val heard = text.lowercase().trim()
        if (heard.isBlank()) return false
        val normalizedTTS = currentTTSText.lowercase()

        return if (liveMicMode == LiveMicMode.FULL) {
            // Full Live: only explicit stop words interrupt TTS.
            voiceStopWords.any { heard.contains(it) } && !normalizedTTS.contains(heard)
        } else {
            // Partial Live: no interruption while TTS is speaking.
            false
        }
    }

    private fun triggerBargein() {
        if (isInterrupted) return
        isInterrupted = true
        ttsManager.stop()
        voiceManager.stopInterruptListening()
        runOnUiThread { setVoiceModeStatus("🎙️ Listening (interrupted)…", "#6A1B9A") }
        isListening = true
        voiceManager.startListening(this, currentLang)
    }

    private fun startWaveAnimation(colorHex: String) {
        waveBarContainer.visibility = View.VISIBLE
        val tint = ColorStateList.valueOf(android.graphics.Color.parseColor(colorHex))
        listOf(waveBar1, waveBar2, waveBar3, waveBar4).forEach { it.backgroundTintList = tint }
        val durations = longArrayOf(420L, 600L, 360L, 510L)
        val delays = longArrayOf(0L, 130L, 260L, 80L)
        listOf(waveBar1, waveBar2, waveBar3, waveBar4).forEachIndexed { i, bar ->
            bar.clearAnimation()
            bar.startAnimation(
                ScaleAnimation(
                    1f, 1f, 0.15f, 1f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 1.0f
                ).apply {
                    duration = durations[i]
                    startOffset = delays[i]
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                    fillAfter = true
                })
        }
    }

    private fun stopWaveAnimation() {
        listOf(waveBar1, waveBar2, waveBar3, waveBar4).forEach { it.clearAnimation() }
        waveBarContainer.visibility = View.GONE
    }

    private fun startMicPulse() {
        voiceButton.clearAnimation()
        voiceButton.startAnimation(
            ScaleAnimation(
                1f, 1.15f, 1f, 1.15f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 600L
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
                fillAfter = true
            })
    }

    private fun stopMicPulse() {
        voiceButton.clearAnimation()
        voiceButton.scaleX = 1f
        voiceButton.scaleY = 1f
    }

}