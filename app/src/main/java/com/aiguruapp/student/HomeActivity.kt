package com.aiguruapp.student

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bumptech.glide.Glide
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.adapters.SubjectAdapter
import com.aiguruapp.student.BlackboardActivity
import com.aiguruapp.student.BuildConfig
import com.aiguruapp.student.chat.BlackboardGenerator
import com.aiguruapp.student.config.AccessGate
import com.aiguruapp.student.config.AccessGate.Feature
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.config.PlanEnforcer
import com.aiguruapp.student.models.AppUpdateConfig
import com.aiguruapp.student.utils.AppUpdateBus
import com.aiguruapp.student.utils.AppUpdateManager
import com.aiguruapp.student.models.UserMetadata
import com.aiguruapp.student.utils.ConfigManager
import com.aiguruapp.student.utils.SchoolTheme
import com.aiguruapp.student.utils.FeedbackManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yalantis.ucrop.UCrop
import com.aiguruapp.student.utils.MediaManager
import com.aiguruapp.student.utils.VoiceManager
import com.aiguruapp.student.utils.VoiceRecognitionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.aiguruapp.student.daily.DailyQuestionsManager
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.firestore.HomeSmartContentLoader
import com.aiguruapp.student.firestore.SmartCard
import com.aiguruapp.student.firestore.StudentStatsManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aiguruapp.student.models.FirestoreOffer
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

class HomeActivity : BaseActivity() {

    private lateinit var subjectsRecyclerView: RecyclerView
    private val subjectsList = mutableListOf<String>()
    private lateinit var subjectAdapter: SubjectAdapter
    private lateinit var userId: String
    private var homePendingForceUpdate: AppUpdateManager.UpdateResult.ForceUpdate? = null
    private lateinit var drawerLayout: DrawerLayout

    // ── Dialog topic-attach state (mirrors FullChatFragment) ──────────────────
    private var homeCameraImageUri: Uri? = null
    private var homePendingImageUri: Uri? = null
    private var homePendingImageBase64: String? = null
    private var homeIsListening = false
    private lateinit var homeVoiceManager: VoiceManager
    private lateinit var homeMediaManager: MediaManager

    // Active sheet references (non-null while showBbTopicDialog is open)
    private var homeSheetImgCard: LinearLayout? = null
    private var homeSheetThumb: ImageView? = null
    private var homeTopicInput: EditText? = null
    private var homeMicTile: LinearLayout? = null

    private val homeCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && homeCameraImageUri != null) launchHomeCrop(homeCameraImageUri!!)
        }
    private val homeGalleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) launchHomeCrop(uri)
        }
    private val homeCropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            when (result.resultCode) {
                android.app.Activity.RESULT_OK -> {
                    val cropped = data?.let { UCrop.getOutput(it) } ?: return@registerForActivityResult
                    applyHomeCroppedImage(cropped)
                }
                UCrop.RESULT_ERROR -> {
                    Log.w("Home", "UCrop error: ${data?.let { UCrop.getError(it)?.message }}")
                }
            }
        }
    private var drawerChatLimit: Int  = 0
    private var drawerBbLimit: Int    = 0
    private var drawerVoiceLimit: Int = 0
    private var hasShownQuotaWarning  = false

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Redirect to login if no session
        if (!SessionManager.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        homeVoiceManager = VoiceManager(this)
        homeMediaManager = MediaManager(this)

        applySchoolBranding()
        setupGreeting()
        setupStudentInfo()
        userId = SessionManager.getFirestoreUserId(this)
        setupRecyclerView()
        loadSubjects()
        // Offers are gated by usage stats: see loadSmartHomeContent() — first-time
        // users see a clean home until they complete at least one chat or BB session.

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.homeSwipeRefresh)
        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary), getColor(R.color.colorSecondary))
        swipeRefresh.setOnRefreshListener {
            loadSubjects()
            loadQuotaStrip()
            // Offers re-fetched via loadSmartHomeContent → loadOffersFromFirestore (gated by usage)
            loadSmartHomeContent()
            swipeRefresh.isRefreshing = false
        }

        findViewById<MaterialButton>(R.id.addSubjectButton).setOnClickListener {
            showAddSubjectDialog()
        }
        findViewById<MaterialButton>(R.id.generalChatButton).let { btn ->
            btn.setOnClickListener {
                startActivity(
                    Intent(this, ChatHostActivity::class.java)
                        .putExtra("subjectName", "General")
                        .putExtra("chapterName", "General Chat")
                )
            }
            // Animated color-cycling for the prominent Quick Chat button
            startQuickChatPulseAnimation(btn)
        }
        setupLangChip()

        drawerLayout = findViewById(R.id.homeDrawerLayout)

        // Hamburger opens the navigation drawer
        findViewById<View?>(R.id.drawerToggleBtn)?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Avatar (top-right) shows a quick logout confirmation — everything else is in the drawer
        findViewById<View?>(R.id.profileButton)?.setOnClickListener {
            confirmLogout()
        }

        // ? Help / guide button
        findViewById<View?>(R.id.helpGuideBtn)?.setOnClickListener {
            showAppGuide()
        }

        // Plan badge still navigates to subscription
        findViewById<TextView?>(R.id.planBadgeText)?.setOnClickListener {
            startActivity(
                Intent(this, SubscriptionActivity::class.java)
                    .putExtra("schoolId", SessionManager.getSchoolId(this))
            )
        }

        setupDrawer()
        setupQuickActions()
        populateTopicChips()

        // BB intro + smart cards: run after layout is settled
        loadSmartHomeContent()
    }

    override fun onResume() {
        super.onResume()
        setupStudentInfo()
        loadQuotaStrip()
        // If user returned from Play Store for a mandatory update, re-check.
        homePendingForceUpdate?.let { fu ->
            if (BuildConfig.VERSION_CODE < fu.config.minVersionCode) {
                showHomeForceUpdateDialog(fu.config)
            } else {
                homePendingForceUpdate = null
            }
            return
        }
        // Receive any late-arriving update result (check finished after splash).
        AppUpdateBus.consume { result -> handleHomeUpdateResult(result) }
        // Show feedback sheet after first / periodic BB session.
        FeedbackManager.showIfNeeded(this)
    }

    override fun onPause() {
        super.onPause()
        AppUpdateBus.clearConsumer()
    }

    @SuppressLint("ResourceType")
    private fun loadQuotaStrip() {
        val uid = SessionManager.getFirestoreUserId(this)
        if (uid.isBlank() || uid == "guest_user") return

        val db = FirebaseFirestore.getInstance()

        // Single read: users_table has all quota counters AND plan limits written by the server.
        // plan_daily_chat_limit / plan_daily_bb_limit are set on registration and plan activation,
        // so they're always authoritative — no need to fetch plans/ collection for the main quota.
        db.collection("users_table").document(uid).get()
            .addOnSuccessListener { userDoc ->
                if (!userDoc.exists()) return@addOnSuccessListener

                val chatToday           = userDoc.getLong("chat_questions_today")?.toInt() ?: 0
                val bbToday             = userDoc.getLong("bb_sessions_today")?.toInt() ?: 0
                val aiTtsCharsUsedToday = userDoc.getLong("ai_tts_chars_used_today")?.toInt() ?: 0
                val updatedAt           = userDoc.getLong("questions_updated_at") ?: 0L
                val aiTtsUpdatedAt      = userDoc.getLong("ai_tts_updated_at") ?: 0L

                val isSameDay    = updatedAt > 0L && !PlanEnforcer.isNewQuotaDay(updatedAt)
                val aiTtsSameDay = aiTtsUpdatedAt > 0L && !PlanEnforcer.isNewQuotaDay(aiTtsUpdatedAt)
                val chatUsed     = if (isSameDay) chatToday else 0
                val bbUsed       = if (isSameDay) bbToday else 0
                val aiTtsCharsUsed = if (aiTtsSameDay) aiTtsCharsUsedToday else 0

                // Limits come from user doc (server writes them); default to free-plan values.
                // 0 stored means unlimited (written as 0 for unlimited plans by server).
                val chatLimit = userDoc.getLong("plan_daily_chat_limit")?.toInt() ?: 12
                val bbLimit   = userDoc.getLong("plan_daily_bb_limit")?.toInt() ?: 2

                drawerChatLimit = chatLimit
                drawerBbLimit   = bbLimit

                // 0 limit = unlimited → show as -1 so UI renders "Unlimited"
                val chatLeft = if (chatLimit <= 0) -1 else (chatLimit - chatUsed).coerceAtLeast(0)
                val bbLeft   = if (bbLimit   <= 0) -1 else (bbLimit   - bbUsed).coerceAtLeast(0)

                // Fetch TTS chars from plans/ (not stored on user doc) — non-critical
                val planId = (userDoc.getString("planId") ?: "").ifBlank { "free" }
                db.collection("plans").document(planId).get()
                    .addOnSuccessListener { planDoc ->
                        @Suppress("UNCHECKED_CAST")
                        val limitsMap = planDoc.get("limits") as? Map<String, Any>
                        val aiTtsQuotaChars = (limitsMap?.get("ai_tts_quota_chars") as? Long)?.toInt() ?: 0
                        drawerVoiceLimit = aiTtsQuotaChars
                        val aiTtsLeft = if (aiTtsQuotaChars <= 0) 0
                            else (aiTtsQuotaChars - aiTtsCharsUsed).coerceAtLeast(0)
                        runOnUiThread {
                            updateQuotaStripUI(chatLeft, bbLeft, aiTtsLeft)
                            showQuotaToastIfNeeded(chatLeft, bbLeft)
                        }
                    }
                    .addOnFailureListener {
                        runOnUiThread {
                            updateQuotaStripUI(chatLeft, bbLeft, 0)
                            showQuotaToastIfNeeded(chatLeft, bbLeft)
                        }
                    }
            }
    }

    private fun showQuotaToastIfNeeded(chatLeft: Int, bbLeft: Int) {
        if (hasShownQuotaWarning) return
        val msg = when {
            chatLeft == 0 -> "You've used all your questions for today. Upgrade to ask more."
            chatLeft in 1..2 -> "Only $chatLeft question${if (chatLeft == 1) "" else "s"} left today."
            bbLeft == 0   -> "No blackboard sessions left today. Upgrade for more."
            else          -> return
        }
        hasShownQuotaWarning = true
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * Update quota display on both the main screen strip and the navigation drawer.
     * @param chatLeft  remaining chat questions today (-1 = unlimited)
     * @param bbLeft    remaining blackboard sessions today (-1 = unlimited)
     * @param aiTtsCharsLeft remaining AI TTS chars today (-1 = unlimited / 0 = no quota)
     */
    private fun updateQuotaStripUI(chatLeft: Int, bbLeft: Int, aiTtsCharsLeft: Int) {

        // ── Main screen: top quota strip (chat + BB chips) ────────────────
        val quotaStrip = findViewById<LinearLayout?>(R.id.homeQuotaStrip)
        if (quotaStrip != null && (drawerChatLimit > 0 || drawerBbLimit > 0)) {
            quotaStrip.visibility = View.VISIBLE

            val chatChipText = when {
                chatLeft < 0  -> "Unlimited questions"
                chatLeft == 0 -> "No questions left today"
                chatLeft == 1 -> "1 question left today"
                else          -> "$chatLeft questions left today"
            }
            val chatChipColor = if (chatLeft in 0..2) "#BF360C" else "#1565C0"
            findViewById<TextView?>(R.id.homeQuotaChatText)?.apply {
                text = chatChipText
                setTextColor(Color.parseColor(chatChipColor))
            }

            val bbChipText = when {
                bbLeft < 0  -> "Unlimited lessons"
                bbLeft == 0 -> "No lessons left today"
                bbLeft == 1 -> "1 lesson left today"
                else        -> "$bbLeft lessons left today"
            }
            val bbChipColor = if (bbLeft in 0..0) "#BF360C" else "#7B1FA2"
            findViewById<TextView?>(R.id.homeQuotaBbText)?.apply {
                text = bbChipText
                setTextColor(Color.parseColor(bbChipColor))
            }
        }

        // ── Main screen: Ask AI card subtitle ────────────────────────────
        if (drawerChatLimit > 0 && chatLeft >= 0) {
            val subtitle = when {
                chatLeft == 0 -> "Limit reached today"
                else          -> "$chatLeft questions left"
            }
            findViewById<TextView?>(R.id.chatQuotaSubtitle)?.text = subtitle
        }

        // ── Main screen: BB card quota pill ──────────────────────────────
        val bbPill = findViewById<TextView?>(R.id.bbQuotaPill)
        if (bbPill != null) {
            if (drawerBbLimit <= 0) {
                bbPill.visibility = View.GONE
            } else {
                bbPill.visibility = View.VISIBLE
                bbPill.text = when (bbLeft) {
                    0    -> "No sessions left today"
                    1    -> "1 session left today"
                    else -> "$bbLeft sessions left today"
                }
                bbPill.setTextColor(ContextCompat.getColor(this,
                    if (bbLeft == 0) R.color.quotaExhausted else R.color.quotaAvailable))
            }
        }

        // ── Navigation drawer: chat progress bar ─────────────────────────
        val chatText = if (chatLeft < 0) "Unlimited" else "$chatLeft left"
        val chatColor = if (chatLeft in 0..3) "#BF360C" else "#1565C0"
        findViewById<TextView?>(R.id.drawerChatLeft)?.apply {
            text = chatText
            setTextColor(Color.parseColor(chatColor))
        }
        findViewById<ProgressBar?>(R.id.drawerChatProgress)?.let { bar ->
            val used = if (drawerChatLimit > 0 && chatLeft >= 0) drawerChatLimit - chatLeft else 0
            bar.max = if (drawerChatLimit > 0) drawerChatLimit else 100
            bar.progress = used.coerceAtLeast(0)
        }

        // ── Navigation drawer: BB progress bar ───────────────────────────
        val bbText = if (bbLeft < 0) "Unlimited" else "$bbLeft left"
        val bbColor = if (bbLeft in 0..1) "#BF360C" else "#7B1FA2"
        findViewById<TextView?>(R.id.drawerBbLeft)?.apply {
            text = bbText
            setTextColor(Color.parseColor(bbColor))
        }
        findViewById<ProgressBar?>(R.id.drawerBbProgress)?.let { bar ->
            val used = if (drawerBbLimit > 0 && bbLeft >= 0) drawerBbLimit - bbLeft else 0
            bar.max = if (drawerBbLimit > 0) drawerBbLimit else 100
            bar.progress = used.coerceAtLeast(0)
        }

        // ── Navigation drawer: AI Voice credits ──────────────────────────
        val voiceRow = findViewById<LinearLayout?>(R.id.drawerVoiceRow)
        if (aiTtsCharsLeft != 0) {
            voiceRow?.visibility = View.VISIBLE
            val voiceText = if (aiTtsCharsLeft < 0) "Unlimited" else "$aiTtsCharsLeft chars left"
            val voiceColor = if (aiTtsCharsLeft in 0..1000) "#BF360C" else "#1E9B6B"
            findViewById<TextView?>(R.id.drawerVoiceLeft)?.apply {
                text = voiceText
                setTextColor(Color.parseColor(voiceColor))
            }
            findViewById<ProgressBar?>(R.id.drawerVoiceProgress)?.let { bar ->
                val used = if (drawerVoiceLimit > 0 && aiTtsCharsLeft >= 0) drawerVoiceLimit - aiTtsCharsLeft else 0
                bar.max = if (drawerVoiceLimit > 0) drawerVoiceLimit else 100
                bar.progress = used.coerceAtLeast(0)
            }
        } else {
            voiceRow?.visibility = View.GONE
        }
    }

    // Called from loadDailyChallenge() after credits are fetched, so the drawer
    // balance stays in sync whenever the home screen refreshes.
    internal fun updateCreditsDisplay(balance: Int) {
        runOnUiThread {
            val chipText = "⭐ $balance"
            findViewById<TextView?>(R.id.creditsChipText)?.apply {
                text = chipText
                visibility = View.VISIBLE
                setOnClickListener {
                    // Tap → open subscription / top-up screen so user can recharge credits
                    startActivity(
                        Intent(this@HomeActivity, SubscriptionActivity::class.java)
                            .putExtra("schoolId", SessionManager.getSchoolId(this@HomeActivity))
                            .putExtra("show_topups", true)
                    )
                }
            }
            findViewById<TextView?>(R.id.drawerCreditsBalance)?.text = balance.toString()
        }
    }

    private fun applySchoolBranding() {
        val schoolId = SessionManager.getSchoolId(this)
        val school = ConfigManager.getSchool(this, schoolId)

        // Load school colors into the centralized SchoolTheme singleton
        SchoolTheme.load(school?.branding)
        SchoolTheme.applyStatusBar(window)

        // Header background (LinearLayout — set color directly)
        SchoolTheme.setBackground(findViewById(R.id.homeHeader))

        // Quick-action chips (only tint addSubjectButton; generalChatButton uses animation)
        SchoolTheme.tint(findViewById(R.id.addSubjectButton))

        // Quick-chat button text color stays white — don't override from school theme
        // (its background is controlled by the ValueAnimator, so tinting here would fight it)

        // Header text: use white if primary is dark enough, else use primaryDark
        val headerTextColor = android.graphics.Color.WHITE
        findViewById<TextView?>(R.id.greetingText)?.setTextColor(headerTextColor)
        findViewById<TextView?>(R.id.userNameText)?.setTextColor(headerTextColor)
        findViewById<TextView?>(R.id.schoolNameSubtitle)?.setTextColor(
            android.graphics.Color.parseColor("#FFFFFFCC"))
        findViewById<TextView?>(R.id.planBadgeText)?.setTextColor(headerTextColor)

        // School logo — load from URL if available, otherwise hide
        val logoUrl = school?.branding?.logoUrl ?: ""
        val logoImageView = findViewById<ImageView?>(R.id.schoolLogoImage)
        if (logoUrl.isNotBlank() && logoImageView != null) {
            logoImageView.visibility = View.VISIBLE
            Glide.with(this)
                .load(logoUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_school_placeholder)
                .error(R.drawable.ic_school_placeholder)
                .into(logoImageView)
        } else {
            logoImageView?.visibility = View.GONE
        }
    }

    private fun setupStudentInfo() {
        val studentName = SessionManager.getStudentName(this)
        val schoolName = SessionManager.getSchoolName(this)
        val planName = SessionManager.getPlanName(this)

        findViewById<TextView?>(R.id.userNameText)?.text = studentName
        // Show school name as subtitle if view exists
        findViewById<TextView?>(R.id.schoolNameSubtitle)?.text = schoolName
        // Always show plan badge — new users see "Free" so they can navigate to subscribe
        val displayPlan = if (planName.isNotBlank()) "📋 $planName" else "📋 Free"
        findViewById<TextView?>(R.id.planBadgeText)?.apply {
            text = displayPlan
            visibility = android.view.View.VISIBLE
        }
        // Sync drawer header too
        if (::drawerLayout.isInitialized) updateDrawerHeader()
    }

    private fun setupGreeting() {
        val config = ConfigManager.getAppConfig(this)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> config.greetingMessages["morning"] ?: "Good morning! ☀️"
            hour < 17 -> config.greetingMessages["afternoon"] ?: "Good afternoon! 👋"
            else -> config.greetingMessages["evening"] ?: "Good evening! 🌙"
        }
        findViewById<TextView>(R.id.greetingText).text = greeting
    }

    // ── Language chip ─────────────────────────────────────────────────────────

    private val langLabels = arrayOf(
        "English only",
        "Hindi + English",
        "Telugu + English",
        "Tamil + English",
        "Kannada + English",
        "Marathi + English",
        "Bengali + English",
        "Gujarati + English"
    )
    private val langCodes = arrayOf(
        "en-US", "hi-IN", "te-IN", "ta-IN", "kn-IN", "mr-IN", "bn-IN", "gu-IN"
    )
    private val langShort = arrayOf(
        "English", "Hindi", "Telugu", "Tamil", "Kannada", "Marathi", "Bengali", "Gujarati"
    )

    /** Draws a rainbow gradient that continuously sweeps left→right across the button. */
    private fun startQuickChatPulseAnimation(button: MaterialButton) {
        // Override Material tinting so our custom gradient background shows through
        button.backgroundTintList = null

        val rainbowColors = intArrayOf(
            
        //    android.graphics.Color.parseColor("#000000"),
            android.graphics.Color.parseColor("#066526"),  // green
            android.graphics.Color.parseColor("#208eee"),  // blue
            // android.graphics.Color.parseColor("#ffffff"),  // violet
     
        )

        // Custom drawable: draws a LinearGradient whose x-origin shifts continuously
        var shiftFraction = 0f
        val shimmerDrawable = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                if (b.isEmpty) return
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                val cornerRadius = h / 2f  // pill shape
                // Shift the gradient window across 2× the button width so colours flow
                val startX = shiftFraction * w * 2f - w
                val endX   = startX + w * 1.8f
                paint.shader = android.graphics.LinearGradient(
                    startX, 0f, endX, 0f,
                    rainbowColors, null,
                    android.graphics.Shader.TileMode.MIRROR
                )
                canvas.drawRoundRect(android.graphics.RectF(b), cornerRadius, cornerRadius, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
        }

        button.background = shimmerDrawable
        // Keep elevation so the shadow is visible above other views
        button.elevation = resources.displayMetrics.density * 8f
        button.stateListAnimator = null  // remove Material press animation so elevation stays stable

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10000L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                shiftFraction = anim.animatedValue as Float
                shimmerDrawable.invalidateSelf()
            }
        }
        animator.start()
        button.tag = animator  // hold reference for lifecycle cleanup
    }

    private fun setupLangChip() {
        val chip = findViewById<MaterialButton?>(R.id.langChipButton) ?: return
        refreshLangChip(chip)
        chip.setOnClickListener { showLangPicker(chip) }
    }

    private fun refreshLangChip(chip: MaterialButton) {
        val saved = SessionManager.getPreferredLang(this)
        val idx   = langCodes.indexOf(saved).coerceAtLeast(0)
        chip.text = "🌐 ${langShort[idx]}"
    }

    private fun showLangPicker(chip: MaterialButton) {
        val saved    = SessionManager.getPreferredLang(this)
        val current  = langCodes.indexOf(saved).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("🌐 Teaching Language")
            .setSingleChoiceItems(langLabels, current) { dialog, which ->
                SessionManager.savePreferredLang(this, langCodes[which])
                refreshLangChip(chip)
                dialog.dismiss()
                Toast.makeText(this, "Language set to ${langLabels[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppGuide() {
        val guide = """
🎓  Blackboard Mode
Animated step-by-step lessons on any topic. Tap the big purple card or pick a chapter → use the Blackboard button.

💬  Ask AI (Chat)
Ask any question about your syllabus. The AI answers with explanations, examples and diagrams.

📷  Camera / Image Q&A
Inside chat or the Blackboard dialog tap 📷 to attach a photo of your textbook — the AI explains it.

🎤  Voice Input
Tap 🎤 in any question bar and ask out loud — hands-free Q&A.

📌  Saved Sessions
Your saved Blackboard lessons are stored per-chapter. Open a chapter → Saved tab to replay them.

📖  NCERT Viewer
Chapters with NCERT books auto-download the PDF and let you read or ask about any page.

🏆  Progress & Stats
Open the ☰ drawer → Progress to see your learning streaks, BB sessions and quiz scores.

💡  Tips
• After 3 chapters, the app will ask you to rate it — your feedback helps us improve!
• Upgrade your plan for unlimited sessions and AI voice narration.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("📚 App Guide & Features")
            .setMessage(guide)
            .setPositiveButton("Got it! 👍", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                SessionManager.logout(this)
                com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                com.aiguruapp.student.auth.TokenManager.clearCache()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Smart home content: BB intro + personalised suggestion cards ──────────

    /**
     * Entry point called once from onCreate.
     * 1. Fetches usage summary from students_stats.
     * 2. Shows BB intro bottom sheet if user has never used BB mode.
     * 3. Loads personalised smart cards and renders them in the strip.
     * 4. Updates Today's Focus stats in the hero card.
     */
    private fun loadSmartHomeContent() {
        val uid = SessionManager.getFirestoreUserId(this)
        if (uid.isBlank() || uid == "guest_user") return

        StudentStatsManager.fetchUsageSummary(uid) { totalMessages, totalBbSessions, streakDays ->
            runOnUiThread {
                // Show streak badge in hero header
                val streakBadge = findViewById<TextView?>(R.id.streakBadgeText)
                val headerSubtitle = findViewById<TextView?>(R.id.homeHeaderSubtitle)
                if (streakDays > 0) {
                    streakBadge?.visibility = View.VISIBLE
                    streakBadge?.text = "🔥 ${streakDays}d"
                    if (streakDays >= 3) {
                        headerSubtitle?.text = "🏆 Great streak! Keep going today"
                    } else {
                        headerSubtitle?.text = "Your visual lesson is waiting today"
                    }
                } else {
                    headerSubtitle?.text = "Start your streak today"
                }

                // Show persistent nudge card until the user's first BB session
                val nudgeCard = findViewById<com.google.android.material.card.MaterialCardView?>(R.id.bbNudgeCard)
                if (nudgeCard != null) {
                    if (totalBbSessions == 0L) {
                        nudgeCard.visibility = View.VISIBLE
                        nudgeCard.setOnClickListener { showBbTopicDialog() }
                    } else {
                        nudgeCard.visibility = View.GONE
                    }
                }
            }

            // Show BB intro bottom sheet if never used BB
            if (totalBbSessions == 0L) {
                StudentStatsManager.hasSeen_bb_intro(uid) { alreadySeen ->
                    if (!alreadySeen) {
                        // Mark seen immediately so it never shows twice
                        StudentStatsManager.markBbIntroSeen(uid)
                        runOnUiThread {
                            // Small delay so the home screen settles first
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    BbIntroBottomSheet().show(supportFragmentManager, BbIntroBottomSheet.TAG)
                                }
                            }, 800)
                        }
                    }
                }
            }

            // Load personalised smart cards
            HomeSmartContentLoader.loadForUser(
                totalMessages   = totalMessages,
                totalBbSessions = totalBbSessions,
                streakDays      = streakDays,
                onSuccess = { cards ->
                    runOnUiThread { renderSmartCards(cards) }
                }
            )

            // Offers / "Something Interesting" — only show after the user has
            // actually used the app (≥1 BB session OR ≥1 chat message). This
            // keeps the first-time home screen clean and focused.
            val isFirstTimeUser = totalBbSessions == 0L && totalMessages == 0L
            if (!isFirstTimeUser && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runOnUiThread { loadOffersFromFirestore() }
            } else {
                runOnUiThread {
                    findViewById<View?>(R.id.offersSectionHeader)?.visibility = View.GONE
                    findViewById<View?>(R.id.offersBannerScroll)?.visibility = View.GONE
                }
            }
        }

        // Daily challenge card + credit balance (independent of stats)
        loadDailyChallenge()
    }

    private fun loadDailyChallenge() {
        lifecycleScope.launch(Dispatchers.IO) {
            val questions = DailyQuestionsManager.fetchFeed(this@HomeActivity)
            val credits = DailyQuestionsManager.fetchCreditBalance()
            val pending = questions.filter { it.status == "pending" }
            updateCreditsDisplay(credits)
            runOnUiThread {
                // Show or hide daily challenge card
                val card = findViewById<android.view.ViewGroup?>(R.id.dailyChallengeCard) ?: return@runOnUiThread
                if (pending.isEmpty()) {
                    card.visibility = View.GONE
                    return@runOnUiThread
                }
                val q = pending.first()
                val wasHidden = card.visibility != View.VISIBLE
                card.visibility = View.VISIBLE
                card.findViewById<TextView?>(R.id.challengeHookText)?.text = q.hook
                card.findViewById<TextView?>(R.id.challengeQuestionText)?.text = q.question
                val diffLabel = when (q.difficulty) { 1 -> "Easy" 2 -> "Medium" else -> "Hard" }
                card.findViewById<TextView?>(R.id.challengeDifficultyText)?.text = "$diffLabel · ${q.subject}"
                card.findViewById<TextView?>(R.id.challengeCreditsText)?.text = "+${q.creditsReward} ⭐"
                card.setOnClickListener {
                    val uid = SessionManager.getFirestoreUserId(this@HomeActivity)
                    val intent = Intent(this@HomeActivity, BlackboardActivity::class.java).apply {
                        putExtra(BlackboardActivity.EXTRA_MESSAGE, q.question)
                        putExtra(BlackboardActivity.EXTRA_SUBJECT, q.subject)
                        if (uid.isNotBlank() && uid != "guest_user") {
                            putExtra(BlackboardActivity.EXTRA_USER_ID, uid)
                        }
                        putExtra("daily_question_id", q.id)
                    }
                    startActivity(intent)
                }
                // Entrance + subtle pulse on the credits badge to draw attention
                if (wasHidden) {
                    card.alpha = 0f
                    card.translationY = 24f
                    card.animate().alpha(1f).translationY(0f).setDuration(420).start()
                }
                card.findViewById<TextView?>(R.id.challengeCreditsText)?.let { badge ->
                    badge.animate().scaleX(1.12f).scaleY(1.12f).setDuration(380)
                        .withEndAction {
                            badge.animate().scaleX(1f).scaleY(1f).setDuration(380).start()
                        }.start()
                }
            }
        }
    }

    /** Renders personalised suggestion cards in the horizontal "For You" strip. */
    private fun renderSmartCards(cards: List<SmartCard>) {
        if (cards.isEmpty()) return
        val section   = findViewById<android.widget.LinearLayout?>(R.id.smartCardSection) ?: return
        val container = findViewById<android.widget.LinearLayout?>(R.id.smartCardsContainer) ?: return
        container.removeAllViews()

        for (card in cards) {
            val itemView = layoutInflater.inflate(R.layout.item_smart_card, container, false)

            itemView.findViewById<android.widget.TextView>(R.id.smartCardEmoji).text    = card.emoji
            itemView.findViewById<android.widget.TextView>(R.id.smartCardTitle).text    = card.title
            itemView.findViewById<android.widget.TextView>(R.id.smartCardSubtitle).text = card.subtitle
            val cta = itemView.findViewById<android.widget.TextView>(R.id.smartCardCta)
            cta.text = card.ctaLabel

            // Accent color
            val color = try { android.graphics.Color.parseColor(card.cardColor) }
                        catch (_: Exception) { android.graphics.Color.parseColor("#1565C0") }
            itemView.findViewById<View>(R.id.smartCardAccent)
                .backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            cta.setTextColor(color)

            itemView.setOnClickListener { handleSmartCardTap(card) }
            container.addView(itemView)
        }

        section.visibility = View.VISIBLE
    }

    /** Handles a tap on a smart card — either opens BB mode or navigates in-app. */
    private fun handleSmartCardTap(card: SmartCard) {
        when (card.type) {
            "bb_intro" -> {
                if (card.bbMessage.isBlank()) return
                val uid  = SessionManager.getFirestoreUserId(this)
                val lang = SessionManager.getPreferredLang(this).ifBlank { "en-US" }
                startActivity(
                    android.content.Intent(this, BlackboardActivity::class.java)
                        .putExtra(BlackboardActivity.EXTRA_MESSAGE, card.bbMessage)
                        .putExtra(BlackboardActivity.EXTRA_SUBJECT, card.subject.ifBlank { "General" })
                        .putExtra(BlackboardActivity.EXTRA_CHAPTER, card.chapter.ifBlank { "General" })
                        .putExtra(BlackboardActivity.EXTRA_USER_ID, uid)
                        .putExtra(BlackboardActivity.EXTRA_LANGUAGE_TAG, lang)
                )
            }
            "tip" -> {
                // For tips that point to a subject — open chat; otherwise just dismiss
                if (card.subject.isNotBlank() && card.chapter.isNotBlank()) {
                    startActivity(
                        android.content.Intent(this, ChatHostActivity::class.java)
                            .putExtra("subjectName", card.subject)
                            .putExtra("chapterName", card.chapter)
                    )
                }
            }
        }
    }

    private fun setupQuickActions() {
        // 🎓 Blackboard — primary hero CTA
        AccessGate.applyVisibility(this, findViewById(R.id.quickActionBbBtn), Feature.BLACKBOARD)
        val bbBtn = findViewById<com.google.android.material.card.MaterialCardView>(R.id.quickActionBbBtn)
        bbBtn?.setOnClickListener { showBbTopicDialog() }

        // Show cumulative BB session count as a subtle badge on the card
        val bbCountBadge = findViewById<android.widget.TextView?>(R.id.bbSessionsCountBadge)
        val bbDoneCount = getSharedPreferences("bb_prefs", MODE_PRIVATE).getInt("bb_sessions_alltime", 0)
        if (bbDoneCount > 0 && bbCountBadge != null) {
            bbCountBadge.text = "🎯 $bbDoneCount session${if (bbDoneCount == 1) "" else "s"} completed"
            bbCountBadge.visibility = android.view.View.VISIBLE
        }

        // Pulse animation on the BB hero card to draw attention
        bbBtn?.post {
            val pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                bbBtn,
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.04f, 1f),
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.04f, 1f),
            ).apply {
                duration = 900
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode  = android.animation.ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            pulse.start()
        }

        // 💬 Ask AI — secondary
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.quickActionChatBtn)
            ?.setOnClickListener {
                startActivity(
                    Intent(this, ChatHostActivity::class.java)
                        .putExtra("subjectName", "General")
                        .putExtra("chapterName", "General Chat")
                )
            }

        // � Saved Sessions — always visible, no gate needed
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.quickActionTasksBtn)
            ?.setOnClickListener {
                startActivity(
                    Intent(this, BbSavedSessionsActivity::class.java)
                        .putExtra(BbSavedSessionsActivity.EXTRA_SUBJECT, "General")
                        .putExtra(BbSavedSessionsActivity.EXTRA_CHAPTER, "General Chat")
                )
            }

        // Progress lives in the drawer — the stub View just satisfies AccessGate
        AccessGate.applyVisibility(this, findViewById(R.id.quickActionProgressBtn), Feature.PROGRESS_DASHBOARD)

        // 🔢 Calculator FAB — opens the floating calculator inherited from BaseActivity
        findViewById<com.google.android.material.card.MaterialCardView?>(R.id.calcFab)
            ?.setOnClickListener { showCalculator() }

        // 💬 Give Feedback chip
        findViewById<android.widget.TextView?>(R.id.feedbackChip)
            ?.setOnClickListener { FeedbackManager.showNow(this) }
    }

    /** Populate quick-launch topic chips and inner BB card topic chips. */
    private fun populateTopicChips() {
        val curatedTopics = listOf(
            Pair("🌿 Photosynthesis", "Photosynthesis"),
            Pair("⚛️ Atom Structure", "Atom"),
            Pair("⚡ Newton's Laws", "Newton's Laws"),
            Pair("💧 Water Cycle", "Water Cycle"),
            Pair("🧬 DNA & Genes", "DNA"),
            Pair("🔋 Electric Circuits", "Electric Circuits"),
            Pair("📐 Pythagoras", "Pythagoras"),
            Pair("🌍 Solar System", "Solar System")
        )

        val container = findViewById<LinearLayout?>(R.id.topicChipsContainer) ?: return
        val bbInnerContainer = findViewById<LinearLayout?>(R.id.bbInnerTopicsContainer)

        // Populate main topic chips row
        for ((label, topic) in curatedTopics) {
            val chip = MaterialButton(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#1A1A2E"))
                setBackgroundColor(Color.parseColor("#E8F0FE"))
                cornerRadius = 20
                setPaddingRelative(12, 6, 12, 6)
                setOnClickListener {
                    showBbTopicDialog(topicHint = topic)
                }
            }
            container.addView(chip)
            val params = chip.layoutParams as LinearLayout.LayoutParams
            params.marginEnd = 8
            chip.layoutParams = params
        }

        // Populate inner BB card chips (first 3 topics)
        if (bbInnerContainer != null) {
            for (i in 0..2) {
                if (i < curatedTopics.size) {
                    val (label, topic) = curatedTopics[i]
                    val cleanLabel = label.substringAfter(" ") // Remove emoji
                    val chip = MaterialButton(this).apply {
                        text = cleanLabel
                        textSize = 11f
                        setTextColor(Color.parseColor("#FFFFFF"))
                        setBackgroundColor(Color.parseColor("#00000000")) // transparent
                        cornerRadius = 16
                        setPaddingRelative(8, 3, 8, 3)
                        setOnClickListener {
                            showBbTopicDialog(topicHint = topic)
                        }
                    }
                    bbInnerContainer.addView(chip)
                    val params = chip.layoutParams as LinearLayout.LayoutParams
                    params.marginEnd = 6
                    chip.layoutParams = params
                }
            }
        }
    }

    /** Shows a polished bottom sheet to collect topic + duration, then launches BB mode. */
    private fun showBbTopicDialog(topicHint: String = "") {
        // Guests must sign in before using Blackboard mode
        if (SessionManager.isGuestMode(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sign in Required")
                .setMessage("Blackboard mode is available for registered students. Sign in to unlock animated lessons, save sessions, and track your progress!")
                .setPositiveButton("Sign In") { _, _ ->
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                .setNegativeButton("Not Now", null)
                .show()
            return
        }

        val dp     = resources.displayMetrics.density
        val labels = BlackboardGenerator.BbDuration.labels
        var selectedDurationLabel = BlackboardGenerator.BbDuration.MIN_2.label

        val sheet = BottomSheetDialog(this, R.style.Theme_BB_QuizDialog)

        // ── Root ─────────────────────────────────────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(20*dp,20*dp,20*dp,20*dp,0f,0f,0f,0f)
                setColor(Color.parseColor("#12122A"))
            }
            setPadding((20*dp).toInt(), (6*dp).toInt(), (20*dp).toInt(), (32*dp).toInt())
        }

        // Drag handle
        root.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (18*dp).toInt() }
            addView(View(this@HomeActivity).apply {
                layoutParams = LinearLayout.LayoutParams((44*dp).toInt(), (4*dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 2*dp
                    setColor(Color.parseColor("#44FFFFFF"))
                }
            })
        })

        // Header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (18*dp).toInt() }
        }
        headerRow.addView(TextView(this).apply {
            text = "🎓"; textSize = 26f; gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (40*dp).toInt(), (40*dp).toInt()
            ).apply { marginEnd = (12*dp).toInt() }
        })
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(TextView(this).apply {
            text = "Start a Blackboard Lesson"
            textSize = 18f
            setTextColor(Color.parseColor("#E8F0FF"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerText.addView(TextView(this).apply {
            text = "Type a topic or question to explore"
            textSize = 12f
            setTextColor(Color.parseColor("#667799"))
        })
        headerRow.addView(headerText)
        root.addView(headerRow)

        // ── Topic input ───────────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "What do you want to learn?"
            textSize = 12f
            setTextColor(Color.parseColor("#8899BB"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6*dp).toInt() }
        })
        val topicInput = EditText(this).apply {
            hint = "e.g. Explain Photosynthesis, Newton's 3rd law…"
            text = if (topicHint.isNotEmpty()) android.text.SpannableStringBuilder(topicHint) else android.text.SpannableStringBuilder("")
            textSize = 15f
            setTextColor(Color.parseColor("#F0EDD0"))
            setHintTextColor(Color.parseColor("#44667788"))
            minLines = 2; maxLines = 4
            gravity = android.view.Gravity.TOP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 12*dp
                setColor(Color.parseColor("#1E1E38"))
                setStroke((1*dp).toInt(), Color.parseColor("#334466BB"))
            }
            setPadding((14*dp).toInt(), (12*dp).toInt(), (14*dp).toInt(), (12*dp).toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (18*dp).toInt() }
        }
        root.addView(topicInput)

        // ── Topic suggestion chips ────────────────────────────────────────────
        val suggestions = listOf(
            "Newton's Laws of Motion", "Photosynthesis", "Pythagoras Theorem",
            "Water Cycle", "French Revolution", "Acids & Bases", "Ohm's Law", "Cell Structure"
        )
        root.addView(TextView(this).apply {
            text = "Quick topics"
            textSize = 11f
            setTextColor(Color.parseColor("#556677"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (5*dp).toInt() }
        })
        val suggestionsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16*dp).toInt() }
        }
        val suggestionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, (16*dp).toInt(), 0)
        }
        for (s in suggestions) {
            suggestionsRow.addView(TextView(this).apply {
                text = s
                textSize = 12f
                setTextColor(Color.parseColor("#AACCEE"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 14*dp
                    setColor(Color.parseColor("#1A2040"))
                    setStroke((1*dp).toInt(), Color.parseColor("#335577"))
                }
                setPadding((12*dp).toInt(), (6*dp).toInt(), (12*dp).toInt(), (6*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8*dp).toInt() }
                setOnClickListener {
                    topicInput.setText(s)
                    topicInput.setSelection(s.length)
                }
            })
        }
        suggestionsScroll.addView(suggestionsRow)
        root.addView(suggestionsScroll)

        // ── Duration chips ────────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "Session length"
            textSize = 12f
            setTextColor(Color.parseColor("#8899BB"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8*dp).toInt() }
        })
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (22*dp).toInt() }
        }
        val chipViews = labels.mapIndexed { idx, label ->
            TextView(this).apply {
                text = label; textSize = 13f; gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 20*dp
                    setColor(
                        if (label == selectedDurationLabel) Color.parseColor("#3C3CBD")
                        else Color.parseColor("#1E1E38")
                    )
                    setStroke((1*dp).toInt(),
                        if (label == selectedDurationLabel) Color.parseColor("#5C5CF0")
                        else Color.parseColor("#334466")
                    )
                }
                setPadding((16*dp).toInt(), (10*dp).toInt(), (16*dp).toInt(), (10*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { if (idx < labels.size - 1) marginEnd = (8*dp).toInt() }
            }
        }
        fun updateChips() {
            chipViews.forEachIndexed { i, chip ->
                val sel = labels[i] == selectedDurationLabel
                (chip.background as GradientDrawable).apply {
                    setColor(if (sel) Color.parseColor("#3C3CBD") else Color.parseColor("#1E1E38"))
                    setStroke((1*dp).toInt(), if (sel) Color.parseColor("#5C5CF0") else Color.parseColor("#334466"))
                }
            }
        }
        chipViews.forEachIndexed { idx, chip ->
            chip.setOnClickListener { selectedDurationLabel = labels[idx]; updateChips() }
            chipRow.addView(chip)
        }
        root.addView(chipRow)

        // ── Image preview card (hidden until photo attached) ──────────────────
        val homeThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 10*dp
            }
            clipToOutline = true
        }
        val homeImgRemoveBtn = TextView(this).apply {
            text = "✕  Remove photo"; textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#FF8080"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt() }
        }
        val homeImgCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 12*dp
                setColor(Color.parseColor("#1E1E38"))
                setStroke((1*dp).toInt(), Color.parseColor("#33AABBCC"))
            }
            setPadding((10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14*dp).toInt() }
            addView(homeThumb.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (100*dp).toInt()
                )
            })
            addView(homeImgRemoveBtn)
        }
        homeSheetImgCard = homeImgCard
        homeSheetThumb   = homeThumb
        root.addView(homeImgCard)

        // ── Action tiles: 📷 Photo  |  🎤 Voice ──────────────────────────────
        fun makeHomeTile(emoji: String, label: String, bgHex: String, borderHex: String): LinearLayout =
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
                isClickable = true; isFocusable = true
                addView(TextView(this@HomeActivity).apply {
                    text = emoji; textSize = 22f; gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4*dp).toInt() }
                })
                addView(TextView(this@HomeActivity).apply {
                    text = label; textSize = 11f; gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#AABBCC"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            }

        val homeCamTile = makeHomeTile("📷", "Photo", "#1A2040", "#3355AA")
        val homeMicTileView = makeHomeTile(
            if (homeIsListening) "⏹️" else "🎤", "Voice", "#1A2030", "#334466"
        )
        (homeCamTile.layoutParams as LinearLayout.LayoutParams).marginEnd = (8*dp).toInt()
        homeMicTile = homeMicTileView
        homeTopicInput = topicInput

        val tilesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16*dp).toInt() }
            addView(homeCamTile); addView(homeMicTileView)
        }
        root.addView(tilesRow)

        // ── Start button ──────────────────────────────────────────────────────
        val startBtn = TextView(this).apply {
            text = "  Start Lesson  🎓"
            textSize = 16f; gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 24*dp
                setColor(Color.parseColor("#3C3CBD"))
            }
            setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (16*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(startBtn)

        sheet.setContentView(root)

        homeImgRemoveBtn.setOnClickListener {
            homePendingImageBase64 = null
            homePendingImageUri = null
            homeImgCard.visibility = View.GONE
        }

        homeCamTile.setOnClickListener { openHomeCamera() }

        homeMicTileView.setOnClickListener {
            if (homeIsListening) homeVoiceManager.stopListening()
            else homeCheckPermissionAndStartListening()
        }

        startBtn.setOnClickListener {
            val topic = topicInput.text.toString().trim()
            if (topic.isBlank()) {
                Toast.makeText(this, "Please enter a topic first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sheet.dismiss()
            homeSheetImgCard = null; homeSheetThumb = null
            homeTopicInput = null;   homeMicTile = null
            val uid  = SessionManager.getFirestoreUserId(this)
            val lang = SessionManager.getPreferredLang(this).ifBlank { "en-US" }
            val capturedImage = homePendingImageBase64.also {
                homePendingImageBase64 = null
                homePendingImageUri = null
            }
            startActivity(
                Intent(this, BlackboardActivity::class.java)
                    .putExtra(BlackboardActivity.EXTRA_MESSAGE,  topic)
                    .putExtra(BlackboardActivity.EXTRA_DURATION, selectedDurationLabel)
                    .putExtra(BlackboardActivity.EXTRA_SUBJECT,  "General")
                    .putExtra(BlackboardActivity.EXTRA_CHAPTER,  "General")
                    .putExtra(BlackboardActivity.EXTRA_USER_ID,  uid)
                    .putExtra(BlackboardActivity.EXTRA_LANGUAGE_TAG, lang)
                    .apply { if (capturedImage != null) putExtra(BlackboardActivity.EXTRA_IMAGE_BASE64, capturedImage) }
            )
        }

        sheet.setOnShowListener {
            topicInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(topicInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        sheet.show()
    }

    // ── Topic dialog: camera / crop / voice (mirrors FullChatFragment) ───────

    /** Mirrors FullChatFragment.showImageSourceDialog() */
    private fun openHomeCamera() {
        AlertDialog.Builder(this)
            .setTitle("Add Image")
            .setItems(arrayOf("📷  Take Photo", "🖼️  Choose from Gallery")) { _, which ->
                when (which) {
                    0 -> openHomeCameraCapture()
                    1 -> homeGalleryLauncher.launch("image/*")
                }
            }.show()
    }

    /** Mirrors FullChatFragment.openCamera() */
    private fun openHomeCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), 903
            )
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        homeCameraImageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        homeCameraImageUri?.let { homeCameraLauncher.launch(it) }
    }

    /** Mirrors FullChatFragment.launchCrop() — dimension-aware initial crop ratio, same dark theme */
    private fun launchHomeCrop(sourceUri: Uri) {
        val imagesDir = java.io.File(filesDir, "home_images").also { it.mkdirs() }
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
            homeCropLauncher.launch(uCrop.getIntent(this))
        } catch (e: Exception) {
            Log.w("Home", "UCrop launch failed: ${e.message}")
        }
    }

    private fun applyHomeCroppedImage(uri: Uri) {
        homePendingImageUri = uri
        lifecycleScope.launch(Dispatchers.IO) {
            val b64 = homeMediaManager.uriToBase64(uri)
            runOnUiThread {
                if (b64 != null) {
                    homePendingImageBase64 = b64
                    val card  = homeSheetImgCard ?: return@runOnUiThread
                    val thumb = homeSheetThumb   ?: return@runOnUiThread
                    card.visibility = View.VISIBLE
                    Glide.with(this@HomeActivity).load(uri).centerCrop().into(thumb)
                }
            }
        }
    }

    /** Mirrors FullChatFragment.checkPermissionAndStartListening() */
    private fun homeCheckPermissionAndStartListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 904
            )
            return
        }
        homeStartVoiceInput()
    }

    /** Mirrors FullChatFragment.startVoiceInput() */
    private fun homeStartVoiceInput() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        homeIsListening = true
        updateHomeMicTile()
        homeVoiceManager.startListening(object : VoiceRecognitionCallback {
            override fun onResults(text: String) {
                runOnUiThread {
                    homeIsListening = false
                    updateHomeMicTile()
                    if (text.isNotEmpty()) {
                        homeTopicInput?.setText(text)
                        homeTopicInput?.setSelection(text.length)
                    }
                }
            }
            override fun onPartialResults(text: String) {
                runOnUiThread {
                    homeTopicInput?.setText(text)
                    homeTopicInput?.setSelection(text.length)
                }
            }
            override fun onError(error: String) {
                runOnUiThread { homeIsListening = false; updateHomeMicTile() }
            }
            override fun onListeningStarted() {}
            override fun onListeningFinished() {
                runOnUiThread { homeIsListening = false; updateHomeMicTile() }
            }
        }, SessionManager.getPreferredLang(this).ifBlank { "en-US" })
    }

    /** Mirrors FullChatFragment.resetVoiceButton() — updates the mic tile emoji */
    private fun updateHomeMicTile() {
        val tile = homeMicTile ?: return
        val emojiView = tile.getChildAt(0) as? TextView ?: return
        emojiView.text = if (homeIsListening) "⏹️" else "🎤"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            903 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openHomeCameraCapture()
            904 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) homeStartVoiceInput()
        }
    }

    private fun setupDrawer() {
        // Populate drawer header from session
        updateDrawerHeader()

        // ── Role-based visibility ─────────────────────────────────────────────
        AccessGate.applyVisibility(this, findViewById(R.id.drawerItemTeacher),   Feature.TEACHER_DASHBOARD)
        AccessGate.applyVisibility(this, findViewById(R.id.drawerItemTasks),     Feature.TASKS)
        AccessGate.applyVisibility(this, findViewById(R.id.drawerItemJoinSchool),Feature.JOIN_SCHOOL)
        AccessGate.applyVisibility(this, findViewById(R.id.drawerItemProfile),   Feature.USER_PROFILE)
        AccessGate.applyVisibility(this, findViewById(R.id.drawerItemProgress),  Feature.PROGRESS_DASHBOARD)

        // Nav item clicks — close drawer then navigate
        fun navigate(block: () -> Unit) {
            drawerLayout.closeDrawer(GravityCompat.START)
            drawerLayout.postDelayed(block, 200)
        }

        findViewById<LinearLayout>(R.id.drawerHeaderCard).setOnClickListener {
            navigate { startActivity(Intent(this, UserProfileActivity::class.java)) }
        }
        findViewById<LinearLayout>(R.id.drawerItemProfile).setOnClickListener {
            navigate { startActivity(Intent(this, UserProfileActivity::class.java)) }
        }
        findViewById<LinearLayout>(R.id.drawerItemProgress).setOnClickListener {
            navigate {
                val target = if (SessionManager.isParent(this)) ParentDashboardActivity::class.java
                else ProgressDashboardActivity::class.java
                startActivity(Intent(this, target))
            }
        }
        findViewById<LinearLayout>(R.id.drawerItemTeacher).setOnClickListener {
            navigate { startActivity(Intent(this, TeacherDashboardActivity::class.java)) }
        }
        findViewById<LinearLayout>(R.id.drawerItemTasks).setOnClickListener {
            navigate { startActivity(Intent(this, TasksActivity::class.java)) }
        }
        findViewById<LinearLayout>(R.id.drawerItemJoinSchool).setOnClickListener {
            navigate { startActivity(Intent(this, SchoolJoinActivity::class.java)) }
        }
        findViewById<LinearLayout>(R.id.drawerItemPlans).setOnClickListener {
            navigate {
                startActivity(
                    Intent(this, SubscriptionActivity::class.java)
                        .putExtra("schoolId", SessionManager.getSchoolId(this))
                )
            }
        }
        findViewById<LinearLayout>(R.id.drawerItemSignOut).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            drawerLayout.postDelayed({ confirmLogout() }, 200)
        }
    }

    /** Sync drawer header text from SessionManager. */
    private fun updateDrawerHeader() {
        val name  = SessionManager.getStudentName(this)
        val school = SessionManager.getSchoolName(this)
        val plan  = SessionManager.getPlanName(this).let { if (it.isBlank()) "Free" else it }
        findViewById<TextView?>(R.id.drawerName)?.text = name
        findViewById<TextView?>(R.id.drawerSchool)?.text = school
        findViewById<TextView?>(R.id.drawerPlanBadge)?.text = "📋 $plan"
    }

    // ── Offers banner (Firestore-backed) ───────────────────────────────────────

    private val offerAutoScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var offerAutoScrollRunnable: Runnable? = null

    /**
     * Fetches active offers from Firestore [app_offers] collection and populates
     * the horizontal banner with animated, highlighted cards.
     *
     * Firestore document fields: title, subtitle, emoji, background_color,
     * display_order, is_active. See [FirestoreOffer] for full schema.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun loadOffersFromFirestore() {
        FirestoreManager.fetchOffers(
            onSuccess = { offers ->
                if (offers.isEmpty()) return@fetchOffers   // keep static placeholder cards
                val container = findViewById<android.widget.LinearLayout>(R.id.offersBannerContainer)
                    ?: return@fetchOffers
                val scroll = findViewById<android.widget.HorizontalScrollView>(R.id.offersBannerScroll)
                val header = findViewById<android.view.View>(R.id.offersSectionHeader)

                runOnUiThread {
                    container.removeAllViews()
                    stopOfferAutoScroll()

                    // Reveal the scroll view (hidden in XML until real data arrives)
                    scroll?.visibility = android.view.View.VISIBLE

                    // Show the section header with a fade-in
                    header?.apply {
                        visibility = android.view.View.VISIBLE
                        alpha = 0f
                        animate().alpha(1f).setDuration(300).start()
                    }
                    offers.forEachIndexed { index, offer ->
                        val card = buildOfferCard(offer)
                        container.addView(card)

                        // Gentle fade-in with stagger — no bounce, so blackboard CTA stays dominant
                        card.alpha = 0f
                        card.animate()
                            .alpha(1f)
                            .setStartDelay(600L + 120L * index)   // 600 ms head-start for the rest of the screen
                            .setDuration(400)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }

                    // Start auto-scroll after all entry animations complete
                    if (offers.size > 1) {
                        val totalEntryMs = 100L * offers.size + 400L
                        offerAutoScrollHandler.postDelayed({
                            startOfferAutoScroll(scroll, container)
                        }, totalEntryMs)
                    }
                }
            },
            onFailure = { e -> Log.w("HomeActivity", "fetchOffers failed: ${e?.message}") }
        )
    }

    /** Auto-scroll the offers banner every 3 seconds so all cards get seen. */
    private fun startOfferAutoScroll(
        scroll: android.widget.HorizontalScrollView?,
        container: android.widget.LinearLayout?
    ) {
        scroll ?: return
        container ?: return
        var targetX = scroll.scrollX

        offerAutoScrollRunnable = object : Runnable {
            override fun run() {
                if (!scroll.isAttachedToWindow) return
                val maxScroll = container.width - scroll.width
                if (maxScroll <= 0) return   // all cards fit, no need to scroll
                // Advance by ~350dp then wrap
                val cardWidthPx = (270 * resources.displayMetrics.density).toInt()
                targetX += cardWidthPx
                if (targetX > maxScroll) targetX = 0
                scroll.smoothScrollTo(targetX, 0)
                offerAutoScrollHandler.postDelayed(this, 3000)
            }
        }
        offerAutoScrollHandler.postDelayed(offerAutoScrollRunnable!!, 3000)
    }

    private fun stopOfferAutoScroll() {
        offerAutoScrollRunnable?.let { offerAutoScrollHandler.removeCallbacks(it) }
        offerAutoScrollRunnable = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildOfferCard(offer: FirestoreOffer): android.view.View {
        val ctx = this
        val bgColor = runCatching { android.graphics.Color.parseColor(offer.backgroundColor) }
            .getOrDefault(android.graphics.Color.parseColor("#1E2640"))

        // Lighten the bg color slightly for the stroke
        val cardStrokeColor = android.graphics.Color.argb(
            180,
            (android.graphics.Color.red(bgColor) + 70).coerceAtMost(255),
            (android.graphics.Color.green(bgColor) + 70).coerceAtMost(255),
            (android.graphics.Color.blue(bgColor) + 70).coerceAtMost(255)
        )

        // Card with glowing stroke
        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (300 * resources.displayMetrics.density).toInt(),
                (72 * resources.displayMetrics.density).toInt()
            ).also { it.marginEnd = (10 * resources.displayMetrics.density).toInt() }
            radius = 16 * resources.displayMetrics.density
            cardElevation = 6 * resources.displayMetrics.density
            setCardBackgroundColor(bgColor)
            setStrokeColor(cardStrokeColor)
            strokeWidth = (1 * resources.displayMetrics.density).toInt()
            alpha = 0.82f   // slightly faded so blackboard CTA stays dominant
        }

        // Root frame for stacking (inner row + badge)
        val frame = android.widget.FrameLayout(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Gradient overlay (bottom fade to dark, for depth)
        val gradient = android.view.View(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(80, 0, 0, 0))
            )
        }

        // Inner row (text + emoji)
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            val pe = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, pe, 0)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Title + subtitle column
        val textCol = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = android.widget.TextView(ctx).apply {
            text = offer.title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, android.graphics.Color.argb(120, 0, 0, 0))
        }
        textCol.addView(title)
        if (offer.subtitle.isNotBlank()) {
            val sub = android.widget.TextView(ctx).apply {
                text = offer.subtitle
                setTextColor(android.graphics.Color.parseColor("#FFFFFFCC"))
                textSize = 11f
                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            textCol.addView(sub)
        }

        // Emoji icon
        val icon = android.widget.TextView(ctx).apply {
            text = offer.emoji
            textSize = 38f
        }

        row.addView(textCol)
        row.addView(icon)

        // "HOT" badge in top-right corner
        val badge = android.widget.TextView(ctx).apply {
            text = "  ★  "
            textSize = 9f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#AAAACC"))
            val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#2A2D45"))  // muted, not red
                cornerRadius = 20 * resources.displayMetrics.density
                setPadding(0, 0, 0, 0)
            }
            background = badgeBg
            val lp = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            lp.topMargin = (6 * resources.displayMetrics.density).toInt()
            lp.marginEnd = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }

        frame.addView(gradient)
        frame.addView(row)
        frame.addView(badge)
        card.addView(frame)
        return card
    }

    override fun onStop() {
        super.onStop()
        stopOfferAutoScroll()
    }

    private fun setupRecyclerView() {
        subjectsRecyclerView = findViewById(R.id.subjectsRecyclerView)
        subjectAdapter = SubjectAdapter(
            subjects = subjectsList,
            onItemClick = { subject ->
                startActivity(
                    Intent(this, SubjectActivity::class.java)
                        .putExtra("subjectName", subject)
                        .putExtra("subjectId", resolveSubjectId(subject))
                )
            },
            onItemLongClick = { subject -> showDeleteSubjectDialog(subject) }
        )
        subjectsRecyclerView.layoutManager = GridLayoutManager(this, 4)
        subjectsRecyclerView.adapter = subjectAdapter
    }

    /** Maps a display subject name + user grade → Firestore subject_id. */
    private fun resolveSubjectId(subjectName: String): String {
        val rawGrade = SessionManager.getGrade(this).lowercase()
            .replace(" ", "").replace("class", "").replace("grade", "").trim()
        val grade = when {
            rawGrade.endsWith("th") || rawGrade.endsWith("st") ||
            rawGrade.endsWith("nd") || rawGrade.endsWith("rd") -> rawGrade
            rawGrade.isNotEmpty() -> "${rawGrade}th"
            else -> "9th"   // default
        }
        val key = subjectName.lowercase().trim()
        return when {
            key.contains("math")     -> "math_$grade"
            key.contains("science")  -> "science_$grade"
            key.contains("english")  -> "english_$grade"
            key.contains("social") || key.contains("history") || key.contains("geography") -> "social_$grade"
            key.contains("hindi")    -> "hindi_$grade"
            else -> ""  // custom subject — NCERT button stays hidden
        }
    }

    private val defaultSubjects = listOf<String>(
        
    )

    // ── Local SharedPreferences storage (Firestore will be added later) ──────

    private fun subjectsPrefs() =
        getSharedPreferences("subjects_prefs", MODE_PRIVATE)

    private fun saveSubjectsLocally(subjects: List<String>) {
        subjectsPrefs().edit()
            .putString("subjects_list", subjects.joinToString("||||"))
            .apply()
    }

    private fun loadSubjectsLocally(): MutableList<String> {
        val raw = subjectsPrefs().getString("subjects_list", "") ?: ""
        return if (raw.isEmpty()) mutableListOf()
               else raw.split("||||").filter { it.isNotEmpty() }.toMutableList()
    }

    private fun loadSubjects() {
        val saved = loadSubjectsLocally()
        subjectsList.clear()
        if (saved.isEmpty()) {
            subjectsList.addAll(defaultSubjects)
            saveSubjectsLocally(subjectsList)
            // Push defaults to Firestore too
            subjectsList.forEach { FirestoreManager.saveSubject(userId, it) }
        } else {
            subjectsList.addAll(saved)
        }
        subjectAdapter.notifyDataSetChanged()
        updateSubjectCount()
        
        // ALWAYS load from Firestore as fallback/sync (survives app uninstall)
        // This restores subjects even if local SharedPrefs were wiped
        FirestoreManager.loadSubjects(userId,
            onSuccess = { remoteList ->
                val toAdd = remoteList.filter { it !in subjectsList }
                if (toAdd.isNotEmpty()) {
                    subjectsList.addAll(toAdd)
                    saveSubjectsLocally(subjectsList)
                    runOnUiThread {
                        subjectAdapter.notifyDataSetChanged()
                        updateSubjectCount()
                    }
                }
            },
            onFailure = { /* Local list is sufficient; Firestore error is OK */ }
        )
    }

    private fun updateSubjectCount() {
        val count = subjectsList.size
        val text = if (count == 1) "1 subject" else "$count subjects"
        findViewById<TextView?>(R.id.subjectCountText)?.text = text
    }

    private fun showAddSubjectDialog() {
        val options = arrayOf(
            "\ud83d\udcd6 Import from NCERT (auto-add subject + chapters)",
            "\ud83d\udcdd Type subject name manually"
        )
        AlertDialog.Builder(this)
            .setTitle("\ud83d\udcda Add Subject")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNcertSubjectImportDialog()
                    1 -> showManualSubjectDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualSubjectDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Science, Maths, History"
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("\ud83d\udcda Add Subject")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addSubject(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // \u2500\u2500 NCERT subject + chapters auto-import \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    private fun loadNcertJson(): org.json.JSONObject {
        val raw = assets.open("ncert.json").bufferedReader().use { it.readText() }
        return org.json.JSONObject(raw)
    }

    private fun ncertChapterUrl(code: String, chapterNum: Int): String =
        "https://ncert.nic.in/textbook/pdf/${code}${chapterNum.toString().padStart(2, '0')}.pdf"

    private fun showNcertSubjectImportDialog() {
        val ncertRoot = try { loadNcertJson() } catch (e: Exception) {
            Toast.makeText(this, "Could not load NCERT data", Toast.LENGTH_SHORT).show()
            return
        }
        val classNums = (6..12).map { it.toString() }.filter { ncertRoot.has(it) }
        val classLabels = classNums.map { "\ud83c\udf93 Class $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("\ud83d\udcd6 NCERT \u2014 Pick Class")
            .setItems(classLabels) { _, ci ->
                pickNcertSubjectForHome(ncertRoot, classNums[ci])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickNcertSubjectForHome(root: org.json.JSONObject, classNum: String) {
        val classObj = root.optJSONObject(classNum) ?: return
        val subjects = mutableListOf<String>()
        val keys = classObj.keys()
        while (keys.hasNext()) subjects.add(keys.next())
        subjects.sort()
        val labels = subjects.map { "\ud83d\udcd8 $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("\ud83d\udcd6 Class $classNum \u2014 Pick Subject")
            .setItems(labels) { _, si ->
                pickNcertBookForHome(root, classNum, subjects[si])
            }
            .setNegativeButton("\u2190 Back") { _, _ -> showNcertSubjectImportDialog() }
            .show()
    }

    private fun pickNcertBookForHome(root: org.json.JSONObject, classNum: String, subject: String) {
        val booksArr = root.optJSONObject(classNum)?.optJSONArray(subject) ?: return
        if (booksArr.length() == 1) {
            confirmNcertImport(booksArr.getJSONObject(0), classNum, subject)
            return
        }
        val labels = (0 until booksArr.length())
            .map { "\ud83d\udcd7 ${booksArr.getJSONObject(it).getString("text")}" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("\ud83d\udcd6 $subject \u2014 Pick Book")
            .setItems(labels) { _, bi ->
                confirmNcertImport(booksArr.getJSONObject(bi), classNum, subject)
            }
            .setNegativeButton("\u2190 Back") { _, _ -> pickNcertSubjectForHome(root, classNum) }
            .show()
    }

    private fun confirmNcertImport(bookObj: org.json.JSONObject, classNum: String, subject: String) {
        val bookTitle = bookObj.getString("text")
        val code = bookObj.getString("code")
        val chapRange = bookObj.optString("chapters", "1-5")
        val parts = chapRange.split("-")
        val rawStart = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val rawEnd   = parts.getOrNull(1)?.toIntOrNull() ?: 5
        val start = if (rawStart == 0) 1 else rawStart
        val end   = rawEnd
        val chapterCount = end - start + 1

        AlertDialog.Builder(this)
            .setTitle("\ud83d\udcda Add \"$subject\"?")
            .setMessage("Book: $bookTitle\nClass $classNum\n\n$chapterCount chapters will be added automatically with NCERT PDF links.")
            .setPositiveButton("Add Subject + Chapters") { _, _ ->
                // 1. Add the subject if not already present
                val subjectLabel = "$subject (Class $classNum)"
                if (!subjectsList.contains(subjectLabel)) {
                    addSubject(subjectLabel)
                }
                // 2. Save all chapters with NCERT URLs to SharedPreferences + Firestore
                val prefs = getSharedPreferences("chapters_prefs", MODE_PRIVATE)
                val chaptersKey = "chapters_$subjectLabel"
                val existing = prefs.getString(chaptersKey, "") ?: ""
                val chapters = if (existing.isEmpty()) mutableListOf()
                               else existing.split("||||").filter { it.isNotEmpty() }.toMutableList()
                var added = 0
                for (ch in start..end) {
                    val title = "$bookTitle \u2014 Chapter $ch"
                    val url = ncertChapterUrl(code, ch)
                    if (!chapters.contains(title)) {
                        chapters.add(title)
                        prefs.edit().putString(
                            "meta_${subjectLabel}_$title",
                            org.json.JSONObject().apply {
                                put("isPdf", false)
                                put("isNcert", true)
                                put("ncertUrl", url)
                            }.toString()
                        ).apply()
                        FirestoreManager.saveChapter(userId, subjectLabel, title, ncertUrl = url)
                        added++
                    }
                }
                prefs.edit().putString(chaptersKey, chapters.joinToString("||||")).apply()
                Toast.makeText(this,
                    "\u2705 \"$subjectLabel\" added with $added NCERT chapters!",
                    Toast.LENGTH_LONG).show()
                // Auto-navigate into the subject so the user can start immediately
                startActivity(
                    Intent(this, SubjectActivity::class.java)
                        .putExtra("subjectName", subjectLabel)
                        .putExtra("subjectId", resolveSubjectId(subjectLabel))
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteSubjectDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Subject")
            .setMessage("Delete \"$name\" and all its chapters?")
            .setPositiveButton("Delete") { _, _ -> deleteSubject(name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSubject(name: String) {
        subjectsList.add(name)
        saveSubjectsLocally(subjectsList)
        FirestoreManager.saveSubject(userId, name)
        subjectAdapter.notifyItemInserted(subjectsList.size - 1)
        updateSubjectCount()
    }

    private fun deleteSubject(name: String) {
        val idx = subjectsList.indexOf(name)
        if (idx >= 0) {
            subjectsList.removeAt(idx)
            saveSubjectsLocally(subjectsList)
            FirestoreManager.deleteSubject(userId, name)
            subjectAdapter.notifyItemRemoved(idx)
            updateSubjectCount()
        }
    }

    // ── Background update-check result ────────────────────────────────────────
    //  Delivered here when SplashActivity left before the Firestore check finished.

    private fun handleHomeUpdateResult(result: AppUpdateManager.UpdateResult) {
        when (result) {
            is AppUpdateManager.UpdateResult.Maintenance   -> showHomeMaintenanceDialog(result.config)
            is AppUpdateManager.UpdateResult.ForceUpdate   -> {
                homePendingForceUpdate = result
                showHomeForceUpdateDialog(result.config)
            }
            is AppUpdateManager.UpdateResult.OptionalUpdate -> showHomeOptionalUpdateDialog(result.config)
            else -> Unit  // UpToDate / NetworkError — nothing to do
        }
    }

    private fun showHomeForceUpdateDialog(config: AppUpdateConfig) {
        val versionLabel = config.latestVersionName
            .takeIf { it.isNotBlank() }?.let { " (v$it)" } ?: ""
        AlertDialog.Builder(this)
            .setTitle("Update Required")
            .setMessage(
                "${config.updateMessage}\n\n" +
                "You need to update AI Guru$versionLabel to continue."
            )
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ -> openPlayStoreHome(config.updateUrl) }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .show()
    }

    private fun showHomeMaintenanceDialog(config: AppUpdateConfig) {
        val msg = buildString {
            append(config.maintenanceMessage)
            if (config.supportContact.isNotBlank())
                append("\n\nFor assistance: ${config.supportContact}")
        }
        AlertDialog.Builder(this)
            .setTitle("Down for Maintenance")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Close App") { _, _ -> finishAffinity() }
            .show()
    }

    private fun showHomeOptionalUpdateDialog(config: AppUpdateConfig) {
        val title = if (config.latestVersionName.isNotBlank())
            "Update Available — v${config.latestVersionName}" else "Update Available"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(config.updateMessage)
            .setCancelable(true)
            .setPositiveButton("Update") { _, _ -> openPlayStoreHome(config.updateUrl) }
            .setNegativeButton("Later") { _, _ -> }
            .show()
    }

    private fun openPlayStoreHome(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            setPackage("com.android.vending")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }
}
