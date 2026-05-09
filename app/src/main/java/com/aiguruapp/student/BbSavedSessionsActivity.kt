package com.aiguruapp.student

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.utils.SessionManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shows all saved BB sessions for a specific subject + chapter.
 * Allows replaying a session (re-launches BlackboardActivity with the same question)
 * or deleting a saved entry.
 */
class BbSavedSessionsActivity : BaseActivity() {

    companion object {
        const val EXTRA_SUBJECT      = "extra_subject"
        const val EXTRA_CHAPTER      = "extra_chapter"
        /** Set true when opening from the Watch History drawer item (no subject/chapter context). */
        const val EXTRA_ALL_HISTORY  = "extra_all_history"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: SessionsAdapter
    private val sessions = mutableListOf<Map<String, Any>>()
    private val allSessions = mutableListOf<Map<String, Any>>()
    private val sharedSessions = mutableListOf<Map<String, Any>>()
    private var activeFilter: String? = null
    private var showingShared = false
    private var isAllHistory = false

    private lateinit var subject: String
    private lateinit var chapter: String
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bb_saved_sessions)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "Subject"
        chapter = intent.getStringExtra(EXTRA_CHAPTER) ?: "Chapter"
        userId  = SessionManager.getFirestoreUserId(this)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        isAllHistory = intent.getBooleanExtra(EXTRA_ALL_HISTORY, false)
        val subtitle = if (isAllHistory) "Watch History" else "$subject › $chapter"
        findViewById<TextView>(R.id.chapterSubtitle).text = subtitle

        // Tab strip — only in Watch History mode
        val tabStrip        = findViewById<android.view.View>(R.id.tabStrip)
        val tabMySessions   = findViewById<TextView>(R.id.tabMySessions)
        val tabSharedWithMe = findViewById<TextView>(R.id.tabSharedWithMe)
        if (isAllHistory) {
            tabStrip.visibility = android.view.View.VISIBLE
            tabMySessions.text   = "My Sessions"
            tabSharedWithMe.text = "Shared with Me"
            tabMySessions.setOnClickListener   { switchTab(false, tabMySessions, tabSharedWithMe) }
            tabSharedWithMe.setOnClickListener { switchTab(true,  tabMySessions, tabSharedWithMe) }
        }

        emptyState   = findViewById(R.id.emptyState)
        recyclerView = findViewById(R.id.sessionsList)

        adapter = SessionsAdapter(
            sessions  = sessions,
            onReplay  = { session -> replaySession(session) },
            onDelete  = { session -> confirmDelete(session) },
            onShare   = { session -> shareSession(session) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<com.google.android.material.button.MaterialButton>(R.id.emptyStateStartBbBtn)
            .setOnClickListener {
                finish()
                startActivity(Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }

        loadSessions()
    }

    // ── Local cache helpers ───────────────────────────────────────────────────

    private fun cacheFile(): File = File(cacheDir,
        if (isAllHistory) "bb_watch_history_${userId}.json" else "bb_sessions_${userId}.json")

    @Suppress("UNCHECKED_CAST")
    private fun readCache(): List<Map<String, Any>>? = try {
        val f = cacheFile()
        if (!f.exists()) null
        else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.keys().asSequence().associateWith { k ->
                    when (val v = obj.get(k)) {
                        is JSONArray -> (0 until v.length()).map { v.getString(it) }
                        else -> v
                    }
                }
            }
        }
    } catch (_: Exception) { null }

    private fun writeCache(list: List<Map<String, Any>>) {
        try {
            val arr = JSONArray()
            list.forEach { map ->
                val obj = JSONObject()
                map.forEach { (k, v) ->
                    when (v) {
                        is List<*> -> obj.put(k, JSONArray(v))
                        else -> obj.put(k, v)
                    }
                }
                arr.put(obj)
            }
            cacheFile().writeText(arr.toString())
        } catch (_: Exception) {}
    }

    private fun invalidateCache() = try { cacheFile().delete() } catch (_: Exception) {}

    // ── Session loading ───────────────────────────────────────────────────────

    private fun loadSessions() {
        // Show cached data immediately (instant UI)
        val cached = readCache()
        if (cached != null) {
            allSessions.clear()
            allSessions.addAll(cached)
            applyFilter(activeFilter)
            buildFilterChips()
        }
        // Refresh from Firestore in background; update + re-cache if changed
        val loadFn: (String, (List<Map<String, Any>>) -> Unit, (Exception?) -> Unit) -> Unit =
            if (isAllHistory) FirestoreManager::loadBbWatchHistory
            else FirestoreManager::loadAllSavedBbSessions
        loadFn(
            userId, { list ->
                writeCache(list)
                allSessions.clear()
                allSessions.addAll(list)
                applyFilter(activeFilter)
                buildFilterChips()
            }, {
                if (cached == null)
                    Toast.makeText(this, "Couldn't load sessions", Toast.LENGTH_SHORT).show()
                // else: silently use cached data — no toast if we already showed something
            }
        )
    }

    private fun applyFilter(subject: String?) {
        activeFilter = subject
        sessions.clear()
        sessions.addAll(if (subject == null) allSessions
                        else allSessions.filter { (it["subject"] as? String) == subject })
        adapter.notifyDataSetChanged()
        val empty = sessions.isEmpty()
        emptyState.visibility   = if (empty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun buildFilterChips() {
        val scroll    = findViewById<android.widget.HorizontalScrollView>(R.id.filterScrollView) ?: return
        val container = findViewById<android.widget.LinearLayout>(R.id.filterChipsContainer) ?: return
        val subjects  = allSessions.mapNotNull { it["subject"] as? String }
            .filter { it.isNotBlank() }.distinct().sorted()
        if (subjects.isEmpty()) { scroll.visibility = View.GONE; return }

        container.removeAllViews()
        scroll.visibility = View.VISIBLE
        val dp = resources.displayMetrics.density

        fun makeChip(label: String, filterValue: String?): android.widget.TextView {
            return android.widget.TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                val isActive = filterValue == activeFilter
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16 * dp
                    setColor(if (isActive) android.graphics.Color.parseColor("#3D1A6E")
                             else android.graphics.Color.parseColor("#252840"))
                    setStroke((1 * dp).toInt(),
                        if (isActive) android.graphics.Color.parseColor("#7B52CC")
                        else android.graphics.Color.parseColor("#33AABBCC"))
                }
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dp).toInt() }
                setOnClickListener { applyFilter(filterValue); buildFilterChips() }
            }
        }

        container.addView(makeChip("All", null))
        subjects.forEach { container.addView(makeChip(it, it)) }
    }

    // ── Tab switching ─────────────────────────────────────────────────────────

    private fun switchTab(toShared: Boolean, tabMy: TextView, tabShared: TextView) {
        if (toShared == showingShared) return
        showingShared = toShared
        tabMy.setBackgroundColor(if (toShared) 0xFF252840.toInt() else 0xFF3D1A6E.toInt())
        tabMy.setTextColor(if (toShared) 0xFFAABBCC.toInt() else 0xFFFFFFFF.toInt())
        tabShared.setBackgroundColor(if (toShared) 0xFF3D1A6E.toInt() else 0xFF252840.toInt())
        tabShared.setTextColor(if (toShared) 0xFFFFFFFF.toInt() else 0xFFAABBCC.toInt())

        val filterScroll = findViewById<android.widget.HorizontalScrollView>(R.id.filterScrollView)
        filterScroll?.visibility = if (toShared) View.GONE else View.VISIBLE

        if (toShared) {
            if (sharedSessions.isEmpty()) loadSharedSessions()
            else showSharedSessions()
        } else {
            applyFilter(activeFilter)
            buildFilterChips()
        }
    }

    private fun loadSharedSessions() {
        FirestoreManager.loadSharedWithMe(
            userId    = userId,
            onSuccess = { list ->
                runOnUiThread {
                    sharedSessions.clear()
                    sharedSessions.addAll(list)
                    showSharedSessions()
                }
            },
            onFailure = {
                runOnUiThread { Toast.makeText(this, "Couldn't load shared sessions", Toast.LENGTH_SHORT).show() }
            }
        )
    }

    private fun showSharedSessions() {
        sessions.clear()
        sessions.addAll(sharedSessions)
        adapter.notifyDataSetChanged()
        val empty = sessions.isEmpty()
        emptyState.visibility   = if (empty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (empty) View.GONE    else View.VISIBLE
    }

    private fun replaySession(session: Map<String, Any>) {
        val sessionId = session["session_id"] as? String ?: session["id"] as? String ?: ""
        val topic     = session["topic"] as? String ?: ""
        val convId    = session["conversation_id"] as? String
        val msgId     = session["message_id"] as? String
        @Suppress("UNCHECKED_CAST")
        val ttsKeys = (session["tts_keys"] as? List<String>) ?: emptyList()
        // Shared sessions live in shared_with_me (not saved_bb_sessions_flat), so
        // loadFromSavedSession would look in the wrong Firestore collection.
        // Seed the disk cache from steps_json so the cache hit bypasses Firestore entirely.
        if (showingShared && sessionId.isNotBlank()) {
            val stepsJson = session["steps_json"] as? String ?: ""
            if (stepsJson.isNotBlank()) {
                com.aiguruapp.student.chat.BlackboardGenerator.writeSessionCache(
                    applicationContext, sessionId, stepsJson)
            }
        }
        val intent = Intent(this, BlackboardActivity::class.java).apply {
            putExtra(BlackboardActivity.EXTRA_MESSAGE, topic)
            putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
            putExtra(BlackboardActivity.EXTRA_SUBJECT, subject)
            putExtra(BlackboardActivity.EXTRA_CHAPTER, chapter)
            putExtra(BlackboardActivity.EXTRA_IS_REPLAY, true)
            // Pass sessionId so BlackboardActivity loads steps directly from Firestore
            // without any LLM regeneration.
            if (sessionId.isNotBlank()) putExtra(BlackboardActivity.EXTRA_SESSION_ID, sessionId)
            if (ttsKeys.isNotEmpty()) putExtra(BlackboardActivity.EXTRA_TTS_KEYS, ArrayList(ttsKeys))
            if (!convId.isNullOrBlank()) putExtra(BlackboardActivity.EXTRA_CONVERSATION_ID, convId)
            if (!msgId.isNullOrBlank())  putExtra(BlackboardActivity.EXTRA_MESSAGE_ID, msgId)
        }
        startActivity(intent)
    }

    private fun shareSession(session: Map<String, Any>) {
        val sessionId  = session["session_id"] as? String ?: session["id"] as? String ?: ""
        val topic      = session["topic"] as? String ?: return
        val stepCount  = (session["step_count"] as? Long)?.toInt() ?: (session["step_count"] as? Int) ?: 0
        val stepsJson  = session["steps_json"] as? String ?: ""
        val msgId      = session["message_id"] as? String ?: ""
        val convId     = session["conversation_id"] as? String ?: ""

        val intent = Intent(this, FriendsActivity::class.java).apply {
            putExtra(FriendsActivity.EXTRA_SHARE_MODE,       true)
            putExtra(FriendsActivity.EXTRA_SESSION_ID,       sessionId)
            putExtra(FriendsActivity.EXTRA_SESSION_TOPIC,    topic)
            putExtra(FriendsActivity.EXTRA_SESSION_STEPS,    stepsJson)
            putExtra(FriendsActivity.EXTRA_SESSION_STEP_CNT, stepCount)
            putExtra(FriendsActivity.EXTRA_SESSION_MSG_ID,   msgId)
            putExtra(FriendsActivity.EXTRA_SESSION_CONV_ID,  convId)
        }
        startActivity(intent)
    }

    private fun confirmDelete(session: Map<String, Any>) {
        val sessionId = session["session_id"] as? String ?: session["id"] as? String ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove Session?")
            .setMessage(if (isAllHistory) "Remove from Watch History?" else "Remove this session from My Sessions?")
            .setPositiveButton("Remove") { _, _ ->
                val onDeleteSuccess = {
                    allSessions.removeAll { (it["session_id"] ?: it["id"]) == sessionId }
                    val idx = sessions.indexOfFirst {
                        (it["session_id"] ?: it["id"]) == sessionId
                    }
                    if (idx >= 0) {
                        sessions.removeAt(idx)
                        adapter.notifyItemRemoved(idx)
                    }
                    if (sessions.isEmpty()) {
                        emptyState.visibility   = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    }
                    buildFilterChips()
                    writeCache(allSessions)
                    Unit
                }
                val onDeleteFailure = { _: Exception? ->
                    Toast.makeText(this, "Remove failed", Toast.LENGTH_SHORT).show()
                    Unit
                }
                if (isAllHistory) {
                    FirestoreManager.deleteBbHistoryEntry(
                        userId    = userId,
                        sessionId = sessionId,
                        onSuccess = onDeleteSuccess,
                        onFailure = onDeleteFailure
                    )
                } else {
                    FirestoreManager.deleteBbSession(
                        userId    = userId,
                        subject   = subject,
                        chapter   = chapter,
                        sessionId = sessionId,
                        onSuccess = onDeleteSuccess,
                        onFailure = onDeleteFailure
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class SessionsAdapter(
        private val sessions: List<Map<String, Any>>,
        private val onReplay: (Map<String, Any>) -> Unit,
        private val onDelete: (Map<String, Any>) -> Unit,
        private val onShare:  (Map<String, Any>) -> Unit
    ) : RecyclerView.Adapter<SessionsAdapter.VH>() {

        private val dateFormat = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val topic:     TextView = view.findViewById(R.id.sessionTopic)
            val stepCount: TextView = view.findViewById(R.id.sessionStepCount)
            val date:      TextView = view.findViewById(R.id.sessionDate)
            val replayBtn: TextView = view.findViewById(R.id.replayBtn)
            val deleteBtn: TextView = view.findViewById(R.id.deleteBtn)
            val shareBtn:  TextView = view.findViewById(R.id.shareBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bb_saved_session, parent, false)
            return VH(view)
        }

        override fun getItemCount() = sessions.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val session   = sessions[position]
            val topic     = (session["topic"] as? String)?.trim() ?: "(no topic)"
            val stepCount = (session["step_count"] as? Long)?.toInt() ?: (session["step_count"] as? Int) ?: 0
            val savedAt   = (session["viewed_at"] as? Long)
                ?: (session["saved_at"] as? Long)
                ?: (session["shared_at"] as? Long) ?: 0L
            val senderName = session["sender_name"] as? String

            holder.topic.text     = topic
            holder.stepCount.text = if (stepCount > 0) "📚 $stepCount steps" else "📚 BB Session"
            holder.date.text      = buildString {
                if (savedAt > 0) append(dateFormat.format(Date(savedAt)))
                if (senderName != null) {
                    if (isNotEmpty()) append(" · ")
                    append("From: $senderName")
                }
            }
            holder.replayBtn.setOnClickListener { onReplay(session) }
            holder.deleteBtn.setOnClickListener { onDelete(session) }
            holder.shareBtn.setOnClickListener  { onShare(session)  }
            // Hide share + delete buttons for received shared sessions
            if (senderName != null) {
                holder.shareBtn.visibility  = View.GONE
                holder.deleteBtn.visibility = View.GONE
            } else {
                holder.shareBtn.visibility  = View.VISIBLE
                holder.deleteBtn.visibility = View.VISIBLE
            }
        }
    }
}
