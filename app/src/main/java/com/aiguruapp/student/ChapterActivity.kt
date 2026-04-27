package com.aiguruapp.student

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aiguruapp.student.adapters.PageListAdapter
import com.aiguruapp.student.utils.ChapterMetricsTracker
import com.aiguruapp.student.utils.PdfPageManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

import com.aiguruapp.student.widget.BoxSpinnerView

class ChapterActivity : BaseActivity() {

    private lateinit var pagesRecyclerView: RecyclerView
    private val pagesListData = mutableListOf<String>()
    private val imagePagePaths = mutableListOf<String>()
    private lateinit var pageListAdapter: PageListAdapter
    private lateinit var subjectName: String
    private lateinit var chapterName: String
    private var cameraImageUri: Uri? = null
    private val CAMERA_PERMISSION_CODE = 201

    /** SharedPreferences key for tracking which pages have been opened in this chapter. */
    private val openedPagesPrefsKey get() =
        "opened_pages_${subjectName}_${chapterName}".replace(" ", "_").take(80)

    /** Load the set of opened page indices (0-based) from local storage. */
    private fun loadOpenedPages(): MutableSet<Int> {
        val raw = getSharedPreferences("page_tracker", MODE_PRIVATE)
            .getString(openedPagesPrefsKey, "") ?: ""
        return if (raw.isBlank()) mutableSetOf()
        else raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
    }

    /** Mark a page as opened, persist it, and refresh the adapter indicator. */
    private fun markPageOpened(position: Int) {
        val set = loadOpenedPages()
        if (set.add(position)) {
            getSharedPreferences("page_tracker", MODE_PRIVATE)
                .edit().putString(openedPagesPrefsKey, set.joinToString(",")).apply()
            pageListAdapter.openedPages = set
        }
    }

    // PDF chapter state
    private var isPdfChapter = false
    private var pdfAssetPath = ""
    private var pdfId = ""
    private var pdfPageCount = 0
    private lateinit var pdfPageManager: PdfPageManager
    private lateinit var metricsTracker: ChapterMetricsTracker

    // Download overlay views
    private lateinit var downloadOverlay: android.widget.LinearLayout
    private lateinit var downloadSpinner: BoxSpinnerView
    private lateinit var downloadStatusText: android.widget.TextView
    private lateinit var downloadSubText: android.widget.TextView

    // NCERT download-and-render state
    private var ncertUrl = ""
    private var ncertCode = ""
    private var ncertChapterNum = 0
    private val ncertPdfId
        get() = "ncert_${subjectName}_${chapterName}"
            .replace(" ", "_").replace("/", "_").take(60)

    // Swipe-to-switch-tabs gesture
    private lateinit var swipeGestureDetector: GestureDetector

    /** OkHttp client for NCERT downloads — browser-like User-Agent + TLS 1.2/1.3 fallback.
     *  ncert.nic.in runs on older NIC servers that sometimes fail the default TLS handshake. */
    private val ncertHttpClient: OkHttpClient by lazy {
        val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .connectionSpecs(listOf(tlsSpec, ConnectionSpec.CLEARTEXT))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { savePage(it.toString()) }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) savePage(cameraImageUri.toString())
        }

    /** Launched when the user taps "View" on a PDF page; receives the Ask AI result back. */
    private val pageViewerForChapterLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val path = result.data?.getStringExtra("pdfPageFilePath") ?: return@registerForActivityResult
                val page = result.data?.getIntExtra("pdfPageNumber", 1) ?: 1
                val fragment = getOrCreateChatFragment()
                switchToChat()
                fragment.attachPdfPage(path, page)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter)

        pdfPageManager = PdfPageManager(this)

        subjectName = intent.getStringExtra("subjectName") ?: "Subject"
        chapterName = intent.getStringExtra("chapterName") ?: "Chapter"

        metricsTracker = ChapterMetricsTracker(subjectName, chapterName)

        downloadOverlay    = findViewById(R.id.downloadOverlay)
        downloadSpinner    = findViewById(R.id.downloadSpinner)
        downloadStatusText = findViewById(R.id.downloadStatusText)
        downloadSubText    = findViewById(R.id.downloadSubText)

        findViewById<TextView>(R.id.chapterTitle).text = chapterName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        // Generate button — always visible in header
        findViewById<MaterialButton>(R.id.generateButton).setOnClickListener {
            showGenerateOptions()
        }

        // Quiz button — launches AI-powered quiz for this chapter
        findViewById<MaterialButton>(R.id.startQuizButton).setOnClickListener {
            val chapterId = "${subjectName}_${chapterName}"
                .replace(" ", "_")
                .lowercase()
                .take(64)
            startActivity(
                Intent(this, QuizSetupActivity::class.java)
                    .putExtra("subjectName",  subjectName)
                    .putExtra("chapterId",    chapterId)
                    .putExtra("chapterTitle", chapterName)
            )
        }

        // Set up RecyclerView with PageListAdapter
        pagesRecyclerView = findViewById(R.id.pagesList)
        pageListAdapter = PageListAdapter(
            pages = pagesListData,
            onView = { position -> onViewPage(position) },
            onAsk  = { position -> onAskPage(position) }
        )
        pagesRecyclerView.layoutManager = LinearLayoutManager(this)
        pagesRecyclerView.adapter = pageListAdapter
        // Restore opened-page indicators immediately
        pageListAdapter.openedPages = loadOpenedPages()

        loadChapterType()
        loadMasteryScore()
        setupTabs()
        setupSwipeToSwitchTabs()

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.chapterSwipeRefresh)
        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary), getColor(R.color.colorSecondary))
        swipeRefresh.setOnRefreshListener {
            pagesListData.clear()
            pageListAdapter.notifyDataSetChanged()
            loadChapterType()
            loadMasteryScore()
            swipeRefresh.isRefreshing = false
        }
    }

    // ─── Swipe left/right to switch Pages ↔ Chat tabs ────────────────────────

    private fun setupSwipeToSwitchTabs() {
        swipeGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                private val SWIPE_MIN_DISTANCE = 80
                private val SWIPE_MIN_VELOCITY = 200
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dX = e2.x - e1.x
                    val dY = e2.y - e1.y
                    // Only treat as horizontal swipe when X dominates Y
                    if (Math.abs(dX) < Math.abs(dY) * 1.5f) return false
                    if (Math.abs(dX) < SWIPE_MIN_DISTANCE) return false
                    if (Math.abs(velocityX) < SWIPE_MIN_VELOCITY) return false

                    val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
                    val currentTab = tabLayout.selectedTabPosition
                    if (dX < 0 && currentTab == 0) {
                        // swipe left → go to Chat
                        tabLayout.getTabAt(1)?.select()
                        return true
                    }
                    if (dX > 0 && currentTab == 1) {
                        // swipe right → go to Pages
                        tabLayout.getTabAt(0)?.select()
                        return true
                    }
                    return false
                }
            }
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun setupTabs() {
        val tabLayout      = findViewById<TabLayout>(R.id.tabLayout)
        val savedContent   = findViewById<View>(R.id.savedContent)
        val pagesContent   = findViewById<View>(R.id.pagesContent)
        val chatContainer  = findViewById<View>(R.id.chatTabContainer)

        tabLayout.addTab(tabLayout.newTab().setText("�  Pages"))
        tabLayout.addTab(tabLayout.newTab().setText("💬  Chat"))
        tabLayout.addTab(tabLayout.newTab().setText("📌  BB Sessions"))

        fun showOnly(visible: View) {
            listOf(savedContent, pagesContent, chatContainer).forEach {
                it.visibility = if (it === visible) View.VISIBLE else View.GONE
            }
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showOnly(pagesContent)
                    1 -> {
                        showOnly(chatContainer)
                        getOrCreateChatFragment()
                    }
                    2 -> {
                        showOnly(savedContent)
                        loadSavedTabIfNeeded()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Start on Chat tab (index 1)
        showOnly(chatContainer)
        getOrCreateChatFragment()
        tabLayout.getTabAt(1)?.select()
    }

    // ─── Saved tab ────────────────────────────────────────────────────────────

    private var savedTabLoaded = false

    private fun loadSavedTabIfNeeded() {
        if (savedTabLoaded) return
        savedTabLoaded = true
        setupSavedSubTabs()
        loadSavedBbSessions()
    }

    private fun setupSavedSubTabs() {
        val subTabs     = findViewById<TabLayout>(R.id.savedSubTabLayout)
        val bbPanel     = findViewById<View>(R.id.savedBbPanel)
        val notesPanel  = findViewById<View>(R.id.savedNotesPanel)

        subTabs.addTab(subTabs.newTab().setText("🎓 BB Sessions"))
        subTabs.addTab(subTabs.newTab().setText("📋 Notes"))

        bbPanel.visibility    = View.VISIBLE
        notesPanel.visibility = View.GONE

        subTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position == 0) {
                    bbPanel.visibility    = View.VISIBLE
                    notesPanel.visibility = View.GONE
                } else {
                    bbPanel.visibility    = View.GONE
                    notesPanel.visibility = View.VISIBLE
                    loadSavedNotesIfNeeded()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // BB sessions inside the Saved tab
    private val savedBbSessions = mutableListOf<Map<String, Any>>()
    private var savedBbAdapter: com.aiguruapp.student.adapters.SavedBbMiniAdapter? = null

    private fun loadSavedBbSessions() {
        val bbList    = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.savedBbList)
        val bbLoading = findViewById<android.widget.ProgressBar>(R.id.savedBbLoading)
        val bbEmpty   = findViewById<View>(R.id.savedBbEmpty)

        bbLoading.visibility = View.VISIBLE
        bbList.visibility    = View.GONE
        bbEmpty.visibility   = View.GONE

        val userId = com.aiguruapp.student.utils.SessionManager.getFirestoreUserId(this)
        com.aiguruapp.student.firestore.FirestoreManager.loadBbSessions(
            userId  = userId,
            subject = subjectName,
            chapter = chapterName,
            onSuccess = { list ->
                bbLoading.visibility = View.GONE
                savedBbSessions.clear()
                savedBbSessions.addAll(list)
                if (savedBbAdapter == null) {
                    savedBbAdapter = com.aiguruapp.student.adapters.SavedBbMiniAdapter(
                        sessions = savedBbSessions,
                        onReplay = { session ->
                            val topic     = session["topic"] as? String ?: return@SavedBbMiniAdapter
                            val sessionId = session["session_id"] as? String ?: session["id"] as? String ?: ""
                            val convId    = session["conversation_id"] as? String
                            val msgId     = session["message_id"] as? String
                            @Suppress("UNCHECKED_CAST")
                            val ttsKeys = (session["tts_keys"] as? List<String>) ?: emptyList()
                            startActivity(
                                android.content.Intent(this, BlackboardActivity::class.java).apply {
                                    putExtra(BlackboardActivity.EXTRA_MESSAGE, topic)
                                    putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
                                    putExtra(BlackboardActivity.EXTRA_SUBJECT, subjectName)
                                    putExtra(BlackboardActivity.EXTRA_CHAPTER, chapterName)
                                    putExtra(BlackboardActivity.EXTRA_IS_REPLAY, true)
                                    // Pass sessionId so steps load from Firestore — no LLM re-generation
                                    if (sessionId.isNotBlank()) putExtra(BlackboardActivity.EXTRA_SESSION_ID, sessionId)
                                    if (ttsKeys.isNotEmpty()) putExtra(BlackboardActivity.EXTRA_TTS_KEYS, ArrayList(ttsKeys))
                                    if (!convId.isNullOrBlank()) putExtra(BlackboardActivity.EXTRA_CONVERSATION_ID, convId)
                                    if (!msgId.isNullOrBlank())  putExtra(BlackboardActivity.EXTRA_MESSAGE_ID, msgId)
                                }
                            )
                        }
                    )
                    bbList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
                    bbList.adapter = savedBbAdapter
                } else {
                    savedBbAdapter!!.notifyDataSetChanged()
                }
                if (list.isEmpty()) {
                    bbEmpty.visibility = View.VISIBLE
                    bbList.visibility  = View.GONE
                } else {
                    bbEmpty.visibility = View.GONE
                    bbList.visibility  = View.VISIBLE
                }
            },
            onFailure = {
                bbLoading.visibility = View.GONE
                bbEmpty.visibility   = View.VISIBLE
            }
        )
    }

    // Notes inside the Saved tab
    private var notesTabLoaded = false

    private fun loadSavedNotesIfNeeded() {
        // Always re-read notes (SharedPreferences is instant) so freshly generated notes show up
        notesTabLoaded = true
        val notesLoading    = findViewById<android.widget.ProgressBar>(R.id.notesLoading)
        val notesScroll     = findViewById<View>(R.id.notesScrollView)
        val notesEmpty      = findViewById<View>(R.id.notesEmpty)
        val notesContainer  = findViewById<android.widget.LinearLayout>(R.id.notesCardsContainer)

        notesLoading.visibility = View.VISIBLE
        notesScroll.visibility  = View.GONE
        notesEmpty.visibility   = View.GONE

        val userId = com.aiguruapp.student.utils.SessionManager.getFirestoreUserId(this)
        val repo = com.aiguruapp.student.chat.NotesRepository(this, userId, subjectName, chapterName)
        val notesList = repo.loadAllTyped()

        notesLoading.visibility = View.GONE
        if (notesList.isEmpty()) {
            notesEmpty.visibility = View.VISIBLE
            findViewById<View>(R.id.generateNotesBtn)?.setOnClickListener { showGenerateOptions() }
            return
        }

        // Build per-section cards with Markwon markdown rendering + edit button
        val markwon = io.noties.markwon.Markwon.builder(this)
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(this))
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .build()
        val dp = resources.displayMetrics.density

        notesContainer.removeAllViews()

        notesList.forEach { (type, heading, content) ->
            // Section card wrapper
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (12 * dp).toInt() }
                layoutParams = lp
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
                (background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = 12 * dp
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12 * dp
                    setColor(android.graphics.Color.parseColor("#1A1A2E"))
                    setStroke((1 * dp).toInt(), android.graphics.Color.parseColor("#2A2A50"))
                }
            }

            // Header row: heading text + Edit button
            val headerRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            val headingView = android.widget.TextView(this).apply {
                text = heading
                textSize = 14f
                android.graphics.Typeface.DEFAULT_BOLD.let { setTypeface(it) }
                setTextColor(android.graphics.Color.parseColor("#C0A0FF"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val editBtn = android.widget.TextView(this).apply {
                text = "✏️ Edit"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#8899CC"))
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16 * dp
                    setColor(android.graphics.Color.parseColor("#22223A"))
                }
            }
            // Content view rendered with Markwon
            val contentView = android.widget.TextView(this).apply {
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#D0D8E8"))
                setLineSpacing(0f, 1.5f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            markwon.setMarkdown(contentView, content)

            editBtn.setOnClickListener {
                // Edit dialog: show raw markdown in an EditText
                val editInput = android.widget.EditText(this).apply {
                    setText(content)
                    minLines = 8
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    setTextColor(android.graphics.Color.parseColor("#E0E8F0"))
                    setBackgroundColor(android.graphics.Color.parseColor("#0D0D1A"))
                    setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Edit: $heading")
                    .setView(editInput)
                    .setPositiveButton("💾 Save") { _, _ ->
                        val edited = editInput.text.toString().trim()
                        if (edited.isNotBlank()) {
                            repo.save(edited, type)
                            // Refresh this card's content view inline
                            markwon.setMarkdown(contentView, edited)
                            android.widget.Toast.makeText(this, "Notes saved", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNeutralButton("🗑️ Delete section") { _, _ ->
                        repo.delete(type)
                        notesContainer.removeView(card)
                        if (notesContainer.childCount == 0) {
                            notesScroll.visibility = View.GONE
                            notesEmpty.visibility  = View.VISIBLE
                            findViewById<View>(R.id.generateNotesBtn)?.setOnClickListener { showGenerateOptions() }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            headerRow.addView(headingView)
            headerRow.addView(editBtn)
            card.addView(headerRow)
            card.addView(contentView)
            notesContainer.addView(card)
        }

        notesScroll.visibility = View.VISIBLE
    }

    /** Switch programmatically to the Chat tab (called by Ask AI buttons in the pages view). */
    fun switchToChat() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.getTabAt(1)?.select()
    }

    /** Returns the existing FullChatFragment or creates it synchronously if not yet loaded. */
    private fun getOrCreateChatFragment(): FullChatFragment {
        val existing = supportFragmentManager.findFragmentByTag("chat_tab") as? FullChatFragment
        if (existing != null) return existing
        val fragment = FullChatFragment.newInstance(subjectName, chapterName)
        supportFragmentManager.beginTransaction()
            .replace(R.id.chatTabContainer, fragment, "chat_tab")
            .commitNow()
        return fragment
    }

    override fun onStop() {
        super.onStop()
        metricsTracker.endSession(this, pdfPageCount)
    }

    override fun onResume() {
        super.onResume()
        // Reload BB sessions in case user just saved one in BlackboardActivity
        loadSavedBbSessions()
        // Reset notes tab so next visit re-reads freshly saved content
        notesTabLoaded = false
    }

    private fun loadMasteryScore() {
        // Mastery data will be loaded from Firestore when re-enabled
        findViewById<View>(R.id.masteryCard).visibility = View.GONE
    }

    // ─── Generate notes (moved from Notes button menu) ────────────────────────

    private fun showGenerateOptions() {
        val options = mutableListOf(
            "📖 Generate Chapter Notes",
            "✏️ Generate Exercise Notes"
        )
        if (isPdfChapter && pdfPageCount > 0) {
            options.add(1, "📄 Generate Page-wise Notes")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("✨ Generate for $chapterName")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "📖 Generate Chapter Notes"   -> generateChapterNotes()
                    "📄 Generate Page-wise Notes"  -> showPageWiseNotesPicker()
                    "✏️ Generate Exercise Notes"   -> generateExerciseNotes()
                }
            }
            .show()
    }

    private fun showNotesOptions() {
        showGenerateOptions()
    }

    private fun generateChapterNotes() {
        val fragment = getOrCreateChatFragment()
        switchToChat()
        fragment.sendAutoPrompt(
            "Create comprehensive study notes for \"$chapterName\" with:\n" +
            "• Key concepts and definitions\n" +
            "• Important facts to remember\n" +
            "• Summary of main points\n" +
            "• Any formulas or rules to know\n\n" +
            "Format clearly with ## headings and bullet points.",
            notesType = "chapter"
        )
    }

    private fun showPageWiseNotesPicker() {
        val pageOptions = (1..pdfPageCount).map { "Page $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Page for Notes")
            .setItems(pageOptions) { _, idx -> generateNotesForPage(idx + 1) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateNotesForPage(pageNum: Int) {
        val fragment = getOrCreateChatFragment()
        switchToChat()
        fragment.sendAutoPrompt(
            "Create detailed study notes for Page $pageNum of \"$chapterName\":\n" +
            "• Key concepts and definitions on this page\n" +
            "• Any formulas, rules, or special points\n" +
            "• Summary of content on this page\n\n" +
            "Use ## Page $pageNum Notes as the heading and format with bullet points.",
            notesType = "page_$pageNum"
        )
    }

    private fun generateExerciseNotes() {
        val fragment = getOrCreateChatFragment()
        switchToChat()
        fragment.sendAutoPrompt(
            "For the exercises in \"$chapterName\":\n" +
            "• List the types of exercises/problems in this chapter\n" +
            "• Provide step-by-step problem-solving strategies\n" +
            "• Show worked examples for typical questions\n" +
            "• Highlight common mistakes to avoid\n\n" +
            "Use ## Exercise Notes as the heading.",
            notesType = "exercises"
        )
    }

    private fun viewSavedNotes() {
        // Navigate to Saved tab → Notes sub-tab
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.getTabAt(2)?.select()
        val subTabs = findViewById<TabLayout>(R.id.savedSubTabLayout)
        subTabs.getTabAt(1)?.select()
    }

    private fun viewSavedBbSessions() {
        // Navigate to Saved tab → BB Sessions sub-tab
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.getTabAt(2)?.select()
    }

    // ─── Chapter type detection ───────────────────────────────────────────────

    private fun loadChapterType() {
        val meta = getSharedPreferences("chapters_prefs", MODE_PRIVATE)
            .getString("meta_${subjectName}_${chapterName}", null)
        if (meta != null) {
            try {
                val json = org.json.JSONObject(meta)

                // NCERT chapter — open via NcertViewerActivity
                if (json.optBoolean("isNcert", false)) {
                    val ncertUrl = json.optString("ncertUrl", "")
                    ncertCode = json.optString("ncertCode", "")
                    ncertChapterNum = json.optInt("ncertChapterNum", 0)
                    setupNcertChapter(ncertUrl)
                    return
                }

                isPdfChapter = json.optBoolean("isPdf", false)
                if (isPdfChapter) {
                    pdfAssetPath = json.optString("pdfAssetPath", "")
                    pdfId        = json.optString("pdfId", "")
                    setupPdfChapter()
                    return
                }
            } catch (_: Exception) { }
        }
//        setupImageChapter()
    }

    // ─── NCERT chapter ────────────────────────────────────────────────────────

    private fun showDownloadOverlay(statusText: String, subText: String = "") {
        downloadStatusText.text = statusText
        downloadSubText.text = subText
        downloadSubText.visibility = if (subText.isNotBlank()) View.VISIBLE else View.GONE
        downloadOverlay.visibility = View.VISIBLE
        downloadSpinner.start()
    }

    private fun hideDownloadOverlay() {
        downloadSpinner.stop()
        downloadOverlay.visibility = View.GONE
    }

    private fun setupNcertChapter(url: String) {
        ncertUrl = url

        // Show attribution banner
        findViewById<TextView>(R.id.ncertAttributionText).visibility = View.VISIBLE

        // Check if cached PDF is for the SAME URL (prevents stale cache from wrong URL)
        val cachedPdf    = java.io.File(cacheDir, "pdf_cache/$ncertPdfId.pdf")
        val cachedUrlFile = java.io.File(cacheDir, "pdf_cache/$ncertPdfId.url")
        val cachedUrl    = if (cachedUrlFile.exists()) cachedUrlFile.readText().trim() else ""

        if (cachedPdf.exists() && (url.isBlank() || cachedUrl == url)) {
            // Cache hit with matching URL — show pages directly
            loadNcertPagesFromCache()
            return
        } else if (cachedPdf.exists() && cachedUrl != url && url.isNotBlank()) {
            // URL changed (chapter re-configured) — delete stale cache and re-download
            cachedPdf.delete()
            cachedUrlFile.delete()
            // Also clear rendered page images for this chapter
            java.io.File(cacheDir, "pdf_pages/$ncertPdfId").deleteRecursively()
        }

        if (url.isBlank()) {
            pagesListData.clear()
            pagesListData.add("⚠️ No NCERT URL available for this chapter.")
            pageListAdapter.notifyDataSetChanged()
            return
        }

        // Auto-start download immediately — no need to tap
        val pdfFileName = url.substringAfterLast("/")
        showDownloadOverlay(
            "Downloading '$pdfFileName'",
            "Free NCERT textbook · saved once"
        )
        downloadNcertToCache()
    }

    /**
     * Generates candidate NCERT URLs to try in order.
     * NCERT servers use inconsistent patterns — this covers all known variants.
     */
    private fun ncertCandidateUrls(): List<String> {
        val base = "https://ncert.nic.in/textbook/pdf"
        val candidates = mutableListOf<String>()
        // Always try the stored URL first (may already be correct)
        if (ncertUrl.isNotBlank()) candidates.add(ncertUrl)
        if (ncertCode.isNotBlank() && ncertChapterNum > 0) {
            val ch2 = ncertChapterNum.toString().padStart(2, '0')  // "01", "10"
            val ch1 = ncertChapterNum.toString()                    // "1", "10"
            listOf(
                "$base/${ncertCode}${ch2}.pdf",   // standard zero-padded  (fesc101.pdf)
                "$base/${ncertCode}${ch1}.pdf",   // no zero-pad            (fesc11.pdf)
                "$base/${ncertCode}${ch2}1.pdf",  // trailing-1 variant     (fesc1011.pdf)
                "$base/${ncertCode}dd.pdf",        // full-book "dd" variant
                "$base/${ncertCode}dd1.pdf"        // full-book "dd1" variant
            ).forEach { if (it !in candidates) candidates.add(it) }
        }
        return candidates
    }

    /** Downloads the NCERT PDF directly into the app's private cache, then renders it.
     *  Tries all known URL patterns before giving up — NCERT URLs are not stable. */
    private fun downloadNcertToCache() {
        if (ncertUrl.isBlank() && ncertCode.isBlank()) return

        showDownloadOverlay("Downloading…", "Free NCERT textbook · saved once")
        pageListAdapter.onItemClickOverride = null

        lifecycleScope.launch(Dispatchers.IO) {
            val destDir  = java.io.File(cacheDir, "pdf_cache").also { it.mkdirs() }
            val destFile = java.io.File(destDir, "$ncertPdfId.pdf")
            var lastError: Exception? = null
            var successUrl: String? = null

            outer@ for (url in ncertCandidateUrls()) {
                // 2 attempts per candidate — handles transient SSL/network blips
                for (attempt in 1..2) {
                    try {
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent",
                                "Mozilla/5.0 (Linux; Android 10; Mobile) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/124.0.0.0 Mobile Safari/537.36")
                            .header("Accept", "application/pdf,*/*")
                            .header("Referer", "https://ncert.nic.in/")
                            .build()

                        val response = ncertHttpClient.newCall(request).execute()
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val body = response.body ?: throw Exception("Empty response body")

                        body.byteStream().use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        // Persist working URL for cache-invalidation check
                        java.io.File(cacheDir, "pdf_cache/$ncertPdfId.url").writeText(url)
                        successUrl = url
                        break@outer
                    } catch (e: Exception) {
                        lastError = e
                        destFile.delete()
                        if (attempt < 2) kotlinx.coroutines.delay(1000L)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                hideDownloadOverlay()
                if (successUrl != null) {
                    loadNcertPagesFromCache()
                } else {
                    val fn = ncertUrl.substringAfterLast("/").ifBlank { "chapter" }
                    pagesListData.clear()
                    pagesListData.add("⬇️  Tap to retry downloading \"$fn\"")
                    pageListAdapter.notifyDataSetChanged()
                    pageListAdapter.onItemClickOverride = { pos ->
                        if (pos == 0) downloadNcertToCache()
                    }
                    Toast.makeText(this@ChapterActivity,
                        "Download failed: ${lastError?.message}. Tap to retry.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Called once the PDF is in our cache dir. Sets up isPdfChapter state
     * and renders the page list exactly like a regular PDF chapter.
     */
    private fun loadNcertPagesFromCache() {
        isPdfChapter = true
        pdfId        = ncertPdfId
        pdfAssetPath = ""          // cache already exists; PdfPageManager skips asset-open
        pageListAdapter.onItemClickOverride = null

        pagesListData.clear()
        pageListAdapter.notifyDataSetChanged()
        showDownloadOverlay("Loading pages…", "")

        // Wire Ask AI button → switch to Chat tab
//        findViewById<MaterialButton>(R.id.askAIButton).apply {
//            text = "💬 Ask AI about this Chapter"
//            setOnClickListener { switchToChat() }
//        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = pdfPageManager.getPageCount(pdfId, pdfAssetPath)
                pdfPageCount = count
                withContext(Dispatchers.Main) {
                    pagesListData.clear()
                    for (i in 1..count) pagesListData.add("📄  Page $i")
                    pageListAdapter.notifyDataSetChanged()
                    hideDownloadOverlay()
                    Toast.makeText(this@ChapterActivity, "✅ $count pages ready!", Toast.LENGTH_SHORT).show()
                    // Do NOT auto-open the PDF viewer here — let the user tap a page
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideDownloadOverlay()
                    pagesListData.clear()
                    pagesListData.add("⚠️ Could not read PDF: ${e.message}")
                    pageListAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun setupPdfChapter() {
        val cachedPdf = java.io.File(cacheDir, "pdf_cache/$pdfId.pdf")
        if (pdfId.isBlank() || (pdfAssetPath.isBlank() && !cachedPdf.exists())) {
            // PDF not found locally — try to re-download from Firebase Storage
            val userId = SessionManager.getFirestoreUserId(this)
            showDownloadOverlay("Looking up PDF…", "")
            com.aiguruapp.student.firestore.FirestoreManager.loadChapterMeta(
                userId, subjectName, chapterName,
                onSuccess = { meta ->
                    val storagePath = meta["pdfStoragePath"] as? String
                    if (!storagePath.isNullOrBlank() && pdfId.isNotBlank()) {
                        showDownloadOverlay("Downloading PDF…", "Please wait")
                        val destDir = java.io.File(cacheDir, "pdf_cache").also { it.mkdirs() }
                        val destFile = java.io.File(destDir, "$pdfId.pdf")
                        com.google.firebase.storage.FirebaseStorage.getInstance()
                            .reference.child(storagePath)
                            .getBytes(50L * 1024 * 1024)
                            .addOnSuccessListener { bytes ->
                                destFile.writeBytes(bytes)
                                hideDownloadOverlay()
                                setupPdfChapterLoad()
                            }
                            .addOnFailureListener {
                                hideDownloadOverlay()
                                pagesListData.clear()
                                pagesListData.add("⚠️ Could not download PDF. Check your connection and re-add if the problem persists.")
                                pageListAdapter.notifyDataSetChanged()
                            }
                    } else {
                        hideDownloadOverlay()
                        pagesListData.clear()
                        pagesListData.add("⚠️ PDF data missing. Re-add this chapter from the Library.")
                        pageListAdapter.notifyDataSetChanged()
                    }
                },
                onFailure = {
                    hideDownloadOverlay()
                    pagesListData.clear()
                    pagesListData.add("⚠️ PDF data missing. Re-add this chapter from the Library.")
                    pageListAdapter.notifyDataSetChanged()
                }
            )
            return
        }
        setupPdfChapterLoad()
    }

    private fun setupPdfChapterLoad() {
        findViewById<MaterialButton>(R.id.uploadImageButton).apply {
            visibility = View.VISIBLE
            setOnClickListener { showImageSourceDialog() }
        }
        // Ask AI → switch to Chat tab
//        findViewById<MaterialButton>(R.id.askAIButton).apply {
//            text = "💬 Ask AI about this Chapter"
//            setOnClickListener { switchToChat() }
//        }

        pagesListData.clear()
        pageListAdapter.notifyDataSetChanged()
        showDownloadOverlay("Loading PDF pages…", "")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = pdfPageManager.getPageCount(pdfId, pdfAssetPath)
                pdfPageCount = count

                withContext(Dispatchers.Main) {
                    hideDownloadOverlay()
                    pagesListData.clear()
                    for (i in 1..count) pagesListData.add("📄  Page $i")
                    pageListAdapter.notifyDataSetChanged()
                    // Stay on the pages tab — let the user tap a page to open it
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideDownloadOverlay()
                    pagesListData.clear()
                    pagesListData.add("⚠️ Failed to load PDF: ${e.message}")
                    pageListAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun onViewPage(position: Int) {
        metricsTracker.recordPageViewed(position + 1)
        markPageOpened(position)
        // Pre-render this page (and let PageViewerActivity handle rest)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pdfPageManager.getPage(pdfId, pdfAssetPath, position)
                withContext(Dispatchers.Main) {
                    pageViewerForChapterLauncher.launch(
                        Intent(this@ChapterActivity, PageViewerActivity::class.java)
                            .putExtra("subjectName", subjectName)
                            .putExtra("chapterName", chapterName)
                            .putExtra("pdfId", pdfId)
                            .putExtra("pdfAssetPath", pdfAssetPath)
                            .putExtra("pageCount", pdfPageCount)
                            .putExtra("startPage", position)
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChapterActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onAskPage(position: Int) {
        metricsTracker.recordPageViewed(position + 1)
        markPageOpened(position)
        Toast.makeText(this, "Rendering page ${position + 1}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pageFile = pdfPageManager.getPage(pdfId, pdfAssetPath, position)
                withContext(Dispatchers.Main) {
                    val fragment = getOrCreateChatFragment()
                    switchToChat()
                    fragment.attachPdfPage(pageFile.absolutePath, position + 1)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChapterActivity, "Render failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─── Image chapter (plain photo uploads) ──────────────────────────────────

//    private fun setupImageChapter() {
//        findViewById<MaterialButton>(R.id.uploadImageButton).setOnClickListener { showImageSourceDialog() }
//        // Ask AI → switch to Chat tab
//        findViewById<MaterialButton>(R.id.askAIButton).setOnClickListener { switchToChat() }
//        // For image chapters the View button opens the image in ChatActivity;
//        // Ask does the same — both open ChatActivity with the image path
//        loadImagePages()
//    }

    private fun showImageSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add Textbook Page")
            .setItems(arrayOf("📷  Take Photo", "🖼️  Choose from Gallery")) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> pickImageLauncher.launch("image/*")
                }
            }.show()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            ); return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_Page_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraImageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        cameraImageUri?.let { cameraLauncher.launch(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            openCamera()
    }

    private fun savePage(imagePath: String) {
        val timestamp = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date())
        val prefs = getSharedPreferences("chapters_prefs", MODE_PRIVATE)
        val key = "imgpages_${subjectName}_${chapterName}"
        val existing = prefs.getString(key, "[]") ?: "[]"
        val arr = try { org.json.JSONArray(existing) } catch (_: Exception) { org.json.JSONArray() }
        arr.put(org.json.JSONObject().apply {
            put("path", imagePath)
            put("timestamp", timestamp)
        })
        prefs.edit().putString(key, arr.toString()).apply()
        imagePagePaths.add(imagePath)
        pagesListData.add("Page uploaded - $timestamp")
        pageListAdapter.notifyItemInserted(pagesListData.size - 1)
        Toast.makeText(this, "Page saved!", Toast.LENGTH_SHORT).show()
    }

    private fun loadImagePages() {
        // Rebuild the PageListAdapter callbacks for image chapter
        pageListAdapter = PageListAdapter(
            pages = pagesListData,
            onView = { position ->
                if (position !in imagePagePaths.indices) return@PageListAdapter
                val fragment = getOrCreateChatFragment()
                switchToChat()
                fragment.attachImage(imagePagePaths[position])
            },
            onAsk = { position ->
                if (position !in imagePagePaths.indices) return@PageListAdapter
                val fragment = getOrCreateChatFragment()
                switchToChat()
                fragment.attachImage(imagePagePaths[position])
            }
        )
        pagesRecyclerView.adapter = pageListAdapter

        val key = "imgpages_${subjectName}_${chapterName}"
        val raw = getSharedPreferences("chapters_prefs", MODE_PRIVATE).getString(key, "[]") ?: "[]"
        imagePagePaths.clear()
        pagesListData.clear()
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    val path = obj.optString("path", "")
                    val ts = obj.optString("timestamp", "")
                    if (path.isNotBlank()) {
                        imagePagePaths.add(path)
                        pagesListData.add("Page - $ts")
                    }
                }
            }
        } catch (_: Exception) { }
        pageListAdapter.notifyDataSetChanged()
    }
}
