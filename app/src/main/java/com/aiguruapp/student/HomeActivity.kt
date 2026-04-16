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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.adapters.SubjectAdapter
import com.aiguruapp.student.BuildConfig
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.config.PlanEnforcer
import com.aiguruapp.student.models.AppUpdateConfig
import com.aiguruapp.student.utils.AppUpdateBus
import com.aiguruapp.student.utils.AppUpdateManager
import com.aiguruapp.student.models.UserMetadata
import com.aiguruapp.student.utils.ConfigManager
import com.aiguruapp.student.utils.SchoolTheme
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.FirestoreOffer
import com.google.firebase.firestore.FirebaseFirestore
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.Calendar

class HomeActivity : BaseActivity() {

    private lateinit var subjectsRecyclerView: RecyclerView
    private val subjectsList = mutableListOf<String>()
    private lateinit var subjectAdapter: SubjectAdapter
    private lateinit var userId: String
    private var homePendingForceUpdate: AppUpdateManager.UpdateResult.ForceUpdate? = null
    private lateinit var drawerLayout: DrawerLayout

    // Drawer quota views — updated by loadQuotaStrip
    private var drawerChatLimit: Int  = 0
    private var drawerBbLimit: Int    = 0
    private var drawerVoiceLimit: Int = 0

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

        applySchoolBranding()
        setupGreeting()
        setupStudentInfo()
        userId = SessionManager.getFirestoreUserId(this)
        setupRecyclerView()
        loadSubjects()
        loadOffersFromFirestore()

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.homeSwipeRefresh)
        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary), getColor(R.color.colorSecondary))
        swipeRefresh.setOnRefreshListener {
            loadSubjects()
            loadQuotaStrip()
            loadOffersFromFirestore()
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

        // Plan badge still navigates to subscription
        findViewById<TextView?>(R.id.planBadgeText)?.setOnClickListener {
            startActivity(
                Intent(this, SubscriptionActivity::class.java)
                    .putExtra("schoolId", SessionManager.getSchoolId(this))
            )
        }

        setupDrawer()
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

        // Step 1: fetch live user counters from users_table
        db.collection("users_table").document(uid).get()
            .addOnSuccessListener { userDoc ->
                if (!userDoc.exists()) return@addOnSuccessListener

                val chatToday = userDoc.getLong("chat_questions_today")?.toInt() ?: 0
                val bbToday   = userDoc.getLong("bb_sessions_today")?.toInt() ?: 0
                val aiTtsCharsUsedToday = userDoc.getLong("ai_tts_chars_used_today")?.toInt() ?: 0
                val updatedAt = userDoc.getLong("questions_updated_at") ?: 0L
                val aiTtsUpdatedAt = userDoc.getLong("ai_tts_updated_at") ?: 0L
                val planId    = (userDoc.getString("planId") ?: "").ifBlank { "free" }

                // Only count today's usage if the counters are from the current UTC day
                val isSameDay = updatedAt > 0L && !PlanEnforcer.isNewQuotaDay(updatedAt)
                val aiTtsSameDay = aiTtsUpdatedAt > 0L && !PlanEnforcer.isNewQuotaDay(aiTtsUpdatedAt)
                val chatUsed  = if (isSameDay) chatToday else 0
                val bbUsed    = if (isSameDay) bbToday else 0
                val aiTtsCharsUsed = if (aiTtsSameDay) aiTtsCharsUsedToday else 0

                // Step 2: fetch plan limits directly from plans/ collection
                db.collection("plans").document(planId).get()
                    .addOnSuccessListener { planDoc ->
                        @Suppress("UNCHECKED_CAST")
                        val limitsMap = planDoc.get("limits") as? Map<String, Any>
                        val chatLimit = (limitsMap?.get("daily_chat_questions") as? Long)?.toInt() ?: 0
                        val bbLimit   = (limitsMap?.get("daily_bb_sessions") as? Long)?.toInt() ?: 0
                        val aiTtsQuotaChars = (limitsMap?.get("ai_tts_quota_chars") as? Long)?.toInt() ?: 0

                        // Store limits so progress bars can show correct percentages
                        drawerChatLimit  = chatLimit
                        drawerBbLimit    = bbLimit
                        drawerVoiceLimit = aiTtsQuotaChars

                        val chatLeft = if (chatLimit <= 0) -1 else (chatLimit - chatUsed).coerceAtLeast(0)
                        val bbLeft   = if (bbLimit   <= 0) -1 else (bbLimit   - bbUsed).coerceAtLeast(0)
                        val aiTtsCharsLeft = if (aiTtsQuotaChars <= 0) -1 else (aiTtsQuotaChars - aiTtsCharsUsed).coerceAtLeast(0)

                        runOnUiThread { updateQuotaStripUI(chatLeft, bbLeft, aiTtsCharsLeft) }
                    }
                    .addOnFailureListener {
                        AdminConfigRepository.resolveEffectiveLimitsAsync(planId, null) { limits ->
                            drawerChatLimit  = limits.dailyChatQuestions
                            drawerBbLimit    = limits.dailyBlackboardSessions
                            val chatLimit2 = if (limits.dailyChatQuestions  <= 0) -1 else (limits.dailyChatQuestions  - chatUsed).coerceAtLeast(0)
                            val bbLimit2   = if (limits.dailyBlackboardSessions <= 0) -1 else (limits.dailyBlackboardSessions - bbUsed).coerceAtLeast(0)
                            runOnUiThread { updateQuotaStripUI(chatLimit2, bbLimit2, 0) }
                        }
                    }
            }
    }

    /**
     * Update the usage section in the left navigation drawer.
     * @param chatLeft  remaining chat questions today (-1 = unlimited)
     * @param bbLeft    remaining blackboard sessions today (-1 = unlimited)
     * @param aiTtsCharsLeft remaining AI TTS chars today (-1 = unlimited / 0 = no quota)
     */
    private fun updateQuotaStripUI(chatLeft: Int, bbLeft: Int, aiTtsCharsLeft: Int) {
        // Chat
        val chatText = if (chatLeft < 0) "∞" else "$chatLeft left"
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

        // Blackboard
        val bbText = if (bbLeft < 0) "∞" else "$bbLeft left"
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

        // AI Voice credits
        val voiceRow = findViewById<LinearLayout?>(R.id.drawerVoiceRow)
        if (aiTtsCharsLeft != 0) {
            voiceRow?.visibility = View.VISIBLE
            val voiceText = if (aiTtsCharsLeft < 0) "∞" else "$aiTtsCharsLeft chars left"
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
            android.graphics.Color.parseColor("#1d87e3"),  // blue
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

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private fun setupDrawer() {
        // Populate drawer header from session
        updateDrawerHeader()

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
            navigate { startActivity(Intent(this, ProgressDashboardActivity::class.java)) }
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
                        animate().alpha(1f).setDuration(400).start()
                    }
                    offers.forEachIndexed { index, offer ->
                        val card = buildOfferCard(offer)
                        container.addView(card)

                        // Entry: scale-up + fade-in with stagger (spring-like overshoot)
                        card.scaleX = 0.75f
                        card.scaleY = 0.75f
                        card.alpha = 0f
                        card.animate()
                            .scaleX(1.05f).scaleY(1.05f)
                            .alpha(1f)
                            .setStartDelay(100L * index)
                            .setDuration(280)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .withEndAction {
                                card.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .setDuration(120)
                                    .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                                    .start()
                            }
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
                // Advance by ~270dp then wrap
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
            .getOrDefault(android.graphics.Color.parseColor("#1A1A2E"))

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
                (268 * resources.displayMetrics.density).toInt(),
                (104 * resources.displayMetrics.density).toInt()
            ).also { it.marginEnd = (10 * resources.displayMetrics.density).toInt() }
            radius = 16 * resources.displayMetrics.density
            cardElevation = 6 * resources.displayMetrics.density
            setCardBackgroundColor(bgColor)
            setStrokeColor(cardStrokeColor)
            strokeWidth = (2 * resources.displayMetrics.density).toInt()
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
            text = "  ★ NEW  "
            textSize = 9f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E53935"))
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

    private val defaultSubjects = listOf(
        "My Subject"
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