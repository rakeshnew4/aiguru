package com.aiguruapp.student

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yalantis.ucrop.UCrop
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
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
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebSettings
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.aiguruapp.student.utils.WikimediaUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.aiguruapp.student.bb.BbInteractivePopup
import com.aiguruapp.student.chat.BlackboardGenerator
import com.aiguruapp.student.chat.ServerProxyClient
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.config.PlanEnforcer
import com.aiguruapp.student.validators.AiVoiceQuotaValidator
import com.aiguruapp.student.validators.BlackboardQuotaValidator
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.UserMetadata
import com.aiguruapp.student.utils.FeedbackManager
import com.aiguruapp.student.utils.MediaManager
import com.aiguruapp.student.utils.PromptRepository
import com.aiguruapp.student.utils.SessionManager
import com.aiguruapp.student.utils.TTSCallback
import com.aiguruapp.student.utils.TextToSpeechManager
import com.aiguruapp.student.utils.VoiceManager
import com.aiguruapp.student.utils.VoiceRecognitionCallback
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
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
        const val EXTRA_SUBJECT         = "extra_subject"
        const val EXTRA_CHAPTER         = "extra_chapter"
        /** Task ID — when set, BB completion is tracked against this task. */
        const val EXTRA_TASK_ID         = "extra_task_id"
        /** True when the user is replaying a previously saved session — skips quota recording. */
        const val EXTRA_IS_REPLAY       = "extra_is_replay"
        /** ArrayList<String> of MD5 TTS keys saved with the session for instant audio on replay. */
        const val EXTRA_TTS_KEYS        = "extra_tts_keys"
        /**
         * Global bb_cache document ID.
         * When set the lesson is loaded directly from [bb_cache/{id}] without any LLM call.
         * Used for teacher-assigned tasks so every student shares the same cached lesson.
         */
        const val EXTRA_BB_CACHE_ID     = "extra_bb_cache_id"
        /**
         * Saved session ID from [saved_bb_sessions_flat/{id}].
         * When set together with [EXTRA_IS_REPLAY], the lesson is loaded directly from the
         * saved session document (steps_json field) — no LLM call, no quota consumed.
         */
        const val EXTRA_SESSION_ID      = "extra_session_id"
        /** BbDuration label string, e.g. "2 min". Determines totalSteps & framesPerStep. */
        const val EXTRA_DURATION        = "extra_duration"
        /** Optional Base64-encoded image to include in the lesson generation (e.g. from home dialog camera). */
        const val EXTRA_IMAGE_BASE64    = "extra_image_base64"
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
    private lateinit var pauseBtn:        TextView
    private lateinit var replayBtn:       TextView
    private lateinit var prevBtn:         TextView
    private lateinit var nextBtn:         TextView
    private lateinit var progressSeekBar: android.widget.ProgressBar
    private lateinit var bbProgressHintTv: TextView
    private lateinit var bbSubtitleTv: TextView
    private lateinit var handWriter:      TextView

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeechManager
    private lateinit var aiTtsEngine: com.aiguruapp.student.tts.BbAiTtsEngine
    private var useAiTts = false
    private lateinit var aiTtsToggleBtn: TextView
    private var steps            = listOf<BlackboardGenerator.BlackboardStep>()
    private var currentStepIdx   = 0
    private var currentFrameIdx  = 0
    private var maxStepReached   = -1   // highest step index visited (for task progress tracking)
    private var bbStartTimeMs    = 0L   // when lesson content first became visible
    private var isPaused         = false
    private var typeAnimator:    ValueAnimator? = null
    private var seekBarAnimator: ValueAnimator? = null
    private var computedFontSp   = 30f
    private var preferredLanguageTag = "en-US"

    // Board views created once in setupBoard(), updated each frame
    private var boardLayout: LinearLayout? = null

    // Reveal button reference for the current quiz frame (set in showFrame, used by TTS callback)
    private var quizRevealBtn: android.widget.TextView? = null

    // Inline quiz card added to board in showFrame(), made visible after TTS completes
    private var pendingQuizCard: View? = null

    // Interactive chat history from the BB-screen ask bar
    private val bbChatHistory = mutableListOf<String>()

    // Ask-bar views
    private lateinit var bbAskInput: EditText
    private lateinit var bbAskSendBtn: TextView
    private lateinit var bbAskToggle: TextView   // close X inside the ask bar overlay
    private lateinit var bbChatFab: TextView      // floating chat button
    private var bbAskBarExpanded = false

    // BB ask-bar: image attachment + voice
    private var bbCameraImageUri: Uri? = null
    private var bbPendingImageUri: Uri? = null
    private var bbPendingImageBase64: String? = null
    private var bbIsListening = false
    private lateinit var bbVoiceManager: VoiceManager
    private lateinit var bbMediaManager: MediaManager
    private lateinit var bbCameraBtn: TextView
    private lateinit var bbMicBtn: TextView
    private lateinit var bbImgPreviewRow: LinearLayout
    private lateinit var bbImgPreviewThumb: ImageView
    private lateinit var bbImgPreviewRemove: TextView

    // ── Activity-result launchers ──────────────────────────────────────────────
    private val bbCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && bbCameraImageUri != null) launchBbCrop(bbCameraImageUri!!)
        }

    private val bbGalleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) launchBbCrop(uri)
        }

    private val bbCropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            when (result.resultCode) {
                android.app.Activity.RESULT_OK -> {
                    val cropped = data?.let { UCrop.getOutput(it) } ?: return@registerForActivityResult
                    encodeBbImage(cropped)
                }
                UCrop.RESULT_ERROR -> {
                    android.util.Log.w("BB", "UCrop error: ${data?.let { UCrop.getError(it)?.message }}")
                }
            }
        }

    // Quota chip in the top bar
    private lateinit var bbQuotaChip: android.widget.TextView
    private lateinit var saveSessionBtn: TextView
    private var sessionAlreadySaved = false
    private lateinit var bbCompletionCard: View
    private lateinit var stepNamesScrollView: android.widget.HorizontalScrollView
    private lateinit var stepNamesContainer: LinearLayout

    // Cached user metadata for quota checks
    private var cachedMetadata = UserMetadata()

    // ── Progressive generation state ───────────────────────────────────────────
    private var bbDuration          = BlackboardGenerator.BbDuration.MIN_2
    private var totalStepsTarget    = BlackboardGenerator.BbDuration.MIN_2.totalSteps
    private var framesPerStepTarget = BlackboardGenerator.BbDuration.MIN_2.framesPerStep
    private var bbIntent: BlackboardGenerator.BlackboardIntent? = null
    private var isGeneratingNextChunk = false
    private var currentTopic        = ""

    // ── Interactive quiz score tracking ────────────────────────────────────────
    private var quizTotal       = 0
    private var quizCorrect     = 0
    private var currentStreak   = 0
    // Each bookmark: Triple(stepIdx, frameIdx, stepTitle)
    private val bookmarkedFrames = mutableListOf<Triple<Int, Int, String>>()

    // ── Teacher publish button (only shown in teacher-mode) ───────────────────
    // Visible when user is a teacher and lesson was just generated (not loaded from cache).
    // Tapping it saves the current lesson to the global bb_cache so it can be assigned to students.
    private lateinit var publishLessonBtn: android.widget.TextView
    private var isTeacherMode = false
    private var publishedBbCacheId = ""   // set after successful publish

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
        pauseBtn        = findViewById(R.id.pauseButton)
        replayBtn       = findViewById(R.id.replayButton)
        prevBtn         = findViewById(R.id.prevButton)
        nextBtn         = findViewById(R.id.nextButton)
        progressSeekBar = findViewById(R.id.bbProgressSeek)
        bbProgressHintTv = findViewById(R.id.bbProgressHint)
        bbSubtitleTv     = findViewById(R.id.bbSubtitleTv)
        handWriter      = findViewById(R.id.handWriter)
        bbQuotaChip     = findViewById(R.id.bbQuotaChip)
        saveSessionBtn  = findViewById(R.id.saveSessionBtn)
        publishLessonBtn = findViewById(R.id.publishLessonBtn)
        bbCompletionCard = findViewById(R.id.bbCompletionCard)
        stepNamesScrollView = findViewById(R.id.stepNamesScrollView)
        stepNamesContainer  = findViewById(R.id.stepNamesContainer)
        bbAskInput      = findViewById(R.id.bbAskInput)
        bbAskSendBtn    = findViewById(R.id.bbAskSend)
        bbAskToggle     = findViewById(R.id.bbAskToggle)
        bbChatFab       = findViewById(R.id.bbChatFab)
        bbCameraBtn       = findViewById(R.id.bbCameraBtn)
        bbMicBtn          = findViewById(R.id.bbMicBtn)
        bbImgPreviewRow   = findViewById(R.id.bbImgPreviewRow)
        bbImgPreviewThumb = findViewById(R.id.bbImgPreviewThumb)
        bbImgPreviewRemove = findViewById(R.id.bbImgPreviewRemove)

        bbVoiceManager = VoiceManager(this)
        bbMediaManager = MediaManager(this)

        val sendBbQuestion = {
            val q = bbAskInput.text.toString().trim()
            if (q.isNotBlank()) { bbAskInput.setText(""); sendBbChat(q) }
        }
        bbAskSendBtn.setOnClickListener { sendBbQuestion() }
        bbAskInput.setOnEditorActionListener { _, _, _ -> sendBbQuestion(); true }
        bbAskToggle.setOnClickListener { toggleAskBar() }   // ✕ inside ask bar
        bbChatFab.setOnClickListener { toggleAskBar() }     // 💬 floating FAB
        bbCameraBtn.setOnClickListener { launchBbCamera() }
        bbImgPreviewRemove.setOnClickListener { clearBbImage() }
        bbMicBtn.setOnClickListener {
            if (bbIsListening) bbVoiceManager.stopListening() else startBbVoice()
        }

        closeBtn.setOnClickListener  {
            if (steps.isNotEmpty()) FeedbackManager.markBbSessionDone(this)
            finish()
        }
        saveSessionBtn.setOnClickListener { saveCurrentSession() }
        publishLessonBtn.setOnClickListener { publishCurrentLesson() }
        findViewById<TextView>(R.id.completionSaveBtn).setOnClickListener {
            bbCompletionCard.visibility = View.GONE
            saveCurrentSession()
        }
        findViewById<TextView>(R.id.completionReplayBtn).setOnClickListener {
            bbCompletionCard.visibility = View.GONE
            restartLesson()
        }
        findViewById<TextView>(R.id.completionCloseBtn).setOnClickListener {
            bbCompletionCard.visibility = View.GONE
        }
        replayBtn.setOnClickListener { restartLesson() }
        pauseBtn.setOnClickListener  { togglePause() }
        prevBtn.setOnClickListener   { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); prevStep() }
        nextBtn.setOnClickListener   { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); nextStep() }

        // Teacher mode: show publish button so this lesson can be shared with students
        isTeacherMode = com.aiguruapp.student.utils.SessionManager.isTeacher(this)
        publishLessonBtn.visibility = View.GONE  // shown after lesson generates

        PromptRepository.init(this)
        tts = TextToSpeechManager(this)
        aiTtsEngine = com.aiguruapp.student.tts.BbAiTtsEngine(this, tts)
        aiTtsToggleBtn = findViewById(R.id.aiTtsToggleBtn)

        // If replaying a saved session, pre-warm TTS engine with stored audio keys
        // so cached MP3s are served instantly without any regeneration
        intent.getStringArrayListExtra(EXTRA_TTS_KEYS)?.let { keys ->
            if (keys.isNotEmpty()) aiTtsEngine.prewarmKeys(keys)
        }

        // Restore AI TTS preference
        val prefs = getSharedPreferences("bb_prefs", MODE_PRIVATE)
        useAiTts = prefs.getBoolean("use_ai_tts", false)
        updateAiTtsToggleUi()

        aiTtsToggleBtn.setOnClickListener {
            useAiTts = !useAiTts
            android.util.Log.d("BB_TTS_TOGGLE", "AI TTS button clicked: useAiTts=$useAiTts")
            prefs.edit().putBoolean("use_ai_tts", useAiTts).apply()
            updateAiTtsToggleUi()
            if (useAiTts) {
                // Immediately check plan permission
                val limits = AdminConfigRepository.resolveEffectiveLimits(
                    cachedMetadata.planId, cachedMetadata.planLimits
                )
                val check = AiVoiceQuotaValidator.checkFeature(
                    cachedMetadata, limits
                )
                if (!check.allowed) {
                    android.util.Log.w("BB_TTS_TOGGLE", "❌ Plan check failed: ${check.upgradeMessage}")
                    useAiTts = false
                    prefs.edit().putBoolean("use_ai_tts", false).apply()
                    updateAiTtsToggleUi()
                    android.widget.Toast.makeText(this, check.upgradeMessage, android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // Preload current + next 2 speech texts with new setting
                android.util.Log.d("BB_TTS_TOGGLE", "✓ AI TTS enabled, preloading...")
                preloadUpcoming(currentStepIdx, currentFrameIdx, count = 3)
                android.widget.Toast.makeText(this, "🎙 AI Voice ON — preloading…", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.util.Log.d("BB_TTS_TOGGLE", "✗ AI TTS disabled")
                aiTtsEngine.stop()
                android.widget.Toast.makeText(this, "🔊 Android TTS", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Prefer SessionManager lang (set from HomeActivity) over the intent extra so that
        // language changes made after the fragment launched are always reflected.
        val intentLang = intent.getStringExtra(EXTRA_LANGUAGE_TAG)?.takeIf { it.isNotBlank() } ?: "en-US"
        val sessionLang = com.aiguruapp.student.utils.SessionManager.getPreferredLang(this)
        preferredLanguageTag = sessionLang.ifBlank { intentLang }
        tts.setLocale(Locale.forLanguageTag(preferredLanguageTag))

        // Read session duration chosen in the launch sheet
        val durationLabel = intent.getStringExtra(EXTRA_DURATION) ?: ""
        bbDuration = BlackboardGenerator.BbDuration.fromLabel(durationLabel) ?: BlackboardGenerator.BbDuration.MIN_2
        totalStepsTarget    = bbDuration.totalSteps
        framesPerStepTarget = bbDuration.framesPerStep
        currentTopic = intent.getStringExtra(EXTRA_MESSAGE) ?: ""

        val userId = intent.getStringExtra(EXTRA_USER_ID)
        AdminConfigRepository.fetchIfStale { _ ->
            // Wire the server URL for AI TTS as soon as config is loaded
            aiTtsEngine.selfHostedUrl = AdminConfigRepository.ttsSelfHostedUrl()
        }

        // Read user fields directly from Firestore — force SERVER source so the quota counter
        // is never served from the local disk cache (which may be stale from previous sessions).
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users_table").document(userId ?: "")
            .get(com.google.firebase.firestore.Source.SERVER)
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val planId         = doc.getString("planId") ?: "free"
                    val chatToday      = doc.getLong("chat_questions_today")?.toInt() ?: 0
                    val bbToday        = doc.getLong("bb_sessions_today")?.toInt() ?: 0
                    val updatedAt      = doc.getLong("questions_updated_at") ?: 0L
                    val bonusToday     = doc.getLong("bonus_questions_today")?.toInt() ?: 0
                    val aiTtsToday     = doc.getLong("ai_tts_chars_used_today")?.toInt() ?: 0
                    val aiTtsUpdatedAt = doc.getLong("ai_tts_updated_at") ?: 0L
                    val planTtsEnabled    = doc.getBoolean("plan_tts_enabled") ?: true
                    val planAiTtsEnabled  = doc.getBoolean("plan_ai_tts_enabled") ?: false
                    val planBbEnabled     = doc.getBoolean("plan_blackboard_enabled") ?: true
                    val planExpiryDate    = doc.getLong("plan_expiry_date") ?: 0L

                    cachedMetadata = cachedMetadata.copy(
                        planId               = planId.ifBlank { "free" },
                        chatQuestionsToday   = chatToday,
                        bbSessionsToday      = bbToday,
                        questionsUpdatedAt   = updatedAt,
                        bonusQuestionsToday  = bonusToday,
                        aiTtsCharsUsedToday  = aiTtsToday,
                        aiTtsUpdatedAt       = aiTtsUpdatedAt,
                        planTtsEnabled       = planTtsEnabled,
                        planAiTtsEnabled     = planAiTtsEnabled,
                        planBlackboardEnabled = planBbEnabled,
                        planExpiryDate       = planExpiryDate
                    )

                    AdminConfigRepository.resolveEffectiveLimitsAsync(cachedMetadata.planId, cachedMetadata.planLimits) { limits ->
                        runOnUiThread {
                            updateBbQuotaChip(userId)

                            // If launched from a teacher-assigned task, load from global bb_cache
                            // (no LLM call, no quota consumed — lesson was generated once by teacher)
                            val bbCacheId = intent.getStringExtra(EXTRA_BB_CACHE_ID).orEmpty()
                            if (bbCacheId.isNotBlank()) {
                                loadFromGlobalCache(bbCacheId, userId)
                                return@runOnUiThread
                            }

                            // If replaying a saved session, load steps directly from Firestore
                            // — no LLM call, no quota consumed.
                            val savedSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                            if (intent.getBooleanExtra(EXTRA_IS_REPLAY, false) && savedSessionId.isNotBlank() && !userId.isNullOrBlank()) {
                                loadFromSavedSession(savedSessionId, userId)
                                return@runOnUiThread
                            }

                            val check = BlackboardQuotaValidator.check(cachedMetadata, limits)
                            if (!check.allowed) {
                                loadingGroup.visibility = android.view.View.GONE
                                loadingText.text = check.upgradeMessage
                                loadingText.visibility = android.view.View.VISIBLE
                                android.widget.Toast.makeText(this, check.upgradeMessage, android.widget.Toast.LENGTH_LONG).show()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    startActivity(android.content.Intent(this, SubscriptionActivity::class.java))
                                }, 2000)
                                return@runOnUiThread
                            }
                            val isReplay = intent.getBooleanExtra(EXTRA_IS_REPLAY, false)
                            generateSteps(
                                message        = intent.getStringExtra(EXTRA_MESSAGE) ?: "",
                                messageId      = intent.getStringExtra(EXTRA_MESSAGE_ID),
                                userId         = userId,
                                conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                                recordSession  = !isReplay,
                                imageBase64    = intent.getStringExtra(EXTRA_IMAGE_BASE64)
                            )
                        }
                    }
                } else {
                    // No user doc — check for global cache / saved session first, then generate
                    val bbCacheId = intent.getStringExtra(EXTRA_BB_CACHE_ID).orEmpty()
                    val savedSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                    when {
                        bbCacheId.isNotBlank() -> loadFromGlobalCache(bbCacheId, userId)
                        intent.getBooleanExtra(EXTRA_IS_REPLAY, false) && savedSessionId.isNotBlank() && !userId.isNullOrBlank() ->
                            loadFromSavedSession(savedSessionId, userId)
                        else -> generateSteps(
                            message        = intent.getStringExtra(EXTRA_MESSAGE) ?: "",
                            messageId      = intent.getStringExtra(EXTRA_MESSAGE_ID),
                            userId         = userId,
                            conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                            recordSession  = false,
                            imageBase64    = intent.getStringExtra(EXTRA_IMAGE_BASE64)
                        )
                    }
                }
            }
            .addOnFailureListener {
                // Firestore unavailable — try global cache / saved session first, then generate
                val bbCacheId = intent.getStringExtra(EXTRA_BB_CACHE_ID).orEmpty()
                val savedSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                when {
                    bbCacheId.isNotBlank() -> loadFromGlobalCache(bbCacheId, userId)
                    intent.getBooleanExtra(EXTRA_IS_REPLAY, false) && savedSessionId.isNotBlank() && !userId.isNullOrBlank() ->
                        loadFromSavedSession(savedSessionId, userId)
                    else -> generateSteps(
                        message        = intent.getStringExtra(EXTRA_MESSAGE) ?: "",
                        messageId      = intent.getStringExtra(EXTRA_MESSAGE_ID),
                        userId         = userId,
                        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                        recordSession  = false,
                        imageBase64    = intent.getStringExtra(EXTRA_IMAGE_BASE64)
                    )
                }
            }
    }

    override fun onPause() {
        super.onPause()
        aiTtsEngine.stop()
    }

    override fun onDestroy() {
        typeAnimator?.cancel()
        aiTtsEngine.destroy()
        // tts is owned by aiTtsEngine.androidTts — already destroyed above
        if (::bbVoiceManager.isInitialized) bbVoiceManager.destroy()
        // Mark session done so HomeActivity.onResume can show feedback if needed.
        if (steps.isNotEmpty()) FeedbackManager.markBbSessionDone(this)
        super.onDestroy()
    }

    private fun updateAiTtsToggleUi() {
        if (useAiTts) {
            aiTtsToggleBtn.text  = "🎙 AI"
            aiTtsToggleBtn.setTextColor(android.graphics.Color.parseColor("#A0FFD0"))
            aiTtsToggleBtn.setBackgroundColor(android.graphics.Color.parseColor("#224433"))
        } else {
            aiTtsToggleBtn.text  = "🔊 TTS"
            aiTtsToggleBtn.setTextColor(android.graphics.Color.parseColor("#AABBCC"))
            aiTtsToggleBtn.setBackgroundColor(android.graphics.Color.parseColor("#333555"))
        }
    }

    /**
     * Preload AI TTS audio for the next [count] frames starting from [stepIdx]/[frameIdx].
     * Called after steps are ready and whenever the frame advances.
     */
    private fun preloadUpcoming(stepIdx: Int, frameIdx: Int, count: Int = 2) {
        if (!useAiTts) return

        // Ensure TTS keys are loaded from config (returns immediately if cached)
        ensureTtsKeysLoaded()

        var remaining = count
        var si = stepIdx
        var fi = frameIdx
        while (remaining > 0) {
            val frame  = steps.getOrNull(si)?.frames?.getOrNull(fi) ?: break
            val speech = frame.speech
            if (speech.isNotBlank()) {
                val engine = frame.ttsEngine.ifBlank {
                    BlackboardGenerator.smartAssignTts(frame.frameType).first
                }
                if (engine != "android") {
                    aiTtsEngine.languageCode = preferredLanguageTag
                    aiTtsEngine.preload(speech, engine)
                }
            }
            fi++
            if (fi >= (steps.getOrNull(si)?.frames?.size ?: 0)) { si++; fi = 0 }
            if (si >= steps.size) break
            remaining--
        }
    }

    /** Ensure the server URL is loaded into the TTS engine (fast, no Firestore call if cache is warm). */
    private fun ensureTtsKeysLoaded() {
        if (aiTtsEngine.selfHostedUrl.isNotBlank()) return
        try {
            aiTtsEngine.selfHostedUrl = com.aiguruapp.student.config.AdminConfigRepository.ttsSelfHostedUrl()
        } catch (e: Exception) {
            android.util.Log.w("BB_TTS", "ensureTtsKeysLoaded failed: ${e.message}")
        }
    }

    // ── Generation ────────────────────────────────────────────────────────────

    private fun generateSteps(
        message: String,
        messageId: String? = null,
        userId: String? = null,
        conversationId: String? = null,
        recordSession: Boolean = false,
        imageBase64: String? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            // ── Local message cache: skip LLM if this message was already explained ──
            val msgCacheKey = messageId?.takeIf { it.isNotBlank() }
            if (msgCacheKey != null && imageBase64.isNullOrBlank()) {
                val cachedJson = getSharedPreferences("bb_msg_cache", MODE_PRIVATE)
                    .getString(msgCacheKey, null)
                if (!cachedJson.isNullOrBlank()) {
                    val cachedSteps = BlackboardGenerator.deserializeSteps(cachedJson, preferredLanguageTag)
                    if (cachedSteps.isNotEmpty()) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            steps = cachedSteps
                            computedFontSp = computeFontSize(steps)
                            progressSeekBar.max = steps.sumOf { it.frames.size }
                            loadingGroup.visibility = View.GONE
                            contentGroup.visibility = View.VISIBLE
                            saveSessionBtn.visibility = View.VISIBLE
                            findViewById<android.widget.LinearLayout>(R.id.bbAskBar)?.visibility = View.VISIBLE
                            if (isTeacherMode) publishLessonBtn.visibility = View.VISIBLE
                            buildDots()
                            setupBoard()
                            preloadUpcoming(0, 0, count = 3)
                            showFrame(0, 0)
                        }
                        return@launch
                    }
                }
            }

            // ── Step 1: Get lesson outline from LLM ────────────────────────
            runOnUiThread { loadingText.text = "Planning lesson…" }
            BlackboardGenerator.callIntent(
                topic            = message,
                totalSteps       = totalStepsTarget,
                imageBase64      = imageBase64,
                preferredLanguageTag = preferredLanguageTag,
                onSuccess = { intent_ ->
                    bbIntent = intent_
                    // ── Step 2: Generate first chunk ───────────────────────
                    val firstTitles = intent_.stepTitles.take(BlackboardGenerator.CHUNK_SIZE)
                    val isOnlyChunk = totalStepsTarget <= BlackboardGenerator.CHUNK_SIZE
                    runOnUiThread { loadingText.text = "Building lesson…" }
                    BlackboardGenerator.generateChunk(
                        topic            = message,
                        intent           = intent_,
                        chunkStepTitles  = firstTitles,
                        framesPerStep    = framesPerStepTarget,
                        isLastChunk      = isOnlyChunk,
                        imageBase64      = imageBase64,
                        preferredLanguageTag = preferredLanguageTag,
                        onStatus = { statusMsg, _ ->
                            runOnUiThread { loadingText.text = statusMsg }
                        },
                        onSuccess = { generated ->
                            if (recordSession && !userId.isNullOrBlank()) {
                                // Server already incremented the quota counter via check_and_record_quota().
                                // Keep local metadata in sync for accurate UI display — no Firestore write.
                                val isNewQuotaDay = cachedMetadata.questionsUpdatedAt > 0L &&
                                    PlanEnforcer.isNewQuotaDay(cachedMetadata.questionsUpdatedAt)
                                cachedMetadata = cachedMetadata.copy(
                                    bbSessionsToday = if (isNewQuotaDay) 1 else cachedMetadata.bbSessionsToday + 1,
                                    questionsUpdatedAt = System.currentTimeMillis()
                                )
                                val bbSubject = intent.getStringExtra(EXTRA_SUBJECT) ?: "General"
                                val bbChapter = intent.getStringExtra(EXTRA_CHAPTER) ?: "General"
                                com.aiguruapp.student.firestore.StudentStatsManager.recordBbSession(
                                    userId  = userId,
                                    subject = bbSubject,
                                    chapter = bbChapter,
                                    context = this@BlackboardActivity
                                )
                                lifecycleScope.launch(Dispatchers.Main) { updateBbQuotaChip(userId) }
                            }
                            lifecycleScope.launch(Dispatchers.Main) {
                                steps = generated
                                // Cache by messageId so re-opening the same message skips re-generation
                                if (!msgCacheKey.isNullOrBlank()) {
                                    getSharedPreferences("bb_msg_cache", MODE_PRIVATE).edit()
                                        .putString(msgCacheKey, BlackboardGenerator.serializeSteps(steps))
                                        .apply()
                                }
                                computedFontSp = computeFontSize(steps)
        progressSeekBar.max = (totalStepsTarget * framesPerStepTarget).coerceAtLeast(generated.sumOf { it.frames.size })
                                loadingGroup.visibility = View.GONE
                                contentGroup.visibility = View.VISIBLE
                                saveSessionBtn.visibility = View.VISIBLE
                                findViewById<android.widget.LinearLayout>(R.id.bbAskBar)?.visibility = View.VISIBLE
                                if (isTeacherMode) publishLessonBtn.visibility = View.VISIBLE
                                buildDots()
                                setupBoard()
                                preloadUpcoming(0, 0, count = 3)
                                showFrame(0, 0)
                                incrementLocalBbCounter()
                                showFirstTimerTipsIfNeeded()
                                // Record lesson start time — actual read tracking happens step-by-step in showFrame()
                                if (!isTeacherMode && intent.hasExtra(EXTRA_TASK_ID)) {
                                    bbStartTimeMs = System.currentTimeMillis()
                                }
                            }
                        },
                        onError = { err ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (err.startsWith("QUOTA_EXCEEDED:")) {
                                    loadingText.text = err.removePrefix("QUOTA_EXCEEDED:")
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        startActivity(
                                            Intent(
                                                this@BlackboardActivity,
                                                SubscriptionActivity::class.java
                                            )
                                            .putExtra("schoolId", SessionManager.getSchoolId(this@BlackboardActivity)))
                                    }, 1500)
                                } else {
                                    loadingText.text = "Couldn't build lesson. Please try again."
                                    android.util.Log.e("Blackboard", "Chunk error: $err")
                                }
                            }
                        }
                    )
                },
                onError = { err ->
                    // Intent call failed — fall back to old single-pass generate()
                    android.util.Log.w("Blackboard", "Intent failed ($err), falling back to generate()")
                    BlackboardGenerator.generate(
                        messageContent = message,
                        messageId      = messageId,
                        userId         = userId,
                        conversationId = conversationId,
                        preferredLanguageTag = preferredLanguageTag,
                        onStatus = { statusMsg, _ ->
                            runOnUiThread { loadingText.text = statusMsg }
                        },
                        onSuccess = { generated ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                steps = generated
                                computedFontSp = computeFontSize(steps)
                                loadingGroup.visibility = View.GONE
                                contentGroup.visibility = View.VISIBLE
                                saveSessionBtn.visibility = View.VISIBLE
                                findViewById<android.widget.LinearLayout>(R.id.bbAskBar)?.visibility = View.VISIBLE
                                if (isTeacherMode) publishLessonBtn.visibility = View.VISIBLE
                                buildDots()
                                setupBoard()
                                preloadUpcoming(0, 0, count = 3)
                                showFrame(0, 0)
                            }
                        },
                        onError = { e ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                if (e.startsWith("QUOTA_EXCEEDED:")) {
                                    loadingText.text = e.removePrefix("QUOTA_EXCEEDED:")
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        startActivity(Intent(this@BlackboardActivity, SubscriptionActivity::class.java)
                                            .putExtra("schoolId", SessionManager.getSchoolId(this@BlackboardActivity)))
                                    }, 1500)
                                } else {
                                    loadingText.text = "Couldn't build lesson. Please try again."
                                    android.util.Log.e("Blackboard", "Generation error: $e")
                                }
                            }
                        }
                    )
                }
            )
        }
    }

    // ── Progressive chunk generation ───────────────────────────────────────────

    /**
     * Fetches the next batch of steps from the LLM and appends them to [steps].
     * Triggered automatically from [showFrame] when the user is 2 steps from the end
     * and more steps are expected.
     */
    private fun triggerNextChunk() {
        val intent_ = bbIntent ?: return
        if (isGeneratingNextChunk) return
        if (steps.size >= totalStepsTarget) return

        isGeneratingNextChunk = true

        val alreadyGenerated   = steps.size
        val remaining          = totalStepsTarget - alreadyGenerated
        val nextTitles         = intent_.stepTitles.drop(alreadyGenerated).take(BlackboardGenerator.CHUNK_SIZE)
        val isLast             = (alreadyGenerated + nextTitles.size) >= totalStepsTarget

        // Build a comprehensive context block listing everything already covered
        val contextSummary = buildString {
            appendLine("Student's original question: $currentTopic")
            appendLine()
            appendLine("Steps ALREADY TAUGHT (do NOT repeat or reintroduce these):")
            steps.forEachIndexed { idx, step ->
                append("  ${idx + 1}. ${step.title}")
                // Include a brief snippet from the first speech of each step so the
                // LLM understands what depth was already covered
                val firstSpeech = step.frames.firstOrNull { it.speech.isNotBlank() }?.speech
                if (!firstSpeech.isNullOrBlank()) {
                    append(" — \"${firstSpeech.take(90)}\"")
                }
                appendLine()
            }
            appendLine()
            appendLine("Total steps covered so far: ${steps.size} / $totalStepsTarget")
            appendLine("Continue from step ${steps.size + 1}. Build on what was taught — go deeper, give examples, advance the lesson.")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            BlackboardGenerator.generateChunk(
                topic            = currentTopic,
                intent           = intent_,
                chunkStepTitles  = nextTitles,
                framesPerStep    = framesPerStepTarget,
                previousContext  = contextSummary,
                isLastChunk      = isLast,
                preferredLanguageTag = preferredLanguageTag,
                onSuccess = { newSteps ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        steps = steps + newSteps
                        progressSeekBar.max = steps.sumOf { it.frames.size }
                        buildDots()
                        updateCounterAndDots()
                        isGeneratingNextChunk = false
                    }
                },
                onError = { err ->
                    android.util.Log.w("Blackboard", "Next chunk error: $err")
                    if (err.startsWith("QUOTA_EXCEEDED:")) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                this@BlackboardActivity,
                                err.removePrefix("QUOTA_EXCEEDED:"),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startActivity(Intent(this@BlackboardActivity, SubscriptionActivity::class.java)
                                    .putExtra("schoolId", SessionManager.getSchoolId(this@BlackboardActivity)))
                            }, 1500)
                        }
                    }
                    isGeneratingNextChunk = false
                }
            )
        }
    }

    // ── Local usage counter + first-timer tips ─────────────────────────────────

    private fun incrementLocalBbCounter() {
        val prefs = getSharedPreferences("bb_prefs", MODE_PRIVATE)
        val current = prefs.getInt("bb_sessions_alltime", 0)
        prefs.edit().putInt("bb_sessions_alltime", current + 1).apply()
    }

    private fun showFirstTimerTipsIfNeeded() {
        val prefs    = getSharedPreferences("bb_prefs", MODE_PRIVATE)
        val sessions = prefs.getInt("bb_sessions_alltime", 0)
        if (sessions > 3) return   // only show for first 3 sessions

        val tips = when (sessions) {
            1 -> "👋 Welcome to Blackboard Mode!\n\n" +
                    "• Tap ▶ / ◀ to move between slides\n" +
                    "• Tap ⏸ to pause the lesson\n" +
                    "• Tap 💬 to ask follow-up questions\n" +
                    "• Tap 💾 Save to keep this lesson"
            2 -> "📷 Did you know?\n\n" +
                    "Tap the 📷 icon in the ask bar to attach a photo of your textbook — the AI will explain it!"
            3 -> "🎤 Voice questions work too!\n\n" +
                    "Tap 🎤 in the ask bar and speak your question hands-free."
            else -> return
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage(tips)
                    .setPositiveButton("Got it! 👍", null)
                    .show()
            }
        }, 1500)
    }

    // ── Save session to chapter notes ──────────────────────────────────────────

    private fun saveCurrentSession() {
        if (sessionAlreadySaved) return
        val uid = intent.getStringExtra(EXTRA_USER_ID) ?: run {
            android.widget.Toast.makeText(this, "Sign in to save sessions", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "General"
        val chapter = intent.getStringExtra(EXTRA_CHAPTER) ?: "General"
        val topic   = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val convId  = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: java.util.UUID.randomUUID().toString()
        val msgId   = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: ""
        val sessionId = "${convId}_${System.currentTimeMillis()}"

        // Collect TTS audio keys for all frames so replays can skip re-generation
        val ttsKeys = steps.flatMap { step ->
            step.frames.mapNotNull { frame ->
                if (frame.speech.isBlank()) null
                else {
                    val engine = frame.ttsEngine.ifBlank {
                        com.aiguruapp.student.chat.BlackboardGenerator.smartAssignTts(frame.frameType).first
                    }
                    if (engine == "android") null
                    else aiTtsEngine.buildKey(frame.speech, engine)
                }
            }
        }.distinct()

        // Show saving spinner on button
        saveSessionBtn.isEnabled = false
        saveSessionBtn.text = "⏳ Saving…"

        com.aiguruapp.student.firestore.FirestoreManager.saveBbSession(
            userId        = uid,
            subject       = subject,
            chapter       = chapter,
            sessionId     = sessionId,
            messageId     = msgId,
            conversationId = convId,
            topic         = topic,
            stepCount     = steps.size,
            ttsKeys       = ttsKeys,
            stepsJson     = com.aiguruapp.student.chat.BlackboardGenerator.serializeSteps(steps),
            onSuccess     = {
                sessionAlreadySaved = true
                saveSessionBtn.isEnabled = true
                saveSessionBtn.text = "✓ Saved"
                saveSessionBtn.setTextColor(android.graphics.Color.parseColor("#A0FFD0"))
                android.widget.Toast.makeText(this, "Session saved!", android.widget.Toast.LENGTH_SHORT).show()
            },
            onFailure     = {
                saveSessionBtn.isEnabled = true
                saveSessionBtn.text = "💾 Save"
                android.widget.Toast.makeText(this, "Save failed — try again", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── Load lesson from teacher-assigned global bb_cache (no LLM) ─────────────

    /**
     * Loads a pre-generated lesson from [bb_cache/{bbCacheId}].
     * Called when [EXTRA_BB_CACHE_ID] is set — no quota deduction, no LLM cost.
     */
    private fun loadFromGlobalCache(bbCacheId: String, userId: String?) {
        loadingText.text = "Loading lesson…"
        lifecycleScope.launch(Dispatchers.IO) {
            com.aiguruapp.student.chat.BlackboardGenerator.loadFromGlobalCache(
                bbCacheId            = bbCacheId,
                preferredLanguageTag = preferredLanguageTag,
                onSuccess = { generated ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        steps = generated
                        computedFontSp = computeFontSize(steps)
                        loadingGroup.visibility = View.GONE
                        contentGroup.visibility = View.VISIBLE
                        // Show inline ask bar now that content is ready
                        findViewById<android.widget.LinearLayout>(R.id.bbAskBar)?.visibility = View.VISIBLE
                        // Teachers don't need save/publish after loading a cached lesson
                        saveSessionBtn.visibility = View.GONE
                        publishLessonBtn.visibility = View.GONE
                        buildDots()
                        setupBoard()
                        preloadUpcoming(0, 0, count = 3)
                        showFrame(0, 0)
                        // Record lesson start time — actual read tracking happens step-by-step in showFrame()
                        if (!isTeacherMode && intent.hasExtra(EXTRA_TASK_ID)) {
                            bbStartTimeMs = System.currentTimeMillis()
                        }
                    }
                },
                onError = { err ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        loadingText.text = "Couldn't load lesson. Please try again."
                        android.util.Log.e("Blackboard", "Global cache load error: $err")
                    }
                }
            )
        }
    }

    // ── Publish current lesson to global bb_cache (teacher only) ──────────────

    /**
     * Loads a previously saved BB session directly from the user's Firestore flat collection.
     * Reads the [steps_json] field stored when the session was saved — no LLM call.
     */
    private fun loadFromSavedSession(sessionId: String, userId: String) {
        loadingText.text = "Loading saved session…"
        lifecycleScope.launch(Dispatchers.IO) {
            com.aiguruapp.student.chat.BlackboardGenerator.loadFromSavedSession(
                userId               = userId,
                sessionId            = sessionId,
                preferredLanguageTag = preferredLanguageTag,
                onSuccess = { generated ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        steps = generated
                        computedFontSp = computeFontSize(steps)
                        loadingGroup.visibility = View.GONE
                        contentGroup.visibility = View.VISIBLE
                        // Show inline ask bar — user can still ask follow-up questions
                        findViewById<android.widget.LinearLayout>(R.id.bbAskBar)?.visibility = View.VISIBLE
                        // Session is already saved; hide the save button, no publish needed
                        saveSessionBtn.visibility = View.GONE
                        publishLessonBtn.visibility = View.GONE
                        buildDots()
                        setupBoard()
                        preloadUpcoming(0, 0, count = 3)
                        showFrame(0, 0)
                    }
                },
                onError = { err ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        loadingText.text = err.ifBlank { "Couldn't load saved session." }
                        android.util.Log.e("Blackboard", "Saved session load error: $err")
                    }
                }
            )
        }
    }

    // ── Publish current lesson to global bb_cache (teacher only) ──────────────

    /**
     * Publishes the just-generated [steps] to [bb_cache/{id}] so this lesson can be
     * assigned to students via tasks.  Only shown when [isTeacherMode] is true.
     */
    private fun publishCurrentLesson() {
        if (steps.isEmpty()) return
        val uid = intent.getStringExtra(EXTRA_USER_ID) ?: run {
            android.widget.Toast.makeText(this, "Sign in to publish", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "General"
        val chapter = intent.getStringExtra(EXTRA_CHAPTER) ?: "General"
        val topic   = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val preview = steps.firstOrNull()?.frames?.firstOrNull()?.text?.take(120) ?: topic
        val schoolId = com.aiguruapp.student.utils.SessionManager.getSchoolId(this)

        // Already published — show the id so it can be assigned
        if (publishedBbCacheId.isNotBlank()) {
            android.widget.Toast.makeText(this, "Lesson ID: $publishedBbCacheId", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        publishLessonBtn.isEnabled = false
        publishLessonBtn.text = "⏳ Publishing…"

        val stepsJson = com.aiguruapp.student.chat.BlackboardGenerator.serializeSteps(steps)
        com.aiguruapp.student.firestore.FirestoreManager.publishBbLesson(
            teacherId    = uid,
            schoolId     = schoolId,
            subject      = subject,
            chapter      = chapter,
            topic        = topic,
            preview      = preview,
            stepsJson    = stepsJson,
            languageTag  = preferredLanguageTag,
            stepCount    = steps.size,
            existingId   = "",
            onSuccess    = { bbCacheId ->
                publishedBbCacheId = bbCacheId
                publishLessonBtn.isEnabled = true
                publishLessonBtn.text = "✓ Published"
                publishLessonBtn.setTextColor(android.graphics.Color.parseColor("#A0FFD0"))
                android.widget.Toast.makeText(this, "Lesson published! ID: $bbCacheId", android.widget.Toast.LENGTH_LONG).show()
            },
            onFailure    = { err ->
                publishLessonBtn.isEnabled = true
                publishLessonBtn.text = "📤 Publish as Lesson"
                android.widget.Toast.makeText(this, "Publish failed — try again", android.widget.Toast.LENGTH_SHORT).show()
                android.util.Log.e("Blackboard", "Publish error: $err")
            }
        )
    }

    /** Refresh the session quota chip in the top bar. */
    private fun updateBbQuotaChip(userId: String?) {
        val limits = AdminConfigRepository.resolveEffectiveLimits(
            cachedMetadata.planId, cachedMetadata.planLimits
        )
        val left = BlackboardQuotaValidator.sessionsLeft(cachedMetadata, limits).coerceAtLeast(0)
        bbQuotaChip.visibility = View.VISIBLE
        bbQuotaChip.text = when {
            left == 0 -> "0 sessions left"
            left == 1 -> "1 session left"
            else      -> "$left sessions left"
        }
        bbQuotaChip.setBackgroundColor(
            android.graphics.Color.parseColor(if (left <= 1) "#BF360C" else "#5C5BD4")
        )
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

        // GestureDetector on ScrollView so single-taps open the ask sheet
        // while scroll gestures still work normally.
        val gestureDetector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    showAskBottomSheet()
                    return true
                }
            }
        )
        stepsScrollView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
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
        updateStepNameStrip(stepIdx)
        trackTaskBbProgress(stepIdx)

        // Trigger next chunk when 2 steps from the end
        if (!isGeneratingNextChunk && steps.size < totalStepsTarget && stepIdx >= steps.size - 2) {
            triggerNextChunk()
        }

        // ── Smooth seekbar animation over this frame's duration ───────────
        if (!isPaused && steps.isNotEmpty()) {
            val totalFrames = steps.sumOf { it.frames.size }
            if (totalFrames > 1) {
                val flatNow  = steps.take(stepIdx).sumOf { it.frames.size } + frameIdx
                val flatNext = (flatNow + 1).coerceAtMost(totalFrames - 1)
                if (flatNow < flatNext) {
                    seekBarAnimator?.cancel()
                    seekBarAnimator = ValueAnimator.ofInt(flatNow, flatNext).apply {
                        duration = frame.durationMs
                        interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener { anim ->
                            progressSeekBar.progress = anim.animatedValue as Int
                        }
                        start()
                    }
                }
            }
        }

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

            // Only fetch an image when:
            //   score >= 0.7  → show inline (diagram/process/structure — clearly visual)
            //   score 0.35–0.69 → show small tap-to-view reference button
            //   score < 0.35  → skip entirely (pure math/text — no image needed)
            val imageScore = step.imageConfidenceScore
            val imageQuery = step.image_description.trim()
            if (imageScore >= 0.35f && imageQuery.isNotBlank() && imageQuery != "null") {
                fetchAndShowStepImage(imageQuery, imageScore, imagePlaceholder)
            }
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
        // Frame-type specific board text colour
        when (frame.frameType) {
            "memory"  -> contentText.setTextColor(Color.parseColor("#FFD700"))  // gold
            "summary" -> contentText.setTextColor(Color.parseColor("#A8D8A8"))  // mint
        }
        board.addView(contentText)

        // ── Diagram frame: animated SVG drawing (HTML built server-side) ─────
        if (frame.frameType == "diagram" && frame.svgHtml.isNotBlank()) {
            // Set caption label above the diagram
            blackboardMarkwon.setMarkdown(contentText, frame.text.ifBlank { " " })

            // Server already built the full animated HTML — just load it
            val diagramWebView = android.webkit.WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                // Allow mixed content (needed for inline data URIs inside HTML)
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Disable safe-browsing delays for local HTML content
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Hardware acceleration is essential for requestAnimationFrame smoothness
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (280 * dp).toInt()
                ).apply { topMargin = (8 * dp).toInt() }
            }
            board.addView(diagramWebView)
            // Use file:///android_asset/ as base URL so the WebView has a real origin
            // (null base URL → about:blank origin which can block requestAnimationFrame)
            diagramWebView.loadDataWithBaseURL(
                "file:///android_asset/", frame.svgHtml, "text/html", "UTF-8", null
            )

            stepsScrollView.post { stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }
            if (!isPaused && frame.speech.isNotBlank()) {
                contentText.postDelayed({ speakFrame(stepIdx, frameIdx) }, 400)
            }
            return
        }

        // Quiz frame: hidden reveal button + answer view (shown after TTS completes)
        quizRevealBtn = null
        if (frame.frameType == "quiz" && frame.quizAnswer.isNotBlank()) {
            val answerView = TextView(this).apply {
                text = buildFrameText("\u2705  ${frame.quizAnswer}", emptyList())
                textSize = computedFontSp
                gravity = Gravity.START
                setTextColor(Color.parseColor("#90EE90"))
                setLineSpacing(0f, 1.6f)
                typeface = caveatFont
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (12 * dp).toInt() }
            }
            val revealBtn = TextView(this).apply {
                text = "\uD83C\uDFAF  Tap to Reveal Answer"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1A1A0A"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 24 * dp
                    setColor(Color.parseColor("#F5E3A0"))
                }
                setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (20 * dp).toInt()
                }
                alpha = 0f
                setOnClickListener {
                    answerView.alpha = 0f
                    answerView.visibility = View.VISIBLE
                    answerView.animate().alpha(1f).setDuration(700).start()
                    this.animate().alpha(0f).setDuration(300)
                        .withEndAction { this.visibility = View.GONE }.start()
                    stepsScrollView.postDelayed(
                        { stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 300
                    )
                    tts.speak(frame.quizAnswer, object : TTSCallback {
                        override fun onStart() {}
                        override fun onComplete() {
                            stepsScrollView.postDelayed({ advanceFrame() }, 600)
                        }
                        override fun onError(error: String) {
                            stepsScrollView.postDelayed({ advanceFrame() }, 600)
                        }
                    })
                }
            }
            board.addView(revealBtn)
            board.addView(answerView)
            quizRevealBtn = revealBtn
        }

        val baseSsb = buildFrameText(sanitizeFrameText(frame.text), frame.highlight)
        val textLen = baseSsb.length

        if (textLen == 0) {
            if (frame.text.contains('$')) {
                val kalam = ResourcesCompat.getFont(this, R.font.kalam)
                blackboardMarkwon.setMarkdown(contentText, preprocessLatex(sanitizeFrameText(frame.text)))
                contentText.typeface = kalam
                contentText.setLineSpacing(0f, 1.6f)
            } else {
                contentText.text = baseSsb
            }
            if (!isPaused && frame.speech.isNotBlank()) {
                contentText.postDelayed({ speakFrame(stepIdx, frameIdx) }, 200)
            }
            return
        }

        // If the frame contains LaTeX, skip the typewriter animation and render directly
        if (frame.text.contains('$')) {
            val kalam = ResourcesCompat.getFont(this, R.font.kalam)
            blackboardMarkwon.setMarkdown(contentText, preprocessLatex(sanitizeFrameText(frame.text)))
            contentText.typeface = kalam
            contentText.setLineSpacing(0f, 1.6f)
            stepsScrollView.post { stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }
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
                    // After typewriter finishes, re-render with Markwon so LaTeX
                    // symbols display properly (animation used plain SpannableString)
                    if (frame.text.contains('$')) {
                        val kalam = ResourcesCompat.getFont(this@BlackboardActivity, R.font.kalam)
                        blackboardMarkwon.setMarkdown(contentText, preprocessLatex(frame.text))
                        contentText.typeface = kalam
                        contentText.setLineSpacing(0f, 1.6f)
                    }
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
        if (query=="null") return
        lifecycleScope.launch {
            // Server now stores the direct image URL in image_description.
            // If it already starts with https:// use it directly — no re-query needed.
            // Legacy fallback: if it's a title/description, search Wikimedia for it.
            val url: String? = if (query.startsWith("https://", ignoreCase = true)) {
                query
            } else {
                WikimediaUtils.firstImageUrl(query)
            }
            if (url == null) return@launch
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

        // Determine effective TTS engine for this frame:
        //   - First frame of the whole lesson → android (zero-delay guaranteed start)
        //   - useAiTts master toggle OFF       → android (user preference)
        //   - otherwise                        → use the per-frame engine assigned by the LLM
        val effectiveEngine: String = when {
            stepIdx == 0 && frameIdx == 0 -> "android"
            !useAiTts                     -> "android"
            else -> frame.ttsEngine.ifBlank {
                BlackboardGenerator.smartAssignTts(frame.frameType).first
            }
        }
        android.util.Log.d("BB_SPEAK",
            "→ speakFrame: step=$stepIdx frame=$frameIdx engine=$effectiveEngine role=${frame.voiceRole} speech_len=${frame.speech.length}")

        // Interactive quiz frames: show popup immediately, then read question inside it
        if (frame.frameType in setOf("quiz_mcq", "quiz_typed", "quiz_voice", "quiz_fill", "quiz_order")) {
            showInteractiveQuiz(frame, stepIdx, frameIdx)
            return
        }
        // Preload the next 2 frames' audio in background while this frame plays
        preloadUpcoming(stepIdx, frameIdx + 1, count = 2)

        if (effectiveEngine != "android" && frame.speech.isNotBlank()) {
            android.util.Log.d("BB_SPEAK", "  ↳ AI TTS mode: engine=$effectiveEngine")
            // Check AI TTS quota before speaking
            val limits = com.aiguruapp.student.config.AdminConfigRepository.resolveEffectiveLimits(
                cachedMetadata.planId, cachedMetadata.planLimits
            )
            val quotaCheck = AiVoiceQuotaValidator.checkQuota(
                cachedMetadata, limits, frame.speech.length
            )
            if (!quotaCheck.allowed) {
                android.util.Log.w("BB_SPEAK", "  ✗ Quota check FAILED: ${quotaCheck.upgradeMessage}")
                android.widget.Toast.makeText(this, quotaCheck.upgradeMessage, android.widget.Toast.LENGTH_LONG).show()
                tts.setLocale(Locale.forLanguageTag(step.languageTag))
                tts.speak(stripLatexForSpeech(frame.speech), makeTtsCallback(stepIdx, frameIdx, stripLatexForSpeech(frame.speech)))
                return
            }

            ensureTtsKeysLoaded()
            aiTtsEngine.languageCode = step.languageTag
            android.util.Log.d("BB_SPEAK", "  ✓ Calling aiTtsEngine.play() lang=${step.languageTag} engine=$effectiveEngine")
            aiTtsEngine.play(
                text      = stripLatexForSpeech(frame.speech),
                langTag   = step.languageTag,
                callback  = makeTtsCallback(stepIdx, frameIdx, stripLatexForSpeech(frame.speech)),
                ttsEngine = effectiveEngine,
                onUsedAi  = { wasAi ->
                    android.util.Log.d("BB_SPEAK", "  ↳ onUsedAi=$wasAi")
                    if (wasAi) {
                        val uid = intent.getStringExtra(EXTRA_USER_ID) ?: ""
                        com.aiguruapp.student.config.PlanEnforcer.recordAiTtsUsed(uid, frame.speech.length)
                    }
                }
            )
        } else {
            tts.setLocale(Locale.forLanguageTag(step.languageTag))
            tts.speak(stripLatexForSpeech(frame.speech), makeTtsCallback(stepIdx, frameIdx, stripLatexForSpeech(frame.speech)))
        }
    }

    /**
     * Strips LaTeX delimiters (`$...$` and `$$...$$`) from [text] so TTS reads
     * the inner expression rather than saying "dollar".
     */
    private fun stripLatexForSpeech(text: String): String {
        var s = text
        // $$...$$ first (must come before single-$ to avoid mis-stripping)
        s = s.replace(Regex("""\$\$([\s\S]+?)\$\$""")) { it.groupValues[1].trim() }
        // $...$
        s = s.replace(Regex("""\$([^\$\n]+?)\$""")) { it.groupValues[1].trim() }
        // Any remaining stray $
        s = s.replace("$", "")
        return s
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
            else -> showCompletionCard()
        }
    }

    private fun showCompletionCard() {
        if (bbCompletionCard.visibility == View.VISIBLE) return
        val subtitle = bbCompletionCard.findViewById<TextView?>(R.id.bbCompletionSubtitle)
        subtitle?.text = "Great job! You covered ${steps.size} step${if (steps.size == 1) "" else "s"}."
        bbCompletionCard.alpha = 0f
        bbCompletionCard.visibility = View.VISIBLE
        bbCompletionCard.animate().alpha(1f).setDuration(400).start()
        // Pulse the save button to nudge the user
        val saveBtn = bbCompletionCard.findViewById<TextView?>(R.id.completionSaveBtn)
        saveBtn?.animate()?.scaleX(1.06f)?.scaleY(1.06f)?.setDuration(300)
            ?.withEndAction { saveBtn.animate().scaleX(1f).scaleY(1f).setDuration(300).start() }?.start()
    }

    private fun nextStep() = advanceFrame()

    private fun prevStep() {
        aiTtsEngine.stop()
        typeAnimator?.cancel()
        handWriter.visibility = View.INVISIBLE
        when {
            currentFrameIdx > 0 -> {
                rebuildBoardUpTo(currentStepIdx, currentFrameIdx - 1)
            }
            currentStepIdx > 0 -> {
                val prevFrames = steps[currentStepIdx - 1].frames
                rebuildBoardUpTo(currentStepIdx - 1, prevFrames.size - 1)
            }
            else -> restartLesson()
        }
    }

    /** Restarts the lesson from Step 1, Frame 1 — wired to the ↺ reload button. */
    private fun restartLesson() {
        seekBarAnimator?.cancel()
        aiTtsEngine.stop()
        typeAnimator?.cancel()
        handWriter.visibility = View.INVISIBLE
        isPaused = false
        pauseBtn.text = "⏸"
        val board = boardLayout ?: return
        board.removeAllViews()
        buildDots()
        setupBoard()
        preloadUpcoming(0, 0, count = 3)
        showFrame(0, 0)
        stepsScrollView.smoothScrollTo(0, 0)
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
                val frameColor = when (frame.frameType) {
                    "memory"  -> Color.parseColor("#FFD700")
                    "summary" -> Color.parseColor("#A8D8A8")
                    else      -> Color.parseColor("#F0EDD0")
                }
                val contentText = TextView(this).apply {
                    text = buildFrameText(sanitizeFrameText(frame.text), frame.highlight)
                    textSize = computedFontSp
                    gravity = Gravity.START
                    setTextColor(frameColor)
                    setLineSpacing(0f, 1.6f)
                    typeface = caveatFont
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (16 * dp).toInt() }
                }
                board.addView(contentText)
                // For quiz frames in history: show the answer directly (already revealed)
                if (frame.frameType == "quiz" && frame.quizAnswer.isNotBlank()) {
                    val answerText = TextView(this).apply {
                        text = buildFrameText("\u2705  ${frame.quizAnswer}", emptyList())
                        textSize = computedFontSp
                        gravity = Gravity.START
                        setTextColor(Color.parseColor("#90EE90"))
                        setLineSpacing(0f, 1.6f)
                        typeface = caveatFont
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (8 * dp).toInt() }
                    }
                    board.addView(answerText)
                }
            }
        }

        // Finally run `showFrame` for the target frame so it animates in and speaks
        showFrame(targetStepIdx, targetFrameIdx)
    }

    private fun reSpeakCurrent() {
        aiTtsEngine.stop()
        if (!isPaused) speakFrame(currentStepIdx, currentFrameIdx)
    }

    private fun togglePause() {
        isPaused = !isPaused
        pauseBtn.text = if (isPaused) "▶" else "⏸"
        if (isPaused) {
            seekBarAnimator?.cancel()
            aiTtsEngine.stop()
        } else {
            speakFrame(currentStepIdx, currentFrameIdx)
        }
    }

    private fun updateCounterAndDots() {
        stepCounter.text = "Step ${currentStepIdx + 1} of ${steps.size}"
        updateDots(currentStepIdx)
        updateProgressSeekBar()
    }

    /** Syncs the progress strip to the current step/frame. */
    private fun updateProgressSeekBar() {
        if (steps.isEmpty()) return
        val totalFrames = steps.sumOf { it.frames.size }
        if (totalFrames <= 1) {
            progressSeekBar.max = 1
            progressSeekBar.progress = 0
        } else {
            val flat = steps.take(currentStepIdx).sumOf { it.frames.size } + currentFrameIdx
            progressSeekBar.max = totalFrames - 1
            progressSeekBar.progress = flat
        }
        // Prev/Next dimming
        val atStart = currentStepIdx == 0 && currentFrameIdx == 0
        val atEnd   = currentStepIdx == steps.size - 1
        prevBtn.alpha = if (atStart) 0.30f else 1f
        nextBtn.alpha = if (atEnd)   0.30f else 1f
        // "Almost done" hint
        val nearEnd = steps.isNotEmpty() && currentStepIdx >= steps.size - 2 && !atStart
        bbProgressHintTv.visibility = if (nearEnd) View.VISIBLE else View.GONE
        if (nearEnd) bbProgressHintTv.text =
            if (currentStepIdx == steps.size - 1) "🎓 Replay?" else "🔥 Almost done!"
    }

    /**
     * Called on each forward step-navigation to incrementally save BB read progress for
     * task homework. Only fires when the student moves to a step they haven't reached yet,
     * so back-navigation doesn't reset progress. Auto-marks bb_completed when last step reached.
     */
    private fun trackTaskBbProgress(stepIdx: Int) {
        if (isTeacherMode) return
        val tid = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val uid = intent.getStringExtra(EXTRA_USER_ID).orEmpty()
        if (tid.isBlank() || uid.isBlank()) return
        if (stepIdx <= maxStepReached) return   // only track forward navigation
        maxStepReached = stepIdx
        val totalSteps  = maxOf(if (steps.size >= totalStepsTarget) totalStepsTarget else steps.size, 1)
        val durationMs  = if (bbStartTimeMs > 0L) System.currentTimeMillis() - bbStartTimeMs else 0L
        val isCompleted = stepIdx >= totalSteps - 1
        com.aiguruapp.student.firestore.FirestoreManager.saveTaskBbProgress(
            userId      = uid,
            taskId      = tid,
            stepsViewed = stepIdx + 1,
            totalSteps  = totalSteps,
            durationMs  = durationMs,
            isCompleted = isCompleted,
            studentName = com.aiguruapp.student.utils.SessionManager.getStudentName(this)
        )
    }

    // ── Frame text rendering ──────────────────────────────────────────────────

    /** Parse markdown then overlay highlight spans for emphasized words/digits. */
    // ── LaTeX rendering ───────────────────────────────────────────────────────

    /**
     * Markwon with JLatexMathPlugin, configured for the dark blackboard theme.
     * Used after the typewriter animation ends on frames that contain LaTeX.
     * Lazy so it is created after [computedFontSp] is set.
     */
    private val blackboardMarkwon: Markwon by lazy {
        val textSizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, computedFontSp,
            resources.displayMetrics
        )
        Markwon.builder(this)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(textSizePx) { builder ->
                builder
                    .inlinesEnabled(true)
                    .theme()
                    .textColor(android.graphics.Color.parseColor("#F0EDD0"))
            })
            .build()
    }

    /** Converts legacy ^{} / _{} notation outside LaTeX blocks to proper $...$ inline LaTeX. */
    private fun preprocessLatex(text: String): String {
        val tokenPattern = Regex("""\$\$[\s\S]*?\$\$|\$[^\$\n]+?\$""")
        data class Token(val value: String, val isLatex: Boolean)
        val tokens = mutableListOf<Token>()
        var last = 0
        for (m in tokenPattern.findAll(text)) {
            if (m.range.first > last) tokens.add(Token(text.substring(last, m.range.first), false))
            tokens.add(Token(m.value, true))
            last = m.range.last + 1
        }
        if (last < text.length) tokens.add(Token(text.substring(last), false))
        return tokens.joinToString("") { (content, isLatex) ->
            if (isLatex) content
            else content
                .replace(Regex("""([A-Za-z0-9])\^\{([^}]+)\}""")) { m ->
                    "\$${m.groupValues[1]}^{${m.groupValues[2]}}\$"
                }
                .replace(Regex("""([A-Za-z])_\{([^}]+)\}""")) { m ->
                    "\$${m.groupValues[1]}_{${m.groupValues[2]}}\$"
                }
        }
    }

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
    /**
     * Guards against raw JSON schema leaking onto the board.
     * If [text] looks like a bare JSON object or array (e.g., the LLM accidentally put
     * the frame schema in the text field), return empty string instead.
     */
    private fun sanitizeFrameText(text: String): String {
        val t = text.trim()
        return if ((t.startsWith("{") || t.startsWith("[")) &&
                   (t.endsWith("}") || t.endsWith("]"))) "" else text
    }

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

    // ── Interactive quiz ──────────────────────────────────────────────────────

    private fun showInteractiveQuiz(
        frame: BlackboardGenerator.BlackboardFrame,
        stepIdx: Int,
        frameIdx: Int
    ) {
        isPaused = true
        val serverUrl = AdminConfigRepository.effectiveServerUrl()
        var startTimerFn: (() -> Unit)? = null
        BbInteractivePopup.show(
            activity    = this,
            frame       = frame,
            serverUrl   = serverUrl,
            languageTag = preferredLanguageTag,
            onResult    = { result ->
                quizTotal++
                val bbUserId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
                val bbSubject = intent.getStringExtra(EXTRA_SUBJECT) ?: "General"
                val bbChapter = intent.getStringExtra(EXTRA_CHAPTER) ?: "General"
                if (bbUserId.isNotBlank()) {
                    com.aiguruapp.student.firestore.StudentStatsManager.recordQuiz(
                        userId   = bbUserId,
                        subject  = bbSubject,
                        chapter  = bbChapter,
                        answered = 1,
                        correct  = if (result.correct) 1 else 0,
                        context  = this@BlackboardActivity
                    )
                }
                if (result.correct) {
                    quizCorrect++
                    currentStreak++
                    if (currentStreak >= 3) showStreakOverlay(currentStreak)
                } else {
                    currentStreak = 0
                    // Bookmark the frame automatically on wrong answer
                    val stepTitle = steps.getOrNull(stepIdx)?.title ?: "Step ${stepIdx + 1}"
                    val key = Triple(stepIdx, frameIdx, stepTitle)
                    if (key !in bookmarkedFrames) bookmarkedFrames.add(key)
                    // Adaptive remediation: insert an extra explanation frame
                    triggerAdaptiveRemediation(frame, stepIdx, frameIdx)
                }
                isPaused = false
                advanceFrame()
            },
            onTimerStart = { fn -> startTimerFn = fn }
        )
        // After popup settles, read the quiz question aloud so the student hears it.
        // Start the countdown timer only AFTER TTS finishes reading.
        if (frame.speech.isNotBlank()) {
            stepsScrollView.postDelayed({
                tts.setLocale(Locale.forLanguageTag(preferredLanguageTag))
                tts.speak(frame.speech, object : TTSCallback {
                    override fun onStart() {}
                    override fun onComplete() { startTimerFn?.invoke() }
                    override fun onError(error: String) { startTimerFn?.invoke() }
                })
            }, 400)
        } else {
            // No speech — start timer immediately
            stepsScrollView.post { startTimerFn?.invoke() }
        }
    }

    /** Brief full-screen streak overlay that fades in and scales out. */
    private fun showStreakOverlay(streak: Int) {
        val dp = resources.displayMetrics.density
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content)

        val overlay = TextView(this).apply {
            text = "🔥 $streak in a row!"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFD700"))
            typeface = ResourcesCompat.getFont(this@BlackboardActivity, R.font.kalam)
            setBackgroundColor(Color.parseColor("#CC1A2B1A"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlay)

        val fadeIn  = AlphaAnimation(0f, 1f).apply { duration = 300 }
        val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 600; startOffset = 900 }
        val scale   = ScaleAnimation(0.8f, 1.1f, 0.8f, 1.1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f).apply { duration = 300 }
        val anim = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(scale)
        }
        overlay.startAnimation(anim)
        overlay.postDelayed({
            overlay.startAnimation(fadeOut)
            overlay.postDelayed({ root.removeView(overlay) }, 700)
        }, 1000)
    }

    /**
     * On a wrong answer, sends a mini LLM request to generate one extra
     * "re-explanation" concept frame and inserts it as the next frame.
     */
    private fun triggerAdaptiveRemediation(
        frame: BlackboardGenerator.BlackboardFrame,
        stepIdx: Int,
        frameIdx: Int
    ) {
        val topic = frame.quizModelAnswer.ifBlank { frame.text }.take(200)
        val serverUrl = AdminConfigRepository.effectiveServerUrl()
        val cfg = AdminConfigRepository.config

        lifecycleScope.launch(Dispatchers.IO) {
            val client = com.aiguruapp.student.chat.ServerProxyClient(
                serverUrl = serverUrl,
                modelName = "",
                apiKey    = cfg.serverApiKey
            )
            val remedPrompt = "Give a single clear 1-sentence re-explanation of: $topic"
            val buffer = StringBuilder()
            val latch  = java.util.concurrent.CountDownLatch(1)
            client.streamChat(
                question     = remedPrompt,
                pageId       = "bb_remediation",
                mode         = "blackboard",
                languageTag  = preferredLanguageTag,
                studentLevel = 5,
                history      = emptyList(),
                onToken      = { t -> buffer.append(t) },
                onDone       = { _, _, _ -> latch.countDown() },
                onError      = { latch.countDown() }
            )
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
            val explanation = buffer.toString().trim().take(300)
            if (explanation.isBlank()) return@launch
            val remedFrame = BlackboardGenerator.BlackboardFrame(
                text      = "💡 Quick recap: $explanation",
                speech    = explanation,
                frameType = "concept",
                durationMs = 3000
            )
            lifecycleScope.launch(Dispatchers.Main) {
                val step = steps.getOrNull(stepIdx) ?: return@launch
                val mutableFrames = step.frames.toMutableList()
                mutableFrames.add(frameIdx + 1, remedFrame)
                val mutableSteps = steps.toMutableList()
                mutableSteps[stepIdx] = step.copy(frames = mutableFrames)
                steps = mutableSteps
            }
        }
    }

    /** Show a summary score card at the end of the lesson when quizzes were played. */
    private fun showScoreCard() {
        val dp     = resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(this, R.font.kalam)
        val pct    = if (quizTotal > 0) (quizCorrect * 100) / quizTotal else 0
        val emoji  = when {
            pct >= 90 -> "🏆"
            pct >= 70 -> "🎉"
            pct >= 50 -> "👍"
            else      -> "📚"
        }
        val msg = when {
            pct >= 90 -> "Outstanding! You've mastered this topic."
            pct >= 70 -> "Great job! Review the ones you missed."
            pct >= 50 -> "Good effort! Practice a bit more."
            else      -> "Keep going — revisit the lesson to improve."
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A2B1A"))
            setPadding((24 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        }

        fun tv(text: String, sizeSp: Float, colorHex: String, bold: Boolean = false) =
            TextView(this).apply {
                this.text = text
                textSize  = sizeSp
                gravity   = Gravity.CENTER
                setTextColor(Color.parseColor(colorHex))
                typeface  = if (bold) android.graphics.Typeface.create(caveat, android.graphics.Typeface.BOLD) else caveat
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * dp).toInt() }
            }

        root.addView(tv(emoji,                           52f, "#F5E3A0"))
        root.addView(tv("Lesson Complete!",              22f, "#F5E3A0", bold = true))
        root.addView(tv("$quizCorrect / $quizTotal correct  ($pct%)", 18f, "#A8D8A8"))
        root.addView(tv(msg,                             14f, "#B0C8B0"))

        if (bookmarkedFrames.isNotEmpty()) {
            root.addView(tv("📌 ${bookmarkedFrames.size} frame${if (bookmarkedFrames.size > 1) "s" else ""} bookmarked for review",
                12f, "#FFD54F"))
        }

        val closeBtn = TextView(this).apply {
            text = "Done ✓"
            textSize = 16f
            gravity   = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1A0A"))
            typeface  = caveat
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(Color.parseColor("#F5E3A0"))
            }
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * dp).toInt() }
        }
        root.addView(closeBtn)

        val dialog = android.app.Dialog(this, R.style.Theme_BB_QuizDialog).apply {
            setContentView(root)
            setCancelable(true)
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun makeTtsCallback(stepIdx: Int, frameIdx: Int, subtitle: String = "") = object : TTSCallback {

        override fun onStart() {
            if (subtitle.isNotBlank()) runOnUiThread { showSubtitle(subtitle) }
        }

        override fun onComplete() {
            runOnUiThread { hideSubtitle() }
            if (!isPaused && currentStepIdx == stepIdx && currentFrameIdx == frameIdx) {
                val f = steps.getOrNull(stepIdx)?.frames?.getOrNull(frameIdx)
                when {
                    f?.frameType == "quiz" && f.quizAnswer.isNotBlank() -> {
                        // Legacy tap-to-reveal quiz
                        runOnUiThread {
                            quizRevealBtn?.animate()?.alpha(1f)?.setDuration(400)?.start()
                        }
                    }
                    f?.frameType in setOf("quiz_mcq", "quiz_typed", "quiz_voice", "quiz_fill", "quiz_order") && f != null -> {
                        // Safety fallback — normally handled by speakFrame() routing
                        runOnUiThread { showInteractiveQuiz(f, stepIdx, frameIdx) }
                    }
                    else -> {
                        val isLastFrame = stepIdx == steps.size - 1 &&
                            frameIdx == (steps.lastOrNull()?.frames?.size ?: 1) - 1
                        if (isLastFrame && quizTotal > 0) {
                            stepsScrollView.postDelayed({ showScoreCard() }, 600)
                        } else {
                            stepsScrollView.postDelayed({ advanceFrame() }, 300)
                        }
                    }
                }
            }
        }

        override fun onError(error: String) {
            runOnUiThread { hideSubtitle() }
            android.util.Log.w("Blackboard", "TTS: $error")
        }
    }

    private fun toggleAskBar() {
        val askBar = findViewById<android.widget.LinearLayout>(R.id.bbAskBar) ?: return
        bbAskBarExpanded = !bbAskBarExpanded
        if (bbAskBarExpanded) {
            askBar.translationY = askBar.height.toFloat()
            askBar.visibility = View.VISIBLE
            askBar.animate().translationY(0f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            bbChatFab.visibility = View.GONE
            bbAskInput.requestFocus()
        } else {
            askBar.animate().translationY(askBar.height.toFloat()).setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { askBar.visibility = View.GONE }
                .start()
            bbChatFab.visibility = View.VISIBLE
        }
    }

    private var subtitlePulse: android.animation.ObjectAnimator? = null

    private fun showSubtitle(text: String) {
        bbSubtitleTv.text = text
        if (bbSubtitleTv.visibility != View.VISIBLE) {
            bbSubtitleTv.alpha = 0f
            bbSubtitleTv.visibility = View.VISIBLE
            bbSubtitleTv.animate().alpha(1f).setDuration(220).withEndAction {
                startSubtitlePulse()
            }.start()
        } else {
            // Already visible — just update text and ensure pulse is running
            if (subtitlePulse?.isRunning != true) startSubtitlePulse()
        }
        // Auto-collapse ask bar while speaking
        val askBar = findViewById<android.widget.LinearLayout>(R.id.bbAskBar)
        if (askBar?.visibility == View.VISIBLE) {
            askBar.visibility = View.GONE
            bbAskBarExpanded = false
            bbChatFab.visibility = View.VISIBLE
        }
    }

    private fun startSubtitlePulse() {
        subtitlePulse?.cancel()
        subtitlePulse = android.animation.ObjectAnimator.ofFloat(bbSubtitleTv, "alpha", 1f, 0.65f).apply {
            duration = 700
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun hideSubtitle() {
        subtitlePulse?.cancel()
        subtitlePulse = null
        if (bbSubtitleTv.visibility == View.VISIBLE) {
            bbSubtitleTv.animate().alpha(0f).setDuration(300)
                .withEndAction { bbSubtitleTv.visibility = View.GONE }.start()
        }
    }

    // ── Inline quiz builders ──────────────────────────────────────────────────

    private fun buildInlineQuizCard(
        frame: BlackboardGenerator.BlackboardFrame,
        dp: Float,
        caveat: Typeface?
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * dp
                setColor(Color.parseColor("#121A12"))
                setStroke((1 * dp).toInt(), Color.parseColor("#306050"))
            }
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * dp).toInt() }
        }
        when (frame.frameType) {
            "quiz_mcq"             -> addMcqOptions(card, frame, dp, caveat)
            "quiz_typed", "quiz_voice" -> addTypedInput(card, frame, dp, caveat)
            "quiz_fill"            -> addFillBlanks(card, frame, dp, caveat)
            "quiz_order"           -> addOrderItems(card, frame, dp, caveat)
        }
        return card
    }

    private fun addMcqOptions(
        container: LinearLayout,
        frame: BlackboardGenerator.BlackboardFrame,
        dp: Float,
        caveat: Typeface?
    ) {
        var answered = false
        val optViews = mutableListOf<TextView>()
        frame.quizOptions.forEachIndexed { idx, option ->
            val label = listOf("A", "B", "C", "D").getOrElse(idx) { "${idx + 1}" }
            val optView = TextView(this).apply {
                text = "$label.  $option"
                textSize = computedFontSp - 4f
                typeface = caveat
                setTextColor(Color.parseColor("#F0EDD0"))
                gravity = Gravity.START
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * dp
                    setStroke((1 * dp).toInt(), Color.parseColor("#4050A0"))
                    setColor(Color.parseColor("#1E2050"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            container.addView(optView)
            optViews.add(optView)
            optView.setOnClickListener {
                if (answered) return@setOnClickListener
                answered = true
                val correct = idx == frame.quizCorrectIndex
                optViews.forEachIndexed { i, v ->
                    (v.background as? GradientDrawable)?.apply {
                        setStroke(0, Color.TRANSPARENT)
                        setColor(Color.parseColor(when {
                            i == frame.quizCorrectIndex -> "#1B5E20"
                            i == idx && !correct        -> "#B71C1C"
                            else                        -> "#1E2050"
                        }))
                    }
                }
                quizTotal++
                if (correct) { quizCorrect++; currentStreak++ } else { currentStreak = 0 }
                if (currentStreak >= 3) showStreakOverlay(currentStreak)
                val continueBtn = TextView(this).apply {
                    text = if (correct) "✅  Correct!  Continue →" else "❌  Wrong!  Continue →"
                    textSize = 15f
                    typeface = caveat
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#1A1A0A"))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 20 * dp
                        setColor(Color.parseColor(if (correct) "#4CAF50" else "#F44336"))
                    }
                    setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (16 * dp).toInt() }
                }
                container.addView(continueBtn)
                stepsScrollView.postDelayed({ stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 200)
                continueBtn.setOnClickListener {
                    if (!correct) {
                        val stepTitle = steps.getOrNull(currentStepIdx)?.title ?: "Step ${currentStepIdx + 1}"
                        val key = Triple(currentStepIdx, currentFrameIdx, stepTitle)
                        if (key !in bookmarkedFrames) bookmarkedFrames.add(key)
                    }
                    isPaused = false
                    advanceFrame()
                }
            }
        }
    }

    private fun addTypedInput(
        container: LinearLayout,
        frame: BlackboardGenerator.BlackboardFrame,
        dp: Float,
        caveat: Typeface?
    ) {
        val editText = EditText(this).apply {
            hint = "Type your answer here…"
            textSize = computedFontSp - 6f
            typeface = caveat
            setTextColor(Color.parseColor("#F0EDD0"))
            setHintTextColor(Color.parseColor("#665070"))
            setBackgroundColor(Color.parseColor("#0D1F0D"))
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            gravity = Gravity.TOP
            minLines = 2
            maxLines = 5
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(editText)

        val progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = (8 * dp).toInt() }
        }
        container.addView(progressBar)

        val feedbackTv = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            typeface = caveat
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
        }
        container.addView(feedbackTv)

        var graded = false
        val serverUrl = AdminConfigRepository.effectiveServerUrl()
        val checkBtn = TextView(this).apply {
            text = "Check Answer"
            textSize = 15f
            typeface = caveat
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1A0A"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(Color.parseColor("#F5E3A0"))
            }
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt() }
        }
        container.addView(checkBtn)

        checkBtn.setOnClickListener {
            if (graded) { isPaused = false; advanceFrame(); return@setOnClickListener }
            val answer = editText.text.toString().trim()
            if (answer.isBlank()) return@setOnClickListener
            editText.isEnabled = false
            progressBar.visibility = View.VISIBLE
            checkBtn.isEnabled = false
            checkBtn.alpha = 0.5f
            lifecycleScope.launch(Dispatchers.IO) {
                BbInteractivePopup.gradeAnswer(
                    serverUrl, frame.text, answer, frame.quizModelAnswer, frame.quizKeywords
                ) { result ->
                    runOnUiThread {
                        graded = true
                        progressBar.visibility = View.GONE
                        quizTotal++
                        if (result.correct) { quizCorrect++; currentStreak++ } else { currentStreak = 0 }
                        if (currentStreak >= 3) showStreakOverlay(currentStreak)
                        feedbackTv.text = "${if (result.correct) "✅" else "❌"}  ${result.feedback}"
                        feedbackTv.setTextColor(Color.parseColor(if (result.correct) "#69F0AE" else "#FF8A80"))
                        feedbackTv.visibility = View.VISIBLE
                        checkBtn.text = "Continue →"
                        checkBtn.isEnabled = true
                        checkBtn.alpha = 1f
                        stepsScrollView.postDelayed(
                            { stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 200
                        )
                    }
                }
            }
        }
    }

    private fun addFillBlanks(
        container: LinearLayout,
        frame: BlackboardGenerator.BlackboardFrame,
        dp: Float,
        caveat: Typeface?
    ) {
        val blanksCount = frame.fillBlanks.size.coerceAtLeast(1)
        var displayText = frame.text
        for (i in 1..blanksCount) displayText = displayText.replaceFirst("___", "($i)")
        container.addView(TextView(this).apply {
            text = "📝  $displayText"
            textSize = computedFontSp - 6f
            typeface = caveat
            setTextColor(Color.parseColor("#F0EDD0"))
            setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() }
        })
        val editTexts = mutableListOf<EditText>()
        for (i in 0 until blanksCount) {
            container.addView(TextView(this).apply {
                text = "Blank ${i + 1}:"
                textSize = 13f
                typeface = caveat
                setTextColor(Color.parseColor("#88905070"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * dp).toInt() }
            })
            val et = EditText(this).apply {
                hint = "Fill in blank ${i + 1}…"
                textSize = computedFontSp - 8f
                typeface = caveat
                setTextColor(Color.parseColor("#F0EDD0"))
                setHintTextColor(Color.parseColor("#665570"))
                setBackgroundColor(Color.parseColor("#0D1F0D"))
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * dp).toInt() }
            }
            container.addView(et)
            editTexts.add(et)
        }
        val feedbackTv = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            typeface = caveat
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
        }
        container.addView(feedbackTv)
        var checked = false
        val checkBtn = TextView(this).apply {
            text = "Check Answers"
            textSize = 15f
            typeface = caveat
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A1A0A"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * dp
                setColor(Color.parseColor("#F5E3A0"))
            }
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt() }
        }
        container.addView(checkBtn)
        checkBtn.setOnClickListener {
            if (checked) { isPaused = false; advanceFrame(); return@setOnClickListener }
            checked = true
            editTexts.forEach { it.isEnabled = false }
            var allCorrect = true
            val feedback = StringBuilder()
            frame.fillBlanks.forEachIndexed { i, expected ->
                val given = editTexts.getOrNull(i)?.text?.toString()?.trim() ?: ""
                val ok = given.equals(expected, ignoreCase = true) ||
                         given.lowercase().contains(expected.lowercase())
                if (!ok) { allCorrect = false; feedback.append("Blank ${i + 1}: expected \"$expected\"\n") }
            }
            quizTotal++
            if (allCorrect) { quizCorrect++; currentStreak++ } else { currentStreak = 0 }
            feedbackTv.text = if (allCorrect) "✅  All blanks correct!" else "❌  Not quite:\n${feedback.trimEnd()}"
            feedbackTv.setTextColor(Color.parseColor(if (allCorrect) "#69F0AE" else "#FF8A80"))
            feedbackTv.visibility = View.VISIBLE
            checkBtn.text = "Continue →"
            stepsScrollView.postDelayed({ stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 200)
        }
    }

    private fun addOrderItems(
        container: LinearLayout,
        frame: BlackboardGenerator.BlackboardFrame,
        dp: Float,
        caveat: Typeface?
    ) {
        container.addView(TextView(this).apply {
            text = "🔢  Tap the steps in the correct order:"
            textSize = computedFontSp - 6f
            typeface = caveat
            setTextColor(Color.parseColor("#F0EDD0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        })
        val stepTexts = frame.quizOptions
        val correctOrder = frame.quizCorrectOrder
        val selectedOrder = mutableListOf<Int>()
        val itemViews = mutableListOf<TextView>()
        stepTexts.forEachIndexed { idx, stepText ->
            val v = TextView(this).apply {
                text = stepText
                textSize = computedFontSp - 8f
                typeface = caveat
                setTextColor(Color.parseColor("#F0EDD0"))
                gravity = Gravity.START
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10 * dp
                    setStroke((1 * dp).toInt(), Color.parseColor("#4050A0"))
                    setColor(Color.parseColor("#1E2050"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            container.addView(v)
            itemViews.add(v)
            v.setOnClickListener {
                if (idx in selectedOrder) return@setOnClickListener
                selectedOrder.add(idx)
                val num = selectedOrder.size
                (v.background as? GradientDrawable)?.setColor(Color.parseColor("#1E3A50"))
                v.text = "$num. $stepText"
                if (selectedOrder.size == stepTexts.size) {
                    val isCorrect = correctOrder.size == stepTexts.size && selectedOrder == correctOrder
                    itemViews.forEachIndexed { i, btn ->
                        val tappedPos = selectedOrder.indexOf(i)
                        val expectedPos = if (correctOrder.size == stepTexts.size) correctOrder.indexOf(i) else i
                        (btn.background as? GradientDrawable)?.setColor(
                            Color.parseColor(if (tappedPos == expectedPos) "#1B5E20" else "#B71C1C")
                        )
                    }
                    quizTotal++
                    if (isCorrect) { quizCorrect++; currentStreak++ } else { currentStreak = 0 }
                    val resultTv = TextView(this).apply {
                        text = if (isCorrect) "✅  Perfect order!" else "❌  Not quite. Review the highlighted steps."
                        textSize = 14f
                        typeface = caveat
                        setTextColor(Color.parseColor(if (isCorrect) "#69F0AE" else "#FF8A80"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (12 * dp).toInt() }
                    }
                    container.addView(resultTv)
                    val contBtn = TextView(this).apply {
                        text = "Continue →"
                        textSize = 15f
                        typeface = caveat
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#1A1A0A"))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 20 * dp
                            setColor(Color.parseColor(if (isCorrect) "#4CAF50" else "#FF5722"))
                        }
                        setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (8 * dp).toInt() }
                    }
                    container.addView(contBtn)
                    contBtn.setOnClickListener { isPaused = false; advanceFrame() }
                    stepsScrollView.postDelayed(
                        { stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 200
                    )
                }
            }
        }
    }

    // ── BB ask-bar: camera / image / voice helpers ────────────────────────────

    /** Mirrors FullChatFragment.showImageSourceDialog() */
    private fun launchBbCamera() {
        AlertDialog.Builder(this)
            .setTitle("Add Image")
            .setItems(arrayOf("📷  Take Photo", "🖼️  Choose from Gallery")) { _, which ->
                when (which) {
                    0 -> openBbCamera()
                    1 -> bbGalleryLauncher.launch("image/*")
                }
            }.show()
    }

    /** Mirrors FullChatFragment.openCamera() exactly. */
    private fun openBbCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), 901
            )
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        bbCameraImageUri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )
        bbCameraImageUri?.let { bbCameraLauncher.launch(it) }
    }

    /**
     * Mirrors FullChatFragment.launchCrop() exactly — reads image dimensions for a smart
     * initial crop ratio, applies the same dark UCrop theme and options.
     */
    private fun launchBbCrop(sourceUri: Uri) {
        val imagesDir = java.io.File(filesDir, "bb_images").also { it.mkdirs() }
        val destFile  = java.io.File(imagesDir, "crop_${System.currentTimeMillis()}.jpg")

        var imgW = 0f; var imgH = 0f
        runCatching {
            val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(sourceUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            imgW = boundsOpts.outWidth.toFloat()
            imgH = boundsOpts.outHeight.toFloat()
        }
        val cropRatioX: Float
        val cropRatioY: Float
        if (imgW > 0f && imgH > 0f) {
            if (imgW >= imgH) { cropRatioX = 0.3f * imgW; cropRatioY = imgH }
            else              { cropRatioX = imgW;         cropRatioY = 0.3f * imgH }
        } else { cropRatioX = 1f; cropRatioY = 1f }

        val options = UCrop.Options().apply {
            setToolbarTitle("Crop Image")
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
                .withAspectRatio(cropRatioX, cropRatioY)
                .withMaxResultSize(1920, 1920)
            bbCropLauncher.launch(uCrop.getIntent(this))
        } catch (e: Exception) {
            android.util.Log.w("BB", "UCrop launch failed: ${e.message}")
            encodeBbImage(sourceUri)
        }
    }

    /**
     * Converts the cropped URI to Base64 (background thread) and shows the
     * thumbnail preview row in the ask bar.
     */
    private fun encodeBbImage(uri: Uri) {
        bbPendingImageUri = uri
        Thread {
            val b64 = bbMediaManager.uriToBase64(uri)
            runOnUiThread {
                if (b64 != null) {
                    bbPendingImageBase64 = b64
                    bbImgPreviewRow.visibility = View.VISIBLE
                    Glide.with(this).load(uri).centerCrop().into(bbImgPreviewThumb)
                }
            }
        }.start()
    }

    private fun clearBbImage() {
        bbPendingImageUri = null
        bbPendingImageBase64 = null
        bbImgPreviewRow.visibility = View.GONE
    }

    /**
     * Mirrors FullChatFragment.checkPermissionAndStartListening().
     * Checks RECORD_AUDIO permission then delegates to startBbVoiceInput().
     */
    private fun startBbVoice() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 902
            )
            return
        }
        startBbVoiceInput()
    }

    /** Mirrors FullChatFragment.startVoiceInput(). */
    private fun startBbVoiceInput() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            android.widget.Toast.makeText(
                this, "Voice recognition not available on this device", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        bbIsListening = true
        bbMicBtn.text = "⏹️"
        bbMicBtn.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E53935"))
        bbAskInput.hint = "Listening…"
        bbVoiceManager.startListening(object : VoiceRecognitionCallback {
            override fun onResults(text: String) {
                runOnUiThread {
                    resetBbVoiceButton()
                    if (text.isNotEmpty()) {
                        bbAskInput.setText(text)
                        bbAskInput.setSelection(text.length)
                    }
                }
            }
            override fun onPartialResults(text: String) {
                runOnUiThread { bbAskInput.setText(text); bbAskInput.setSelection(text.length) }
            }
            override fun onError(error: String) { runOnUiThread { resetBbVoiceButton() } }
            override fun onListeningStarted() {}
            override fun onListeningFinished() { runOnUiThread { resetBbVoiceButton() } }
        }, preferredLanguageTag)
    }

    /** Mirrors FullChatFragment.resetVoiceButton(). */
    private fun resetBbVoiceButton() {
        bbIsListening = false
        bbMicBtn.text = "🎤"
        bbMicBtn.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E1E38"))
        bbAskInput.hint = "Ask a question about this lesson…"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 902 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startBbVoiceInput()   // permission just granted
        } else if (requestCode == 901 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openBbCamera()        // camera permission just granted
        }
    }

    /**
     * Shows a compact bottom sheet when the user taps anywhere on the board.
     * Provides a quick-input field + camera + mic + full-screen buttons.
     */
    private fun showAskBottomSheet() {
        val dp = resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(this, R.font.kalam)

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.Theme_BB_QuizDialog
        )

        // ── Root container ────────────────────────────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(20*dp, 20*dp, 20*dp, 20*dp, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#12122A"))
            }
            setPadding((20*dp).toInt(), (6*dp).toInt(), (20*dp).toInt(), (28*dp).toInt())
        }

        // ── Drag handle ───────────────────────────────────────────────────────
        root.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16*dp).toInt() }
            addView(View(this@BlackboardActivity).apply {
                layoutParams = LinearLayout.LayoutParams((44*dp).toInt(), (4*dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 2*dp
                    setColor(Color.parseColor("#44FFFFFF"))
                }
            })
        })

        // ── Header row: icon + title ──────────────────────────────────────────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14*dp).toInt() }
        }
        headerRow.addView(TextView(this).apply {
            text = "🤔"; textSize = 22f; gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (36*dp).toInt(), (36*dp).toInt()
            ).apply { marginEnd = (10*dp).toInt() }
        })
        headerRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@BlackboardActivity).apply {
                text = "Ask a question"
                textSize = 17f; typeface = caveat
                setTextColor(Color.parseColor("#E8F0FF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(TextView(this@BlackboardActivity).apply {
                text = "Type, attach a photo, or use voice"
                textSize = 11f; typeface = caveat
                setTextColor(Color.parseColor("#667788"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        })
        root.addView(headerRow)

        // ── Image preview (shown only when photo attached) ────────────────────
        val sheetThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 10*dp
            }
            clipToOutline = true
        }
        val sheetImgRemoveBtn = TextView(this).apply {
            text = "✕  Remove photo"; textSize = 11f; typeface = caveat
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#FF8080"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt() }
        }
        val sheetImgCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            visibility = if (bbPendingImageBase64 != null) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 12*dp
                setColor(Color.parseColor("#1E1E38"))
                setStroke((1*dp).toInt(), Color.parseColor("#33AABBCC"))
            }
            setPadding((10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12*dp).toInt() }
            addView(sheetThumb.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (120*dp).toInt()
                )
            })
            addView(sheetImgRemoveBtn)
        }
        bbPendingImageUri?.let { Glide.with(this).load(it).centerCrop().into(sheetThumb) }
        root.addView(sheetImgCard)

        // ── Multiline text input ──────────────────────────────────────────────
        val sheetInput = EditText(this).apply {
            hint = "What would you like to know about this topic?"
            textSize = 15f; typeface = caveat
            setTextColor(Color.parseColor("#F0EDD0"))
            setHintTextColor(Color.parseColor("#44667788"))
            minLines = 3; maxLines = 6
            gravity = android.view.Gravity.TOP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 12*dp
                setColor(Color.parseColor("#1E1E38"))
                setStroke((1*dp).toInt(), Color.parseColor("#334466BB"))
            }
            setPadding((14*dp).toInt(), (12*dp).toInt(), (14*dp).toInt(), (12*dp).toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16*dp).toInt() }
        }
        // Pre-fill with main bar text if any
        val existing = bbAskInput.text.toString().trim()
        if (existing.isNotBlank()) { sheetInput.setText(existing); sheetInput.setSelection(existing.length) }
        root.addView(sheetInput)

        // ── Action tiles row: 📷 Attach  |  🎤 Voice  |  ⛶ Expand ────────────
        fun makeTile(emoji: String, label: String, bgHex: String, borderHex: String): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 12*dp
                    setColor(Color.parseColor(bgHex))
                    setStroke((1*dp).toInt(), Color.parseColor(borderHex))
                }
                setPadding((8*dp).toInt(), (12*dp).toInt(), (8*dp).toInt(), (10*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = (8*dp).toInt() }
                isClickable = true
                isFocusable = true
                addView(TextView(this@BlackboardActivity).apply {
                    text = emoji; textSize = 22f; gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4*dp).toInt() }
                })
                addView(TextView(this@BlackboardActivity).apply {
                    text = label; textSize = 11f; typeface = caveat
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#AABBCC"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            }

        val camTile  = makeTile("📷", "Photo", "#1A2040", "#3355AA")
        val micTile  = makeTile(if (bbIsListening) "🔴" else "🎤", "Voice", "#1A2030", "#334466")
        val fullTile = makeTile("⛶", "Expand", "#1A1A30", "#333355")
        // Remove right margin from last tile
        (fullTile.layoutParams as LinearLayout.LayoutParams).marginEnd = 0

        val tilesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16*dp).toInt() }
            addView(camTile); addView(micTile); addView(fullTile)
        }
        root.addView(tilesRow)

        // ── Send button ───────────────────────────────────────────────────────
        val sendBtn = TextView(this).apply {
            text = "  Send Question  ↑"
            textSize = 15f; typeface = caveat
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 24*dp
                setColor(Color.parseColor("#3C3CBD"))
            }
            setPadding((20*dp).toInt(), (14*dp).toInt(), (20*dp).toInt(), (14*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(sendBtn)

        sheet.setContentView(root)

        // ── Click handlers ────────────────────────────────────────────────────
        sheetImgRemoveBtn.setOnClickListener {
            clearBbImage()
            sheetImgCard.visibility = View.GONE
        }

        camTile.setOnClickListener {
            sheet.dismiss()
            launchBbCamera()
        }

        micTile.setOnClickListener {
            sheet.dismiss()
            if (bbIsListening) bbVoiceManager.stopListening() else startBbVoice()
        }

        fullTile.setOnClickListener {
            sheet.dismiss()
            val q = sheetInput.text.toString()
            if (q.isNotBlank()) bbAskInput.setText(q)
            bbAskInput.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(bbAskInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        sendBtn.setOnClickListener {
            val q = sheetInput.text.toString().trim()
            if (q.isNotBlank()) {
                sheet.dismiss()
                bbAskInput.setText("")
                sendBbChat(q)
            }
        }

        // Auto-show keyboard on the input
        sheet.setOnShowListener {
            sheetInput.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(sheetInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        sheet.show()
    }

    // ── Interactive board chat ────────────────────────────────────────────────

    private fun sendBbChat(question: String) {
        val dp = resources.displayMetrics.density
        val caveat = ResourcesCompat.getFont(this, R.font.kalam)
        val board = boardLayout ?: return

        // Capture and clear the pending image (if any) before building the card
        val capturedImageBase64 = bbPendingImageBase64.also { bbPendingImageBase64 = null }
        if (capturedImageBase64 != null) clearBbImage()

        val questionCard = buildChatCard("❓  $question", "#1A1A3A", "#B3C8FF", caveat, dp, capturedImageBase64)
        board.addView(questionCard)
        stepsScrollView.postDelayed({ stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 200)

        // ── Streaming answer text ───────────────────────────────────────────
        val responseInner = TextView(this).apply {
            text = "…"
            textSize = computedFontSp - 6f
            setTextColor(Color.parseColor("#A8D8A8"))
            typeface = caveat
            setLineSpacing(0f, 1.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Collapsible content area (answer text + BB button added after streaming)
        val contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
            addView(responseInner)
        }

        // Chevron toggle indicator
        val chevron = TextView(this).apply {
            text = "▼"
            textSize = 11f
            setTextColor(Color.parseColor("#7070A0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Collapsible header row: "💬 Answer" + chevron
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val label = TextView(this@BlackboardActivity).apply {
                text = "💬 Answer"
                textSize = computedFontSp - 7f
                setTextColor(Color.parseColor("#909090"))
                typeface = caveat
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)
            addView(chevron)
            setOnClickListener {
                if (contentArea.visibility == View.VISIBLE) {
                    contentArea.visibility = View.GONE
                    chevron.text = "▶"
                } else {
                    contentArea.visibility = View.VISIBLE
                    chevron.text = "▼"
                }
            }
        }

        val responseCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#0A1A0A"))
                setStroke((1 * dp).toInt(), Color.parseColor("#30508050"))
            }
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
            addView(headerRow)
            addView(contentArea)
        }
        board.addView(responseCard)
        stepsScrollView.postDelayed({ stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 300)

        val lessonTopic = steps.firstOrNull()?.title
            ?: intent.getStringExtra(EXTRA_MESSAGE)?.take(100)
            ?: "this lesson"
        val history = mutableListOf("system: Lesson topic: $lessonTopic").apply { addAll(bbChatHistory) }
        val userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
        val pageId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: "bb_chat"
        val client = ServerProxyClient(
            serverUrl = AdminConfigRepository.effectiveServerUrl(),
            modelName = "",
            userId    = userId
        )
        val responseText = StringBuilder()
        lifecycleScope.launch(Dispatchers.IO) {
            client.streamChat(
                question     = question,
                pageId       = pageId,
                mode         = "normal",
                languageTag  = preferredLanguageTag,
                studentLevel = 7,
                history      = history,
                imageBase64  = capturedImageBase64,
                onToken      = { token ->
                    responseText.append(token)
                    val display = TutorController.extractAnswerForDisplay(responseText.toString())
                    runOnUiThread { blackboardMarkwon.setMarkdown(responseInner, display) }
                },
                onDone = { _, _, _ ->
                    val finalAnswer = TutorController.extractAnswerForDisplay(responseText.toString())
                    bbChatHistory.add("user: $question")
                    bbChatHistory.add("assistant: ${finalAnswer.take(600)}")
                    runOnUiThread {
                        blackboardMarkwon.setMarkdown(responseInner, finalAnswer)
                        stepsScrollView.postDelayed(
                            { stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 200
                        )
                        // ── "Explain in BB Mode" button ───────────────────────
                        val bbExplainBtn = TextView(this@BlackboardActivity).apply {
                            text = "▶  Explain in Blackboard Mode"
                            textSize = computedFontSp - 7f
                            setTextColor(Color.parseColor("#C8A0FF"))
                            typeface = caveat
                            setPadding(0, (12 * dp).toInt(), 0, 4)
                            background = null
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (10 * dp).toInt() }
                        }
                        bbExplainBtn.setOnClickListener {
                            // Collapse the answer card to save space while lesson plays
                            contentArea.visibility = View.GONE
                            chevron.text = "▶"
                            requestInlineBbLesson(question, finalAnswer)
                        }
                        contentArea.addView(bbExplainBtn)
                    }
                },
                onError = { err -> runOnUiThread { responseInner.text = "⚠️ $err" } }
            )
        }
    }

    /**
     * Generates a fresh BB mini-lesson for [question] and APPENDS it below the existing
     * board content (instead of replacing it). A purple separator divider and topic header
     * are inserted first so the student can see where the inline lesson begins.
     */
    /**
     * Generates a full animated BB lesson for [question] and APPENDS it to the current lesson.
     * The new steps are added to [steps] so they play with the same animations, TTS, images,
     * and seekbar as the original lesson — fully consistent, not static cards.
     */
    private fun requestInlineBbLesson(question: String, chatAnswer: String) {
        val dp = resources.displayMetrics.density
        val board = boardLayout ?: return
        val caveat = ResourcesCompat.getFont(this, R.font.kalam)

        // Stop current audio so the incoming lesson doesn't compete
        aiTtsEngine.stop()
        typeAnimator?.cancel()
        seekBarAnimator?.cancel()
        handWriter.visibility = View.INVISIBLE

        // ── Purple separator + topic header appended to board ─────────────────
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (2 * dp).toInt()
            ).apply { topMargin = (24 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
            setBackgroundColor(Color.parseColor("#3D1A6E"))
        }
        val topicHeader = TextView(this).apply {
            text = "🎓  ${question.take(60)}"
            textSize = computedFontSp - 4f
            setTextColor(Color.parseColor("#C8A0FF"))
            typeface = caveat
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val loadingTv = TextView(this).apply {
            text = "⏳ Building lesson…"
            textSize = computedFontSp - 6f
            setTextColor(Color.parseColor("#8070A0"))
            typeface = caveat
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() }
        }
        board.addView(divider)
        board.addView(topicHeader)
        board.addView(loadingTv)
        stepsScrollView.postDelayed({ stepsScrollView.smoothScrollTo(0, stepsContainer.bottom) }, 200)

        val enrichedMessage = buildString {
            append(question)
            if (chatAnswer.isNotBlank()) {
                append("\n\n[Context: "); append(chatAnswer.take(400)); append("]")
            }
        }
        val uid = intent.getStringExtra(EXTRA_USER_ID)?.ifBlank { null }
        lifecycleScope.launch(Dispatchers.IO) {
            BlackboardGenerator.generate(
                messageContent       = enrichedMessage,
                userId               = uid,
                preferredLanguageTag = preferredLanguageTag,
                onStatus = { msg, _ -> runOnUiThread { loadingTv.text = msg } },
                onSuccess = { inlineSteps ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        // Remove the loading indicator (divider + header stay)
                        board.removeView(loadingTv)

                        // Record the index where the inline lesson begins
                        val firstNewStepIdx = steps.size

                        // Extend the master steps list → seekbar, dots, advanceFrame() all work
                        steps = steps + inlineSteps

                        // Update seekbar range and navigation dots for the longer lesson
                        progressSeekBar.max = maxOf(1, steps.sumOf { it.frames.size } - 1)
                        buildDots()
                        updateCounterAndDots()

                        // Preload audio for the first few inline frames
                        preloadUpcoming(firstNewStepIdx, 0, count = 3)

                        // showFrame at firstNewStepIdx > 0 appends without clearing the board,
                        // so the previous lesson stays visible above the inline content.
                        isPaused = false
                        pauseBtn.text = "⏸"
                        showFrame(firstNewStepIdx, 0)
                    }
                },
                onError = { err ->
                    runOnUiThread { loadingTv.text = "⚠️ $err" }
                }
            )
        }
    }

    private fun buildChatCard(
        text: String,
        bgColor: String,
        textColor: String,
        caveat: Typeface?,
        dp: Float,
        imageBase64: String? = null
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * dp
            setColor(Color.parseColor(bgColor))
            setStroke((1 * dp).toInt(), Color.parseColor("#30707090"))
        }
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * dp).toInt() }
        addView(TextView(this@BlackboardActivity).apply {
            this.text = text
            textSize = computedFontSp - 6f
            setTextColor(Color.parseColor(textColor))
            typeface = caveat
            setLineSpacing(0f, 1.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        // Show attached image thumbnail below the text
        if (!imageBase64.isNullOrBlank()) {
            try {
                val bytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    addView(ImageView(this@BlackboardActivity).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (140 * dp).toInt()
                        ).apply { topMargin = (8 * dp).toInt() }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE; cornerRadius = 8 * dp
                        }
                        clipToOutline = true
                    })
                }
            } catch (_: Exception) {}
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
        buildStepNameStrip()
    }

    private fun buildStepNameStrip() {
        stepNamesContainer.removeAllViews()
        if (steps.isEmpty()) { stepNamesScrollView.visibility = View.GONE; return }
        val dp = resources.displayMetrics.density
        steps.forEachIndexed { i, step ->
            val label = step.title.let { if (it.length > 22) it.take(22) + "…" else it }
            val isActive = i == currentStepIdx
            val pill = TextView(this).apply {
                text = "${i + 1}. $label"
                textSize = 11f
                setTextColor(Color.parseColor(if (isActive) "#FFFFFF" else "#88AABB"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 12 * dp
                    setColor(if (isActive) Color.parseColor("#3C3CBD") else Color.parseColor("#1A1A2E"))
                    setStroke((1 * dp).toInt(), if (isActive) Color.parseColor("#5C5CF0") else Color.parseColor("#334466"))
                }
                setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * dp).toInt() }
                setOnClickListener { if (i <= currentStepIdx) showFrame(i, 0) }
            }
            stepNamesContainer.addView(pill)
        }
        stepNamesScrollView.visibility = View.VISIBLE
    }

    private fun updateStepNameStrip(activeIdx: Int) {
        val dp = resources.displayMetrics.density
        for (i in 0 until stepNamesContainer.childCount) {
            val pill = stepNamesContainer.getChildAt(i) as? TextView ?: continue
            val isActive = i == activeIdx
            pill.setTextColor(Color.parseColor(if (isActive) "#FFFFFF" else "#88AABB"))
            (pill.background as? GradientDrawable)?.apply {
                setColor(if (isActive) Color.parseColor("#3C3CBD") else Color.parseColor("#1A1A2E"))
                setStroke((1 * dp).toInt(), if (isActive) Color.parseColor("#5C5CF0") else Color.parseColor("#334466"))
            }
        }
        if (activeIdx < stepNamesContainer.childCount) {
            stepNamesScrollView.post {
                stepNamesContainer.getChildAt(activeIdx)?.let { pill ->
                    stepNamesScrollView.smoothScrollTo(pill.left, 0)
                }
            }
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

