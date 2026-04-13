package com.aiguruapp.student

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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.aiguruapp.student.adapters.MessageAdapter
import com.aiguruapp.student.adapters.PageListAdapter
import com.aiguruapp.student.chat.AiClient
import com.aiguruapp.student.chat.ChatHistoryRepository
import com.aiguruapp.student.chat.NotesRepository
import com.aiguruapp.student.chat.PageAnalyzer
import com.aiguruapp.student.chat.ServerProxyClient
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.config.PlanEnforcer
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.Flashcard
import com.aiguruapp.student.models.Message
import com.aiguruapp.student.models.PageContent
import com.aiguruapp.student.models.TutorMode
import com.aiguruapp.student.models.TutorSession
import com.aiguruapp.student.models.UserMetadata
import com.aiguruapp.student.services.ResponseCacheService
import com.aiguruapp.student.utils.ChapterMetricsTracker
import com.aiguruapp.student.utils.MediaManager
import com.aiguruapp.student.utils.PdfPageManager
import com.aiguruapp.student.utils.PromptRepository
import com.aiguruapp.student.utils.SessionManager
import com.aiguruapp.student.utils.TTSCallback
import com.aiguruapp.student.utils.TextToSpeechManager
import com.aiguruapp.student.utils.VoiceManager
import com.aiguruapp.student.utils.VoiceRecognitionCallback
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Full-featured chat tab embedded inside ChapterActivity.
 * Contains all the functionality of ChatActivity: voice, images, quiz, blackboard, etc.
 */
class FullChatFragment : Fragment(), VoiceRecognitionCallback {

    // ── UI Views ──────────────────────────────────────────────────────────────
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var loadingLayout: LinearLayout
    private lateinit var loadingStatusText: TextView
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
    private lateinit var listeningIndicator: TextView
    private lateinit var bottomDescribeButton: MaterialButton
    private lateinit var plusButton: MaterialButton
    private lateinit var quickActionsPanel: View
    private var isQuickActionsOpen = false

    // ── Chapter Workspace Drawer ──────────────────────────────────────────────
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

    // ── Auto Explain / Blackboard Mode ────────────────────────────────────────
    private var isAutoExplainActive = true
    private lateinit var autoExplainButton: MaterialButton
    private var blackboardNudgeSnackbar: Snackbar? = null

    // ── Interactive Voice Chat Mode ────────────────────────────────────────────
    private var isVoiceModeActive = false
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
    private lateinit var chapterNotesRepo: com.aiguruapp.student.notes.ChapterNotesRepository
    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var mediaManager: MediaManager
    private lateinit var metricsTracker: ChapterMetricsTracker

    private var selectedImageUri: Uri? = null
    private var cameraImageUri: Uri? = null
    private var pendingDisplayUri: Uri? = null
    private var isListening = false

    // ── Page Analysis ─────────────────────────────────────────────────────────
    private var currentPageContent: PageContent? = null

    // ── Language ───────────────────────────────────────────────────────────────
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

    private var pdfPageBase64: String? = null
    private var pendingImageBase64: String? = null
    private var imageEncodeJob: kotlinx.coroutines.Job? = null
    private var saveNotesType: String? = null

    // ── Crop state ────────────────────────────────────────────────────────────
    private var pendingCropIsPdf = false
    private var pendingCropPdfPageNumber = 0
    private var pendingCropPdfFile: File? = null
    /** When true, the next crop result should be saved as a note instead of a chat attachment. */
    private var pendingCropForNote = false

    // ── Tutor System ──────────────────────────────────────────────────────────
    private lateinit var tutorSession: TutorSession
    private var lastInputWasVoice = false

    // ── Pending actions (set before views are ready) ──────────────────────────
    private var pendingAutoPrompt: Pair<String, String?>? = null
    private var pendingPdfPage: Pair<String, Int>? = null
    private var pendingImagePath: String? = null

    // ── Activity Result Launchers ─────────────────────────────────────────────
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) launchCrop(uri, isPdf = false)
            else saveNextPickedImageToChapter = false
        }

    // Returns from PageViewerActivity — attach the rendered PDF page to chat and
    // stay inside the current activity (ChapterActivity tabbed view or ChatHostActivity).
    private val pageViewerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val filePath = data.getStringExtra("pdfPageFilePath") ?: return@registerForActivityResult
                val pageNum = data.getIntExtra("pdfPageNumber", 1)
                tutorSession.currentPage = pageNum
                preloadPdfPage(java.io.File(filePath), pageNum)
                chatDrawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) launchCrop(cameraImageUri!!, isPdf = false)
            else saveNextPickedImageToChapter = false
        }

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            when (result.resultCode) {
                android.app.Activity.RESULT_OK -> {
                    val croppedUri =
                        data?.let { UCrop.getOutput(it) } ?: return@registerForActivityResult
                    // ── Crop-to-note path ─────────────────────────────────────────────────
                    if (pendingCropForNote) {
                        pendingCropForNote = false
                        val cropUri = croppedUri
                        val noteBase = com.aiguruapp.student.notes.ChapterNote(
                            id       = java.util.UUID.randomUUID().toString(),
                            type     = "image",
                            content  = "",
                            imageUri = cropUri.toString()
                        )
                        showCategoryPickerAndSaveNote(noteBase)
                    } else if (pendingCropIsPdf) {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val bmp: android.graphics.Bitmap = try {
                                    requireContext().contentResolver.openInputStream(croppedUri)
                                        ?.use { stream -> android.graphics.BitmapFactory.decodeStream(stream) }
                                        ?: run {
                                            android.util.Log.e("FullChatFragment",
                                                "Crop: could not open/decode stream for $croppedUri")
                                            return@launch
                                        }
                                } catch (oom: OutOfMemoryError) {
                                    android.util.Log.e("FullChatFragment", "Crop: OOM decoding cropped image")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(requireContext(),
                                            "Image too large — try a smaller crop",
                                            Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }
                                val baos = ByteArrayOutputStream()
                                try {
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                                } finally {
                                    bmp.recycle()
                                }
                                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                val pageLabel = if (pendingCropPdfPageNumber > 0)
                                    "Page $pendingCropPdfPageNumber \u2702\ufe0f cropped"
                                else "\u2702\ufe0f Cropped region"
                                withContext(Dispatchers.Main) {
                                    pdfPageBase64 = b64
                                    pendingDisplayUri = croppedUri
                                    currentPageContent = null
                                    imagePreviewStrip.visibility = View.VISIBLE
                                    Glide.with(requireContext()).load(croppedUri).centerCrop()
                                        .into(imagePreviewThumbnail)
                                    imagePreviewLabel.text = pageLabel
                                    messageInput.setText("Explain this Page")
                                    messageInput.setSelection(messageInput.text.length)
                                    bottomDescribeButton.visibility = View.VISIBLE
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("FullChatFragment",
                                    "Crop result processing failed: ${e.message}")
                            }
                        }
                    } else {
                        showImagePreview(croppedUri)
                    }
                }

                UCrop.RESULT_ERROR -> {
                    pendingCropForNote = false
                    android.util.Log.w("FullChatFragment",
                        "UCrop error: ${data?.let { UCrop.getError(it)?.message }}")
                    if (pendingCropIsPdf) pendingCropPdfFile?.let {
                        applyFullPdfPage(it, pendingCropPdfPageNumber)
                    } else {
                        saveNextPickedImageToChapter = false
                    }
                }

                else -> {
                    pendingCropForNote = false
                    if (pendingCropIsPdf) pendingCropPdfFile?.let {
                        applyFullPdfPage(it, pendingCropPdfPageNumber)
                    } else {
                        saveNextPickedImageToChapter = false
                    }
                }
            }
        }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100

        fun newInstance(subjectName: String, chapterName: String) = FullChatFragment().apply {
            arguments = Bundle().apply {
                putString("subjectName", subjectName)
                putString("chapterName", chapterName)
            }
        }
    }

    // ── Fragment Lifecycle ────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.activity_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pdfPageManager = PdfPageManager(requireContext())

        isAutoExplainActive = requireContext()
            .getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("blackboard_mode_on", true)

        subjectName = arguments?.getString("subjectName") ?: "General"
        chapterName = arguments?.getString("chapterName") ?: "Study Session"

        PromptRepository.init(requireContext())

        userId = SessionManager.getFirestoreUserId(requireContext())
        cachedMetadata = cachedMetadata.copy(userId = userId)

        AdminConfigRepository.fetchIfStale()
        FirestoreManager.getUserMetadata(userId, onSuccess = { meta ->
            if (meta != null) {
                cachedMetadata = meta
                // Guard: initializeUI may not have run yet if Firestore responds very fast

            }
        })

        tutorSession = TutorSession(
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
                android.util.Log.w("FullChatFragment",
                    "Failed to load chapter context: ${it?.message}")
            }
        )

        historyRepo = ChatHistoryRepository(userId, subjectName, chapterName)
        notesRepo = NotesRepository(requireContext(), userId, subjectName, chapterName)
        chapterNotesRepo = com.aiguruapp.student.notes.ChapterNotesRepository(
            requireContext(), userId, subjectName, chapterName)
        voiceManager = VoiceManager(requireActivity())
        ttsManager = TextToSpeechManager(requireContext())
        mediaManager = MediaManager(requireContext())
        metricsTracker = ChapterMetricsTracker(subjectName, chapterName)

        initializeUI(view)
        initializeChapterWorkspaceDrawer()

        // Blackboard preference is remembered across sessions (default ON).

        val chatInitOverlay = view.findViewById<android.view.View>(R.id.chatInitOverlay)
        fun hideChatInitOverlay() {
            chatInitOverlay?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
                chatInitOverlay.visibility = android.view.View.GONE
                chatInitOverlay.alpha = 1f
            }?.start()
        }
        historyRepo.loadHistory(
            onMessages = { msgs ->
                hideChatInitOverlay()
                messageAdapter.addMessages(msgs)
                messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
            },
            onEmpty = {
                hideChatInitOverlay()
                addWelcomeMessage()
            }
        )

        // Drain any pending actions queued before views were ready
        pendingAutoPrompt?.let { (prompt, notesType) ->
            saveNotesType = notesType
            messagesRecyclerView.post { sendMessage(prompt, autoSaveNotes = notesType != null) }
            pendingAutoPrompt = null
        }
        pendingPdfPage?.let { (filePath, pageNum) ->
            tutorSession.currentPage = pageNum
            preloadPdfPage(File(filePath), pageNum)
            pendingPdfPage = null
        }
        pendingImagePath?.let { path ->
            showImagePreview(Uri.parse(path))
            messageInput.setText("Explain this Page page")
            messageInput.setSelection(messageInput.text.length)
            pendingImagePath = null
        }
    }

    // ── Public action API (called from ChapterActivity) ───────────────────────

    fun sendAutoPrompt(prompt: String, notesType: String? = null) {
        if (isAdded && view != null) {
            saveNotesType = notesType
            messagesRecyclerView.post { sendMessage(prompt, autoSaveNotes = notesType != null) }
        } else {
            pendingAutoPrompt = Pair(prompt, notesType)
        }
    }

    fun attachPdfPage(pdfPageFilePath: String, pageNumber: Int) {
        if (isAdded && view != null) {
            tutorSession.currentPage = pageNumber
            preloadPdfPage(File(pdfPageFilePath), pageNumber)
        } else {
            pendingPdfPage = Pair(pdfPageFilePath, pageNumber)
        }
    }

    fun attachImage(imagePath: String) {
        if (isAdded && view != null) {
            showImagePreview(Uri.parse(imagePath))
            messageInput.setText("Explain this Page page")
            messageInput.setSelection(messageInput.text.length)
        } else {
            pendingImagePath = imagePath
        }
    }

    override fun onResume() {
        super.onResume()
        // Always re-fetch the latest metadata from Firestore so that question
        // counters (chat_questions_today, bb_sessions_today) reflect the real
        // Firestore value — not the in-memory default — after the app is reopened.
        if (userId.isNotBlank() && userId != "guest_user") {
            FirestoreManager.getUserMetadata(userId, onSuccess = { meta ->
                if (meta != null) {
                    cachedMetadata = meta
                }
            })
        }
        if (isVoiceModeActive && isAutoExplainActive && !isListening) {
            setVoiceModeStatus("🎙️ Listening… speak now", "#2E7D32")
            startVoiceLoopListening()
        }
        // Re-apply any language change made in HomeActivity while this screen was paused
        val resumeLang = com.aiguruapp.student.utils.SessionManager.getPreferredLang(requireContext())
        if (resumeLang.isNotBlank() && resumeLang != currentLang) {
            currentLang = resumeLang
            currentLangName = LANGUAGES.entries.firstOrNull { it.value == resumeLang }?.key ?: currentLangName
            ttsManager.setLocale(java.util.Locale.forLanguageTag(currentLang))
        }
    }

    override fun onStop() {
        super.onStop()
        context?.let { metricsTracker.endSession(it, 0) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isListening) voiceManager.stopListening()
        voiceManager.stopInterruptListening()
        ttsManager.destroy()
    }

    // ── UI Initialization ─────────────────────────────────────────────────────

    private fun initializeUI(view: View) {
        chatDrawerLayout = view.findViewById(R.id.chatDrawerLayout)

//        view.findViewById<TextView>(R.id.chatHeaderTitle).text = chapterName
//        view.findViewById<TextView>(R.id.chatHeaderSubtitle).text = "$subjectName · Tutor Session"

        messagesRecyclerView = view.findViewById(R.id.messagesRecyclerView)
        messageAdapter = MessageAdapter(
            context = requireContext(),
            onVoiceClick = { msg ->
                ttsManager.setLocale(Locale.forLanguageTag(currentLang))
                val speechText = TutorController.prepareSpeechText(
                    TutorController.extractAnswerForDisplay(msg.content)
                )
                ttsManager.speak(speechText, object : TTSCallback {
                    override fun onStart() {}
                    override fun onComplete() {}
                    override fun onError(error: String) {}
                })
            },
            onStopClick = { ttsManager.stop() },
            onImageClick = { msg ->
                msg.imageUrl?.let { uri ->
                    startActivity(
                        Intent(requireContext(), FullscreenImageActivity::class.java)
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
                    val convId =
                        "${FirestoreManager.safeId(subjectName)}__${FirestoreManager.safeId(chapterName)}"
                    startActivity(
                        Intent(requireContext(), BlackboardActivity::class.java)
                            .putExtra(BlackboardActivity.EXTRA_MESSAGE, TutorController.extractAnswerForDisplay(msg.content))
                            .putExtra(BlackboardActivity.EXTRA_MESSAGE_ID, msg.id)
                            .putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
                            .putExtra(BlackboardActivity.EXTRA_CONVERSATION_ID, convId)
                            .putExtra(BlackboardActivity.EXTRA_LANGUAGE_TAG, currentLang)
                    )
                }
            },
            onSaveNoteClick = { msg ->
                val text = TutorController.extractAnswerForDisplay(msg.content).take(4000)
                showCategoryPickerAndSaveNote(
                    com.aiguruapp.student.notes.ChapterNote(
                        id       = msg.id,
                        type     = "ai",
                        content  = text
                    )
                )
            }
        )
        messagesRecyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        messagesRecyclerView.adapter = messageAdapter

        messageInput = view.findViewById(R.id.messageInput)
        sendButton = view.findViewById(R.id.sendButton)
        loadingLayout = view.findViewById(R.id.loadingLayout)
        loadingStatusText = view.findViewById(R.id.loadingStatusText)
        voiceButton = view.findViewById(R.id.voiceButton)
        imageButton = view.findViewById(R.id.imageButton)
        saveNotesButton = view.findViewById(R.id.saveNotesButton)
        viewNotesButton = view.findViewById(R.id.viewNotesButton)
        formulaButton = view.findViewById(R.id.formulaButton)
        practiceButton = view.findViewById(R.id.practiceButton)
        imagePreviewStrip = view.findViewById(R.id.imagePreviewStrip)
        imagePreviewThumbnail = view.findViewById(R.id.imagePreviewThumbnail)
        imagePreviewLabel = view.findViewById(R.id.imagePreviewLabel)
        removeImageButton = view.findViewById(R.id.removeImageButton)
        pagesDrawerCloseButton = view.findViewById(R.id.pagesDrawerCloseButton)
        pagesDrawerAddPageButton = view.findViewById(R.id.pagesDrawerAddPageButton)
        pagesDrawerTitle = view.findViewById(R.id.pagesDrawerTitle)
        pagesDrawerHint = view.findViewById(R.id.pagesDrawerHint)
        pagesDrawerList = view.findViewById(R.id.pagesDrawerList)
        listeningIndicator = view.findViewById(R.id.listeningIndicator)
        bottomDescribeButton = view.findViewById(R.id.bottomDescribeButton)
        plusButton = view.findViewById(R.id.plusButton)
        quickActionsPanel = view.findViewById(R.id.quickActionsPanel)
        voiceChatBar = view.findViewById(R.id.voiceChatBar)
        voiceChatStatus = view.findViewById(R.id.voiceChatStatus)
        voiceModeBadge = view.findViewById(R.id.voiceModeBadge)
        liveModeChip = view.findViewById(R.id.liveModeChip)
        waveBarContainer = view.findViewById(R.id.waveBarContainer)
        waveBar1 = view.findViewById(R.id.waveBar1)
        waveBar2 = view.findViewById(R.id.waveBar2)
        waveBar3 = view.findViewById(R.id.waveBar3)
        waveBar4 = view.findViewById(R.id.waveBar4)

        plusButton.setOnClickListener { toggleQuickActions() }
        pagesDrawerCloseButton.setOnClickListener { chatDrawerLayout.closeDrawer(GravityCompat.START) }

        // Load language preference saved during signup/settings
        val savedLang = com.aiguruapp.student.utils.SessionManager.getPreferredLang(requireContext())
        if (savedLang.isNotBlank()) {
            currentLang = savedLang
            currentLangName = LANGUAGES.entries.firstOrNull { it.value == savedLang }?.key ?: currentLangName
            ttsManager.setLocale(java.util.Locale.forLanguageTag(currentLang))
        }

        updateLiveModeUi()
        setupButtons(view)
        setupQuickActions(view)

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

    private fun setupButtons(view: View) {
        voiceChatButton = view.findViewById(R.id.voiceChatButton)
        voiceChatButton.setOnClickListener {
            if (isVoiceModeActive) stopVoiceMode() else startVoiceMode()
        }

        autoExplainButton = view.findViewById(R.id.autoExplainButton)
        autoExplainButton.setOnClickListener {
            val limits = AdminConfigRepository.resolveEffectiveLimits(
                cachedMetadata.planId, cachedMetadata.planLimits
            )
            val check =
                PlanEnforcer.check(cachedMetadata, limits, PlanEnforcer.FeatureType.BLACKBOARD)
            if (!check.allowed) { showError(check.upgradeMessage); return@setOnClickListener }
            isAutoExplainActive = !isAutoExplainActive
            requireContext().getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("blackboard_mode_on", isAutoExplainActive).apply()
            updateAutoExplainButton()
            Toast.makeText(
                requireContext(),
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

        imagePreviewThumbnail.setOnLongClickListener {
            val curUri = selectedImageUri ?: pendingDisplayUri
            if (curUri != null) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Add to Notes")
                    .setMessage("Crop a region of this image and add it as a note?")
                    .setPositiveButton("✂️ Crop & Add") { _, _ ->
                        pendingCropForNote = true
                        launchCrop(curUri, isPdf = false)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        imagePreviewThumbnail.setOnClickListener {
            pendingDisplayUri?.let { uri ->
                startActivity(
                    Intent(requireContext(), FullscreenImageActivity::class.java)
                        .putExtra(FullscreenImageActivity.EXTRA_IMAGE_URI, uri.toString())
                )
            }
        }

        removeImageButton.setOnClickListener {
            imageEncodeJob?.cancel()
            imageEncodeJob = null
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

        view.findViewById<MaterialButton>(R.id.clearChatButton).setOnClickListener {
            showClearChatConfirmation()
        }
    }

    private fun setupQuickActions(view: View) {
        mapOf(
            R.id.explainButton to "explain",
            R.id.notesButton to "notes"
        ).forEach { (id, key) ->
            view.findViewById<MaterialButton>(id).setOnClickListener {
                sendMessage(PromptRepository.getQuickAction(key, subjectName, chapterName))
                closeQuickActions()
            }
        }

        view.findViewById<MaterialButton>(R.id.quizButton).setOnClickListener {
            closeQuickActions()
            val chapterId =
                "${subjectName}_${chapterName}".replace(" ", "_").lowercase().take(64)
            startActivity(
                Intent(requireContext(), QuizSetupActivity::class.java)
                    .putExtra("subjectName", subjectName)
                    .putExtra("chapterId", chapterId)
                    .putExtra("chapterTitle", chapterName)
            )
        }
        view.findViewById<MaterialButton>(R.id.flashcardsButton).setOnClickListener {
            generateFlashcards()
            closeQuickActions()
        }
        view.findViewById<MaterialButton>(R.id.createPageButton).setOnClickListener {
            saveNextPickedImageToChapter = true
            closeQuickActions()
            showImageSourceDialog()
        }
    }

    // ── Quick Actions Panel ───────────────────────────────────────────────────

    private fun toggleQuickActions() {
        if (isQuickActionsOpen) closeQuickActions() else openQuickActions()
    }

    private fun openQuickActions() {
        quickActionsPanel.visibility = View.VISIBLE
        plusButton.text = "✕"
        isQuickActionsOpen = true
    }

    private fun closeQuickActions() {
        quickActionsPanel.visibility = View.GONE
        plusButton.text = "+"
        isQuickActionsOpen = false
    }

    // ── Chapter Workspace Drawer ──────────────────────────────────────────────

    private fun initializeChapterWorkspaceDrawer() {
        chapterPagesAdapter = PageListAdapter(
            pages = chapterPages,
            onView = { index -> onWorkspaceViewPage(index) },
            onAsk = { index -> onWorkspaceAskPage(index) }
        )
        pagesDrawerList.layoutManager = LinearLayoutManager(requireContext())
        pagesDrawerList.adapter = chapterPagesAdapter

        pagesDrawerAddPageButton.setOnClickListener {
            saveNextPickedImageToChapter = true
            showImageSourceDialog()
        }

        val metaRaw = requireContext()
            .getSharedPreferences("chapters_prefs", android.content.Context.MODE_PRIVATE)
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

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
        val raw = requireContext()
            .getSharedPreferences("chapters_prefs", android.content.Context.MODE_PRIVATE)
            .getString(key, "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                if (item != null) {
                    val path = item.optString("path", "")
                    if (path.isNotBlank()) {
                        chapterImagePaths.add(path)
                        chapterPages.add("Page ${chapterImagePaths.size}")
                    }
                }
            }
        } catch (_: Exception) { }
        if (chapterPages.isEmpty()) chapterPages.add("No pages yet. Tap Add Page.")
        chapterPagesAdapter.notifyDataSetChanged()
    }

    private fun onWorkspaceViewPage(index: Int) {
        if (isPdfChapterWorkspace) {
            if (chapterPdfPageCount <= 0) return
            pageViewerLauncher.launch(
                Intent(requireContext(), PageViewerActivity::class.java)
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
            tutorSession.currentPage = index + 1
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val pageFile =
                        pdfPageManager.getPage(chapterPdfId, chapterPdfAssetPath, index)
                    withContext(Dispatchers.Main) {
                        preloadPdfPage(pageFile, index + 1)
                        chatDrawerLayout.closeDrawer(GravityCompat.START)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(),
                            "Failed to render page: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        if (chapterImagePaths.isEmpty() || index !in chapterImagePaths.indices) return
        val uri = Uri.parse(chapterImagePaths[index])
        showImagePreview(uri)
        messageInput.setText("Explain this Page page")
        messageInput.setSelection(messageInput.text.length)
        chatDrawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun saveImagePageToChapter(uri: Uri) {
        val key = "imgpages_${subjectName}_${chapterName}"
        val prefs = requireContext()
            .getSharedPreferences("chapters_prefs", android.content.Context.MODE_PRIVATE)
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
        Toast.makeText(requireContext(), "Page added to chapter", Toast.LENGTH_SHORT).show()
    }

    // ── Voice Input ───────────────────────────────────────────────────────────

    private fun checkPermissionAndStartListening() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
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

    // ── Image Preview & PDF Page ──────────────────────────────────────────────

    private fun showImagePreview(uri: Uri) {
        selectedImageUri = uri
        pendingDisplayUri = uri
        // Cancel any in-flight encode from a previous image pick
        imageEncodeJob?.cancel()
        imageEncodeJob = null
        pendingImageBase64 = null
        if (saveNextPickedImageToChapter) {
            saveNextPickedImageToChapter = false
            if (!isPdfChapterWorkspace) saveImagePageToChapter(uri)
        }
        currentPageContent = null
        metricsTracker.recordEvent(ChapterMetricsTracker.EventType.IMAGE_UPLOADED)
        imagePreviewStrip.visibility = View.VISIBLE
        Glide.with(requireContext()).load(uri).centerCrop().into(imagePreviewThumbnail)
        imagePreviewLabel.text = mediaManager.getFileInfo(uri)
        messageInput.setText("Explain this Page")
        messageInput.setSelection(messageInput.text.length)
        bottomDescribeButton.visibility = View.VISIBLE

        // Eagerly encode in background; sendMessage falls back to re-encoding if this hasn't
        // finished yet (race case when user sends immediately after picking).
        imageEncodeJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val b64 = mediaManager.uriToBase64(uri)
            if (b64 != null) pendingImageBase64 = b64
            // null means encode failed or was cancelled — sendMessage re-encodes from URI
        }
    }

    private fun preloadPdfPage(pageFile: File, pageNumber: Int) {
        if (!pageFile.exists()) return
        val sourceUri =
            FileProvider.getUriForFile(requireContext(),
                "${requireContext().packageName}.fileprovider", pageFile)
        messagesRecyclerView.post {
            launchCrop(sourceUri, isPdf = true, pdfPageNumber = pageNumber, pdfFile = pageFile)
        }
    }

    private fun applyFullPdfPage(pageFile: File, pageNumber: Int) {
        if (!pageFile.exists()) return
        val bmp = try {
            BitmapFactory.decodeFile(pageFile.absolutePath)
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e("FullChatFragment", "applyFullPdfPage: OOM decoding ${pageFile.name}")
            null
        } ?: return
        val b64: String
        try {
            val baos = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } finally {
            bmp.recycle()
        }
        pdfPageBase64 = b64
        pendingDisplayUri = Uri.fromFile(pageFile)
        currentPageContent = null

        imagePreviewStrip.visibility = View.VISIBLE
        Glide.with(requireContext()).load(pageFile).centerCrop().into(imagePreviewThumbnail)
        imagePreviewLabel.text = "Page $pageNumber"
        messageInput.setText("Explain this Page page")
        messageInput.setSelection(messageInput.text.length)
        bottomDescribeButton.visibility = View.VISIBLE

    }

    private fun launchCrop(
        sourceUri: Uri,
        isPdf: Boolean,
        pdfPageNumber: Int = 0,
        pdfFile: File? = null
    ) {
        pendingCropIsPdf = isPdf
        pendingCropPdfPageNumber = pdfPageNumber
        pendingCropPdfFile = pdfFile

        val imagesDir = File(requireContext().filesDir, "chat_images").also { it.mkdirs() }
        val destFile = File(imagesDir, "crop_${System.currentTimeMillis()}.jpg")

        // Decode only image header (no pixels) to get dimensions for crop ratio calculation
        var imgW = 0f; var imgH = 0f
        runCatching {
            val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireContext().contentResolver.openInputStream(sourceUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            imgW = boundsOpts.outWidth.toFloat()
            imgH = boundsOpts.outHeight.toFloat()
        }

        // Compute an aspect ratio that pre-positions the crop box to ~30% of the image area:
        //   Landscape (W≥H): box = 30% width × 100% height → ratio = (0.3×W) : H
        //   Portrait  (H>W): box = 100% width × 30% height → ratio = W : (0.3×H)
        // UCrop's initCropWindow() uses this ratio to size the initial crop frame.
        // setFreeStyleCropEnabled(true) still allows the user to resize freely after opening.
        val cropRatioX: Float
        val cropRatioY: Float
        if (imgW > 0f && imgH > 0f) {
            if (imgW >= imgH) {
                cropRatioX = 0.3f * imgW; cropRatioY = imgH   // landscape
            } else {
                cropRatioX = imgW; cropRatioY = 0.3f * imgH   // portrait
            }
        } else {
            cropRatioX = 1f; cropRatioY = 1f   // fallback: square box if decode failed
        }

        val options = UCrop.Options().apply {
            setToolbarTitle(if (isPdf) "Select Region" else "Crop Image")
            // Dark toolbar so the white ✓ and ✕ icons are always visible
            setToolbarColor(android.graphics.Color.parseColor("#1A237E"))
            setToolbarWidgetColor(android.graphics.Color.WHITE)
            setStatusBarColor(android.graphics.Color.parseColor("#0D1650"))
            setActiveControlsWidgetColor(android.graphics.Color.parseColor("#5C6BC0"))
            setFreeStyleCropEnabled(true)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setHideBottomControls(true)
            withMaxResultSize(1920, 1920)
            setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setDimmedLayerColor(android.graphics.Color.parseColor("#AA000000"))
        }
        try {
            val uCrop = UCrop.of(sourceUri, Uri.fromFile(destFile))
                .withOptions(options)
                .withMaxResultSize(1920, 1920)
                .withAspectRatio(cropRatioX, cropRatioY)  // pre-positions crop box to ~30% area
            cropLauncher.launch(uCrop.getIntent(requireContext()))
        } catch (e: Exception) {
            android.util.Log.w("FullChatFragment", "UCrop launch failed: ${e.message}")
            if (isPdf) pdfFile?.let { applyFullPdfPage(it, pdfPageNumber) }
        }
    }

    // ── Image Source Dialog & Camera ──────────────────────────────────────────

    private fun showImageSourceDialog() {
        AlertDialog.Builder(requireContext())
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
        if (ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(android.Manifest.permission.CAMERA),
                PERMISSION_REQUEST_CODE
            )
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraImageUri =
            requireContext().contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
        cameraImageUri?.let { cameraLauncher.launch(it) }
    }

    // ── Chat Helpers ──────────────────────────────────────────────────────────

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
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Chat")
            .setMessage("Delete all messages in this chat? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                historyRepo.clearHistory(
                    onSuccess = {
                        messageAdapter.clear()
                        addWelcomeMessage()
                        Toast.makeText(requireContext(), "Chat cleared", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = {
                        Toast.makeText(requireContext(), "Failed to clear chat", Toast.LENGTH_SHORT)
                            .show()
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildAiClient(): AiClient {
        val cfg = AdminConfigRepository.config
        return ServerProxyClient(
            serverUrl = AdminConfigRepository.effectiveServerUrl(),
            modelName = "",
            apiKey = cfg.serverApiKey,
            userId = userId
        )
    }

    // ── Send Message ──────────────────────────────────────────────────────────

    private fun sendMessage(userText: String, autoSaveNotes: Boolean = false) {
        val act = requireActivity()
        val ctx = requireContext()

        val imageUri = selectedImageUri.also { selectedImageUri = null }
        val capturedPdfBase64 = pdfPageBase64.also { pdfPageBase64 = null }
        val capturedImageBase64 = pendingImageBase64.also { pendingImageBase64 = null }
        currentPageContent = null
        val capturedDisplayUri = pendingDisplayUri.also { pendingDisplayUri = null }
        var serverPageTranscript: String? = null
        var serverSuggestBlackboard = false
        val hadVisualAttachment = imageUri != null || capturedPdfBase64 != null

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
            if (planCheck.limitType == PlanEnforcer.LimitType.PLAN_EXPIRED) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startActivity(
                        Intent(ctx, SubscriptionActivity::class.java)
                            .putExtra("schoolId", SessionManager.getSchoolId(ctx))
                    )
                }, 1500)
            }
            return
        }

        // FAST PATH: check question quota synchronously against the in-memory-accurate
        // cachedMetadata (updated in onDone after each successful response).
        // This blocks exhausted users immediately without waiting for Firestore.
        val fastQuotaCheck = PlanEnforcer.checkQuestionsQuota(cachedMetadata, effectiveLimits, isBlackboard = false)
        if (!fastQuotaCheck.allowed) {
            if (imageUri != null) selectedImageUri = imageUri
            if (capturedPdfBase64 != null) pdfPageBase64 = capturedPdfBase64
            if (capturedDisplayUri != null) pendingDisplayUri = capturedDisplayUri
            android.util.Log.e("FullChatFragment",
                "FAST QUOTA BLOCK: ${fastQuotaCheck.reason} (asked=${cachedMetadata.chatQuestionsToday}/${effectiveLimits.dailyChatQuestions})")
            showError(fastQuotaCheck.upgradeMessage)
            if (fastQuotaCheck.limitType == PlanEnforcer.LimitType.CHAT_QUESTIONS) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startActivity(
                        Intent(ctx, SubscriptionActivity::class.java)
                            .putExtra("schoolId", SessionManager.getSchoolId(ctx))
                    )
                }, 1500)
            }
            return
        }

        // Refresh metadata from Firestore to catch cross-device / post-restart usage
        FirestoreManager.getUserMetadata(userId, onSuccess = { freshMeta ->
            if (freshMeta != null) {
                // Preserve the local userId in case Firestore deserialization leaves it blank,
                // so the in-memory quota counter (keyed by userId) stays aligned.
                cachedMetadata = if (freshMeta.userId.isBlank()) freshMeta.copy(userId = userId) else freshMeta
                android.util.Log.d(
                    "FullChatFragment",
                    "Meta refreshed before quota: chatAsked=${freshMeta.chatQuestionsToday} bbDone=${freshMeta.bbSessionsToday} questionsUpdatedAt=${freshMeta.questionsUpdatedAt}"
                )
            }
            // Now proceed with the actual message sending using refreshed metadata
            proceedWithSendMessage(
                userText, autoSaveNotes, imageUri, capturedPdfBase64, capturedImageBase64,
                capturedDisplayUri, hadVisualAttachment, featureType, effectiveLimits
            )
        }, onFailure = {
            android.util.Log.w("FullChatFragment", "Meta refresh failed, proceeding with cached: ${it?.message}")
            // Proceed anyway with potentially stale metadata (worst case: user needs to refresh and retry)
            proceedWithSendMessage(
                userText, autoSaveNotes, imageUri, capturedPdfBase64, capturedImageBase64,
                capturedDisplayUri, hadVisualAttachment, featureType, effectiveLimits
            )
        })
    }

    private fun proceedWithSendMessage(
        userText: String,
        autoSaveNotes: Boolean,
        imageUri: android.net.Uri?,
        capturedPdfBase64: String?,
        capturedImageBase64: String?,
        capturedDisplayUri: android.net.Uri?,
        hadVisualAttachment: Boolean,
        featureType: PlanEnforcer.FeatureType,
        effectiveLimits: com.aiguruapp.student.models.PlanLimits
    ) {
        val act = requireActivity()
        val ctx = requireContext()
        var serverPageTranscript: String? = null
        var serverSuggestBlackboard = false

        // Check daily question quota using the FRESHLY REFRESHED metadata
        val questionCheck = PlanEnforcer.checkQuestionsQuota(cachedMetadata, effectiveLimits, isBlackboard = false)
        if (!questionCheck.allowed) {
            selectedImageUri = imageUri
            pdfPageBase64 = capturedPdfBase64
            pendingDisplayUri = capturedDisplayUri
            android.util.Log.e(
                "FullChatFragment",
                "QUOTA BLOCKED: ${questionCheck.reason} (asked=${cachedMetadata.chatQuestionsToday}/${effectiveLimits.dailyChatQuestions})"
            )
            showError(questionCheck.upgradeMessage)
            if (questionCheck.limitType == PlanEnforcer.LimitType.CHAT_QUESTIONS) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startActivity(
                        Intent(ctx, SubscriptionActivity::class.java)
                            .putExtra("schoolId", SessionManager.getSchoolId(ctx))
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
        sendButton.isEnabled = false

        val sysPrompt = TutorController.buildSystemPrompt(tutorSession) +
                PromptRepository.getLanguageInstruction(currentLang)
        val ctxMessage = userText

        val recentMsgs = messageAdapter.getMessages()
            .filter { it.content.isNotBlank() && it.id != userMessage.id }
            .takeLast(10)
        val pageId =
            "${FirestoreManager.safeId(subjectName)}__${FirestoreManager.safeId(chapterName)}"
        val studentLevel = cachedMetadata.grade.filter { it.isDigit() }.toIntOrNull() ?: 5

        val streamingId = UUID.randomUUID().toString()
        val streamingMsg = Message(id = streamingId, content = "", isUser = false)
        val accumulated = StringBuilder()
        var loadingHidden = false
        val onStatus: (String, Int) -> Unit = { message, _ ->
            act.runOnUiThread { loadingStatusText.text = message }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                historyRepo.saveMessage(userMessage)

                val recentHistory =
                    recentMsgs.map { msg ->
                        if (msg.isUser) {
                            "user: ${msg.content}"
                        } else {
                            // Build a compact history entry for the LLM.
                            // - extractAnswerForDisplay already appends extra_details, so we
                            //   do NOT add a separate [extra_summary] tag (avoids duplication).
                            // - Collapse excessive blank lines to prevent \n\n\n\n echoing.
                            // - Only include attachment_transcription when not sending a fresh
                            //   visual attachment (old transcription would conflict with new image).
                            buildString {
                                val answerText = TutorController.extractAnswerForDisplay(msg.content)
                                    .replace(Regex("\\n{3,}"), "\n\n") // collapse 3+ newlines → 2
                                    .trim()
                                append("assistant: $answerText")
                                if (!hadVisualAttachment && msg.transcription.isNotBlank())
                                    append("\n[attachment_transcription: ${msg.transcription.take(500).replace(Regex("\\n{3,}"), "\n\n")}]")
                            }
                        }
                    }

                // When a fresh image/PDF is attached, do NOT inject the old page
                // transcript as system_context — the server will derive fresh context
                // directly from the new attachment.  Injecting stale context causes
                // the LLM to answer based on the previous image instead of the new one.
                val pageContextEntry: List<String> = if (
                    !hadVisualAttachment && tutorSession.latestPageContext.isNotBlank()
                ) {
                    listOf("system_context: Page transcript: ${
                        tutorSession.latestPageContext.take(500)
                            .replace(Regex("\\n{3,}"), "\n\n")
                            .let { if (tutorSession.latestPageContext.length > 500) "$it…" else it }
                    }")
                } else emptyList()
                val historyStrings = pageContextEntry + recentHistory

                val onToken: (String) -> Unit = { token ->
                    accumulated.append(token)
                    act.runOnUiThread {
                        if (!loadingHidden) {
                            loadingHidden = true
                            showLoading(false)
                            messageAdapter.addMessage(streamingMsg)
                        }
                        messageAdapter.updateMessage(streamingId, TutorController.extractAnswerForDisplay(accumulated.toString()))
                        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                    }
                }

                val onDone: (Int, Int, Int) -> Unit = { inputTok, outputTok, totalTok ->
                    if (totalTok > 0) PlanEnforcer.recordTokensUsed(userId, totalTok, inputTok, outputTok)
                    if (hadVisualAttachment && !serverPageTranscript.isNullOrBlank()) {
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
                    act.runOnUiThread {
                        if (totalTok > 0) {
                            cachedMetadata = cachedMetadata.copy(
                                tokensToday = cachedMetadata.tokensToday + totalTok,
                                tokensThisMonth = cachedMetadata.tokensThisMonth + totalTok,
                                tokensUpdatedAt = System.currentTimeMillis()
                            )
                        }
                        // Increment question counter in-memory and Firestore
                        // Guard against day-rollover: if it's a new UTC day, reset to 1 instead
                        // of adding to yesterday's stale count (which would falsely trigger the limit)
                        val isNewQuotaDay = cachedMetadata.questionsUpdatedAt > 0L &&
                            PlanEnforcer.isNewQuotaDay(cachedMetadata.questionsUpdatedAt)
                        PlanEnforcer.recordQuestionAsked(userId, isBlackboard = false, previousUpdatedAt = cachedMetadata.questionsUpdatedAt)
                        cachedMetadata = cachedMetadata.copy(
                            chatQuestionsToday = if (isNewQuotaDay) 1 else cachedMetadata.chatQuestionsToday + 1,
                            questionsUpdatedAt = System.currentTimeMillis()
                        )
                        sendButton.isEnabled = true
                        showLoading(false)
                        val rawResponse = accumulated.toString()
                        if (rawResponse.isNotEmpty()) {
                            val reply = TutorController.parseResponse(rawResponse)
                            TutorController.updateSession(tutorSession, reply.intent, userText)
                            if (reply.transcription.isNotBlank()) {
                                // Sanitize before storing: remove control chars and collapse
                                // excess newlines that cause \n\n\n\n000000 in next sends
                                tutorSession.latestPageContext = reply.transcription
                                    .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")
                                    .replace(Regex("\\n{3,}"), "\n\n")
                                    .trim()
                            }
                            val finalMsg = Message(
                                id = streamingId,
                                content = rawResponse,  // full JSON — all fields preserved in Firestore
                                isUser = false,
                                transcription = reply.transcription,
                                extraSummary = reply.extraSummary
                            )
                            // Replace full message in adapter so transcription/extraSummary
                            // survive in recentHistory for the next turn
                            val msgIdx = messageAdapter.getMessages().indexOfFirst { it.id == streamingId }
                            if (msgIdx >= 0) messageAdapter.updateMessage(msgIdx, finalMsg)
                            val tokensToSave = totalTok.takeIf { it > 0 }
                            historyRepo.saveMessage(finalMsg, tokens = tokensToSave,
                                inputTokens = inputTok.takeIf { it > 0 },
                                outputTokens = outputTok.takeIf { it > 0 })
                            messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)

                            if ((lastInputWasVoice || isVoiceModeActive) && !isAutoExplainActive) {
                                lastInputWasVoice = false
                                val voiceText = TutorController.prepareSpeechText(reply.response)
                                if (isVoiceModeActive) {
                                    currentTTSText = voiceText
                                    setVoiceModeStatus("🔊 AI is speaking…", "#1565C0")
                                }
                                ttsManager.setLocale(Locale.forLanguageTag(currentLang))
                                ttsManager.speak(voiceText, object : TTSCallback {
                                    override fun onStart() {
                                        if (isVoiceModeActive) {
                                            Handler(Looper.getMainLooper()).postDelayed({
                                                if (isVoiceModeActive && ttsManager.isSpeaking()) {
                                                    voiceManager.startInterruptListening(
                                                        interruptCallback, currentLang
                                                    )
                                                }
                                            }, 700)
                                        }
                                    }

                                    override fun onComplete() {
                                        act.runOnUiThread {
                                            voiceManager.stopInterruptListening()
                                            if (isVoiceModeActive && !isInterrupted) startVoiceLoopListening()
                                            isInterrupted = false
                                        }
                                    }

                                    override fun onError(error: String) {
                                        act.runOnUiThread {
                                            voiceManager.stopInterruptListening()
                                            if (isVoiceModeActive) startVoiceLoopListening()
                                        }
                                    }
                                })
                            }
                            if (autoSaveNotes && saveNotesType != null) {
                                notesRepo.save(reply.response, saveNotesType!!)
                            }
                            if (serverSuggestBlackboard || isAutoExplainActive) {
                                showBlackboardNudge(finalMsg)
                            }
                        } else {
                            showError("Couldn't get a response. Check your connection and try again.")
                        }
                    }
                }

                val onError: (String) -> Unit = { err ->
                    act.runOnUiThread { sendButton.isEnabled = true; showLoading(false); showError("Error: $err") }
                }

                val client = buildAiClient()
                val imageDataJson = null
                when {
                    imageUri != null -> {
                        val b64 = capturedImageBase64 ?: mediaManager.uriToBase64(imageUri)
                        if (client is ServerProxyClient) {
                            client.streamChat(ctxMessage, pageId, "normal", currentLang,
                                studentLevel, historyStrings, imageDataJson, b64,
                                { transcript -> serverPageTranscript = transcript },
                                { bb -> serverSuggestBlackboard = bb },
                                onStatus, onToken, onDone, onError)
                        } else {
                            if (b64 != null) client.streamWithImage(sysPrompt, ctxMessage, b64,
                                onToken, onDone, onError)
                            else client.streamText(sysPrompt, ctxMessage, onToken, onDone, onError)
                        }
                    }
                    capturedPdfBase64 != null ->
                        if (client is ServerProxyClient)
                            client.streamChat(ctxMessage, pageId, "normal", currentLang,
                                studentLevel, historyStrings, imageDataJson, capturedPdfBase64,
                                { transcript -> serverPageTranscript = transcript },
                                { bb -> serverSuggestBlackboard = bb },
                                onStatus, onToken, onDone, onError)
                        else
                            client.streamWithImage(sysPrompt, ctxMessage, capturedPdfBase64,
                                onToken, onDone, onError)
                    else ->
                        if (client is ServerProxyClient)
                            client.streamChat(ctxMessage, pageId, "normal", currentLang,
                                studentLevel, historyStrings, null, null,
                                { transcript -> serverPageTranscript = transcript },
                                { bb -> serverSuggestBlackboard = bb },
                                onStatus, onToken, onDone, onError)
                        else
                            client.streamText(sysPrompt, ctxMessage, onToken, onDone, onError)
                }
            } catch (e: Exception) {
                android.util.Log.e("FullChatFragment", "sendMessage crash: ${e.message}", e)
                act.runOnUiThread { sendButton.isEnabled = true; showLoading(false); showError("Error: ${e.message}") }
            }
        }
    }

    // ── Blackboard Nudge ──────────────────────────────────────────────────────

    /**
     * Quietly draws attention to the Explain (BB) button — scrolls the message into
     * view and briefly highlights the button with a single blue flash (no animation pop).
     */
    private fun showBlackboardNudge(msg: Message) {
        blackboardNudgeSnackbar?.dismiss()
        val bbLimits = AdminConfigRepository.resolveEffectiveLimits(
            cachedMetadata.planId, cachedMetadata.planLimits
        )
        val bbCheck = PlanEnforcer.check(cachedMetadata, bbLimits, PlanEnforcer.FeatureType.BLACKBOARD)
        if (!bbCheck.allowed) return  // Don't tease the user if they can't use it

        // Scroll to ensure the message with the BB button is visible
        val msgPosition = messageAdapter.getMessages().indexOf(msg)
        if (msgPosition >= 0) {
            messagesRecyclerView.scrollToPosition(msgPosition)

            // After scroll settles, briefly highlight the BB button (no pop/scale)
            Handler(Looper.getMainLooper()).postDelayed({
                val holder = messagesRecyclerView.findViewHolderForAdapterPosition(msgPosition)
                if (holder is MessageAdapter.MessageViewHolder) {
                    holder.highlightExplainButton()
                }
            }, 200)
        }
    }

    private fun persistLatestPageContext(pageContent: PageContent?) {
        val page = pageContent ?: return
        val resolvedUserId = SessionManager.getFirestoreUserId(requireContext()).also { uid ->
            if (uid != userId) userId = uid
        }
        // Sanitize: collapse runaway blank lines and strip non-printable control
        // characters that occasionally appear in PDF/image transcriptions and
        // cause \n\n\n\n / 000000 garbage in subsequent LLM responses.
        val cleanTranscript = page.transcript
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "") // strip control chars except \n \t
            .replace(Regex("\\n{3,}"), "\n\n") // collapse 3+ newlines → 2
            .trim()
        tutorSession.latestPageContext = cleanTranscript
        FirestoreManager.saveChapterContext(
            userId = resolvedUserId,
            page = page.copy(transcript = cleanTranscript),
            onSuccess = { },
            onFailure = { e ->
                android.util.Log.e("PageContext",
                    "saveChapterContext FAILED userId='$userId': ${e?.message}")
            }
        )
    }

    // ── Flashcards ────────────────────────────────────────────────────────────

    private fun generateFlashcards() {
        val prompt = PromptRepository.getQuickAction("flashcards", subjectName, chapterName)
        messageAdapter.addMessage(
            Message(UUID.randomUUID().toString(),
                "🃏 Generating revision flashcards for $chapterName…", true)
        )
        showLoading(true)

        val sysPrompt = TutorController.buildSystemPrompt(tutorSession)
        val fullResponse = StringBuilder()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            buildAiClient().streamText(
                systemPrompt = sysPrompt,
                userText = prompt,
                onToken = { token -> fullResponse.append(token) },
                onDone = { _, _, _ ->
                    requireActivity().runOnUiThread {
                        showLoading(false)
                        val cards = parseFlashcards(fullResponse.toString())
                        if (cards.isNotEmpty()) {
                            messageAdapter.addMessage(
                                Message(UUID.randomUUID().toString(),
                                    "✅ ${cards.size} flashcards ready! Opening revision mode…", false)
                            )
                            startActivity(
                                Intent(requireContext(), RevisionActivity::class.java)
                                    .putExtra("flashcards", ArrayList(cards))
                            )
                        } else showError("Could not parse flashcards. Please try again.")
                    }
                },
                onError = { err ->
                    requireActivity().runOnUiThread {
                        showLoading(false); showError("Failed to generate flashcards: $err")
                    }
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

    // ── Notes ─────────────────────────────────────────────────────────────────

    private fun saveLastAIMessageAsNotes() {
        val lastAi = messageAdapter.getLastAIMessage() ?: run {
            Toast.makeText(requireContext(),
                "Generate notes first, then tap 💾 Save Notes.", Toast.LENGTH_SHORT).show()
            return
        }
        notesRepo.save(
            content = TutorController.extractAnswerForDisplay(lastAi.content),
            type = saveNotesType ?: "chapter",
            onSuccess = {
                metricsTracker.recordEvent(ChapterMetricsTracker.EventType.NOTES_SAVED)
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "✅ Notes saved!", Toast.LENGTH_SHORT).show()
                }
            },
            onFailure = {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Failed to save notes.", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        )
    }

    private fun viewSavedNotes() {
        com.aiguruapp.student.notes.NotesActivity.launch(
            requireContext(), subjectName, chapterName, userId
        )
    }

    /**
     * Shows a category picker dialog, then saves [note] with the chosen category.
     * If reusing an existing note ID (AI message), updates it in place.
     */
    private fun showCategoryPickerAndSaveNote(note: com.aiguruapp.student.notes.ChapterNote) {
        val cats = chapterNotesRepo.getCategories()
        val options = (cats + listOf("＋ New Category...")).toTypedArray()
        var chosen = cats.firstOrNull() ?: "General"
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("📌 Add to Notes")
            .setSingleChoiceItems(options, 0) { _, which ->
                chosen = if (which < cats.size) cats[which] else "__new__"
            }
            .setPositiveButton("Save") { _, _ ->
                if (chosen == "__new__") {
                    showNewCategoryInput { newCat ->
                        chapterNotesRepo.addCategory(newCat)
                        chapterNotesRepo.saveNote(note.copy(category = newCat))
                        Toast.makeText(requireContext(), "✅ Added to Notes ($newCat)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    chapterNotesRepo.saveNote(note.copy(category = chosen))
                    Toast.makeText(requireContext(), "✅ Added to Notes", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewCategoryInput(onCreated: (String) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Category name"
            setPadding(48, 24, 48, 8)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("New Category")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) onCreated(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Loading / Error ───────────────────────────────────────────────────────

    private fun showLoading(show: Boolean) {
        loadingLayout.visibility = if (show) View.VISIBLE else View.GONE
        if (show) loadingStatusText.text = "AI is thinking..."
    }

    private fun showError(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    /** Refresh the quota pill in the top info bar with today's remaining questions. */
    private fun updateQuotaBanner() {
        // quota display moved to HomeActivity
    }

    // ── VoiceRecognitionCallback ──────────────────────────────────────────────

    override fun onResults(text: String) {
        requireActivity().runOnUiThread {
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
        requireActivity().runOnUiThread {
            messageInput.setText(text)
            messageInput.setSelection(text.length)
        }
    }

    override fun onError(error: String) {
        requireActivity().runOnUiThread {
            if (isVoiceModeActive) startVoiceLoopListening()
            else resetVoiceButton()
        }
    }

    override fun onListeningStarted() {}

    override fun onListeningFinished() {
        requireActivity().runOnUiThread {
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
            val perm = permissions.firstOrNull()
            if (perm == android.Manifest.permission.RECORD_AUDIO) startVoiceInput()
            else if (perm == android.Manifest.permission.CAMERA) openCamera()
        }
    }

    // ── Interactive Voice Chat Mode ───────────────────────────────────────────

    private fun startVoiceMode() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
            return
        }
        isVoiceModeActive = true
        isInterrupted = false
        voiceButton.isEnabled = false
        voiceChatBar.visibility = View.VISIBLE
        listeningIndicator.visibility = View.GONE
        voiceChatButton.backgroundTintList =
            ColorStateList.valueOf(android.graphics.Color.parseColor("#E53935"))
        Toast.makeText(requireContext(), "🎙️ Live Mic ON", Toast.LENGTH_SHORT).show()
        updateLiveModeUi()
        startVoiceLoopListening()
    }

    private fun stopVoiceMode() {
        isVoiceModeActive = false
        isInterrupted = false
        ttsManager.stop()
        voiceManager.stopInterruptListening()
        if (isListening) { voiceManager.stopListening(); isListening = false }
        stopWaveAnimation()
        stopMicPulse()
        voiceButton.isEnabled = true
        voiceChatBar.visibility = View.GONE
        voiceChatButton.backgroundTintList =
            ColorStateList.valueOf(android.graphics.Color.parseColor("#E8F5E9"))
        updateLiveModeUi()
        Toast.makeText(requireContext(), "Voice mode OFF", Toast.LENGTH_SHORT).show()
    }

    private fun startVoiceLoopListening() {
        if (!isVoiceModeActive) return
        isListening = true
        setVoiceModeStatus("🎙️ Listening… say 'stop it' to interrupt", "#2E7D32")
        voiceManager.startListening(this, currentLang)
        startMicPulse()
    }

    private fun updateLiveModeUi() {
        liveModeChip.text = "Live"
        voiceModeBadge.text = "LIVE"
        liveModeChip.setBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
        liveModeChip.setTextColor(android.graphics.Color.parseColor("#B45309"))
        voiceModeBadge.setBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
        voiceModeBadge.setTextColor(android.graphics.Color.parseColor("#B45309"))
    }

    private fun setVoiceModeStatus(text: String, colorHex: String) {
        voiceChatStatus.text = text
        voiceChatStatus.setTextColor(android.graphics.Color.parseColor(colorHex))
        when (colorHex) {
            "#2E7D32", "#6A1B9A" -> startWaveAnimation(colorHex)
            "#1565C0" -> { startWaveAnimation("#1565C0"); stopMicPulse() }
            else -> { stopWaveAnimation(); stopMicPulse() }
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
        return voiceStopWords.any { heard.contains(it) } && !normalizedTTS.contains(heard)
    }

    private fun triggerBargein() {
        if (isInterrupted) return
        isInterrupted = true
        ttsManager.stop()
        voiceManager.stopInterruptListening()
        requireActivity().runOnUiThread {
            setVoiceModeStatus("🎙️ Listening (interrupted)…", "#6A1B9A")
        }
        isListening = true
        voiceManager.startListening(this, currentLang)
    }

    // ── Wave Animation & Mic Pulse ────────────────────────────────────────────

    private fun startWaveAnimation(colorHex: String) {
        waveBarContainer.visibility = View.VISIBLE
        val tint = ColorStateList.valueOf(android.graphics.Color.parseColor(colorHex))
        listOf(waveBar1, waveBar2, waveBar3, waveBar4).forEach { it.backgroundTintList = tint }
        val durations = longArrayOf(420L, 600L, 360L, 510L)
        val delays = longArrayOf(0L, 130L, 260L, 80L)
        listOf(waveBar1, waveBar2, waveBar3, waveBar4).forEachIndexed { i, bar ->
            bar.clearAnimation()
            bar.startAnimation(
                ScaleAnimation(1f, 1f, 0.15f, 1f,
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
            ScaleAnimation(1f, 1.15f, 1f, 1.15f,
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
