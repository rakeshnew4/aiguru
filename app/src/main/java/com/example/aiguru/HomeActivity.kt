package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.adapters.SubjectAdapter
import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.models.FirestoreOffer
import com.example.aiguru.utils.ConfigManager
import com.example.aiguru.utils.SchoolTheme
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import android.util.Log
import androidx.core.view.WindowCompat
import java.util.Calendar

class HomeActivity : BaseActivity() {

    private lateinit var subjectsRecyclerView: RecyclerView
    private val subjectsList = mutableListOf<String>()
    private lateinit var subjectAdapter: SubjectAdapter
    private lateinit var userId: String

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

        findViewById<MaterialButton>(R.id.addSubjectButton).setOnClickListener {
            showAddSubjectDialog()
        }
        findViewById<MaterialButton>(R.id.generalChatButton).setOnClickListener {
            startActivity(
                Intent(this, ChatHostActivity::class.java)
                    .putExtra("subjectName", "General")
                    .putExtra("chapterName", "General Chat")
            )
        }
        findViewById<MaterialButton>(R.id.libraryButton).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.progressButton).setOnClickListener {
            startActivity(Intent(this, ProgressDashboardActivity::class.java))
        }
        findViewById<TextView?>(R.id.profileButton)?.setOnClickListener {
            startActivity(Intent(this, UserProfileActivity::class.java))
        }
        findViewById<TextView?>(R.id.planBadgeText)?.setOnClickListener {
            startActivity(
                Intent(this, SubscriptionActivity::class.java)
                    .putExtra("schoolId", SessionManager.getSchoolId(this))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        setupStudentInfo()
    }

    private fun applySchoolBranding() {
        val schoolId = SessionManager.getSchoolId(this)
        val school = ConfigManager.getSchool(this, schoolId)

        // Load school colors into the centralized SchoolTheme singleton
        SchoolTheme.load(school?.branding)
        SchoolTheme.applyStatusBar(window)

        // Header background (LinearLayout — set color directly)
        SchoolTheme.setBackground(findViewById(R.id.homeHeader))

        // Quick-action chips
        SchoolTheme.tintLight(findViewById(R.id.generalChatButton))
        SchoolTheme.tint(findViewById(R.id.addSubjectButton))

        // Chip text colors to match school primary
        val primary = SchoolTheme.primaryColor
        findViewById<MaterialButton?>(R.id.generalChatButton)?.setTextColor(primary)

        // Header text: use white if primary is dark enough, else use primaryDark
        val headerTextColor = android.graphics.Color.WHITE
        findViewById<TextView?>(R.id.greetingText)?.setTextColor(headerTextColor)
        findViewById<TextView?>(R.id.userNameText)?.setTextColor(headerTextColor)
        findViewById<TextView?>(R.id.schoolNameSubtitle)?.setTextColor(
            android.graphics.Color.parseColor("#FFFFFFCC"))
        findViewById<TextView?>(R.id.planBadgeText)?.setTextColor(headerTextColor)
    }

    private fun setupStudentInfo() {
        val studentName = SessionManager.getStudentName(this)
        val schoolName = SessionManager.getSchoolName(this)
        val planName = SessionManager.getPlanName(this)

        findViewById<TextView?>(R.id.userNameText)?.text = studentName
        // Show school name as subtitle if view exists
        findViewById<TextView?>(R.id.schoolNameSubtitle)?.text = schoolName
        // Show plan badge if view exists
        if (planName.isNotBlank()) {
            findViewById<TextView?>(R.id.planBadgeText)?.apply {
                text = "📋 $planName"
                visibility = android.view.View.VISIBLE
            }
        }
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

    private fun showProfileDialog() {
        val studentName = SessionManager.getStudentName(this)
        val studentId = SessionManager.getStudentId(this)
        val schoolName = SessionManager.getSchoolName(this)
        val planName = SessionManager.getPlanName(this).ifBlank { "No plan selected" }
        val userId = SessionManager.getStudentId(this)

        // Load fresh token breakdown from Firestore
        FirestoreManager.getUserMetadata(userId, onSuccess = { meta ->
            val totalToday  = meta?.tokensToday ?: 0
            val inToday     = meta?.inputTokensToday ?: 0
            val outToday    = meta?.outputTokensToday ?: 0
            val totalMonth  = meta?.tokensThisMonth ?: 0
            val inMonth     = meta?.inputTokensThisMonth ?: 0
            val outMonth    = meta?.outputTokensThisMonth ?: 0

            val tokenInfo = buildString {
                append("\nToday:  $totalToday tokens")
                if (inToday > 0 || outToday > 0) append(" (in $inToday / out $outToday)")
                append("\nMonth: $totalMonth tokens")
                if (inMonth > 0 || outMonth > 0) append(" (in $inMonth / out $outMonth)")
            }

            AlertDialog.Builder(this)
                .setTitle("👤 $studentName")
                .setMessage(
                    "School: $schoolName\n" +
                    "Student ID: $studentId\n" +
                    "Plan: $planName\n" +
                    tokenInfo
                )
                .setPositiveButton("Change Plan") { _, _ ->
                    startActivity(
                        Intent(this, SubscriptionActivity::class.java)
                            .putExtra("schoolId", SessionManager.getSchoolId(this))
                    )
                }
                .setNeutralButton("Logout") { _, _ -> confirmLogout() }
                .setNegativeButton("Close", null)
                .show()
        }, onFailure = {
            // Fallback: show dialog without token info
            AlertDialog.Builder(this)
                .setTitle("👤 $studentName")
                .setMessage(
                    "School: $schoolName\n" +
                    "Student ID: $studentId\n" +
                    "Plan: $planName"
                )
                .setPositiveButton("Change Plan") { _, _ ->
                    startActivity(
                        Intent(this, SubscriptionActivity::class.java)
                            .putExtra("schoolId", SessionManager.getSchoolId(this))
                    )
                }
                .setNeutralButton("Logout") { _, _ -> confirmLogout() }
                .setNegativeButton("Close", null)
                .show()
        })
    }

    private fun confirmLogout() {        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                SessionManager.logout(this)
                com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Offers banner (Firestore-backed) ───────────────────────────────────────

    /**
     * Fetches active offers from Firestore [app_offers] collection and populates
     * the horizontal banner.  Falls back silently to the static XML cards on failure.
     *
     * Firestore document fields: title, subtitle, emoji, background_color,
     * display_order, is_active. See [FirestoreOffer] for full schema.
     */
    private fun loadOffersFromFirestore() {
        FirestoreManager.fetchOffers(
            onSuccess = { offers ->
                if (offers.isEmpty()) return@fetchOffers   // keep static placeholder cards
                val container = findViewById<android.widget.LinearLayout>(R.id.offersBannerContainer)
                container.removeAllViews()
                offers.forEach { offer -> container.addView(buildOfferCard(offer)) }
            },
            onFailure = { /* silent — static XML card remain */ }
        )
    }

    private fun buildOfferCard(offer: FirestoreOffer): android.view.View {
        val ctx = this
        val bgColor = runCatching { android.graphics.Color.parseColor(offer.backgroundColor) }
            .getOrDefault(android.graphics.Color.parseColor("#1A1A2E"))

        // Card
        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (260 * resources.displayMetrics.density).toInt(),
                (96 * resources.displayMetrics.density).toInt()
            ).also { it.marginEnd = (10 * resources.displayMetrics.density).toInt() }
            radius = 14 * resources.displayMetrics.density
            cardElevation = 3 * resources.displayMetrics.density
            setCardBackgroundColor(bgColor)
        }

        // Inner row
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            val pe = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, pe, 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
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
//            lineSpacingExtra = 2 * resources.displayMetrics.density
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
            textSize = 36f
        }

        row.addView(textCol)
        row.addView(icon)
        card.addView(row)
        return card
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
        "Mathematics", "Science", "Computer", "English", "History", "Geography"
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
        // Restore from Firestore if local was empty (e.g. fresh install)
        if (saved.isEmpty()) return
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
            }
        )
    }

    private fun updateSubjectCount() {
        val count = subjectsList.size
        val text = if (count == 1) "1 subject" else "$count subjects"
        findViewById<TextView?>(R.id.subjectCountText)?.text = text
    }

    private fun showAddSubjectDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Science, Maths, History"
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("📚 Add Subject")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addSubject(name)
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
}