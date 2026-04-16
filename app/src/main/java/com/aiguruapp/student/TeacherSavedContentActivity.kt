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
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shows all BB sessions saved by the teacher (across all subjects/chapters).
 * Uses the flat saved_bb_sessions_flat collection.
 * Teachers can replay a session or assign it as a task.
 */
class TeacherSavedContentActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var loadingBar: android.widget.ProgressBar
    private lateinit var adapter: SessionsAdapter
    private val sessions = mutableListOf<Map<String, Any>>()
    private val userId by lazy { SessionManager.getFirestoreUserId(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_saved_content)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        emptyState   = findViewById(R.id.emptyState)
        loadingBar   = findViewById(R.id.loadingBar)
        recyclerView = findViewById(R.id.sessionsList)

        adapter = SessionsAdapter(
            sessions  = sessions,
            onReplay  = { session -> replaySession(session) },
            onAssign  = { session -> assignAsTask(session) },
            onDelete  = { session -> confirmDelete(session) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        loadSessions()
    }

    private fun loadSessions() {
        loadingBar.visibility   = View.VISIBLE
        emptyState.visibility   = View.GONE
        recyclerView.visibility = View.GONE
        FirestoreManager.loadAllSavedBbSessions(
            userId    = userId,
            onSuccess = { list ->
                loadingBar.visibility = View.GONE
                sessions.clear()
                sessions.addAll(list)
                adapter.notifyDataSetChanged()
                if (sessions.isEmpty()) {
                    emptyState.visibility  = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyState.visibility  = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
            },
            onFailure = {
                loadingBar.visibility = View.GONE
                Toast.makeText(this, "Couldn't load sessions", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun replaySession(session: Map<String, Any>) {
        val topic   = session["topic"] as? String ?: return
        val subject = session["subject"] as? String ?: "General"
        val chapter = session["chapter"] as? String ?: "General"
        startActivity(
            Intent(this, BlackboardActivity::class.java).apply {
                putExtra(BlackboardActivity.EXTRA_MESSAGE, topic)
                putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
                putExtra(BlackboardActivity.EXTRA_SUBJECT, subject)
                putExtra(BlackboardActivity.EXTRA_CHAPTER, chapter)
            }
        )
    }

    private fun assignAsTask(session: Map<String, Any>) {
        val topic   = session["topic"] as? String ?: ""
        val subject = session["subject"] as? String ?: ""
        val chapter = session["chapter"] as? String ?: ""
        startActivity(
            Intent(this, TeacherTasksActivity::class.java).apply {
                putExtra(TeacherTasksActivity.EXTRA_PREFILL_BB_TOPIC, topic)
                putExtra(TeacherTasksActivity.EXTRA_PREFILL_SUBJECT, subject)
                putExtra(TeacherTasksActivity.EXTRA_PREFILL_CHAPTER, chapter)
            }
        )
    }

    private fun confirmDelete(session: Map<String, Any>) {
        val sessionId = session["session_id"] as? String ?: session["id"] as? String ?: return
        val subject   = session["subject"] as? String ?: ""
        val chapter   = session["chapter"] as? String ?: ""
        AlertDialog.Builder(this)
            .setTitle("Delete Session?")
            .setPositiveButton("Delete") { _, _ ->
                FirestoreManager.deleteBbSession(
                    userId    = userId,
                    subject   = subject,
                    chapter   = chapter,
                    sessionId = sessionId,
                    onSuccess = {
                        val idx = sessions.indexOfFirst {
                            (it["session_id"] ?: it["id"]) == sessionId
                        }
                        if (idx >= 0) { sessions.removeAt(idx); adapter.notifyItemRemoved(idx) }
                        if (sessions.isEmpty()) {
                            emptyState.visibility  = View.VISIBLE
                            recyclerView.visibility = View.GONE
                        }
                    },
                    onFailure = { Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class SessionsAdapter(
        private val sessions: List<Map<String, Any>>,
        private val onReplay: (Map<String, Any>) -> Unit,
        private val onAssign: (Map<String, Any>) -> Unit,
        private val onDelete: (Map<String, Any>) -> Unit
    ) : RecyclerView.Adapter<SessionsAdapter.VH>() {

        private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val topic:     TextView = v.findViewById(R.id.sessionTopic)
            val meta:      TextView = v.findViewById(R.id.sessionStepCount)
            val date:      TextView = v.findViewById(R.id.sessionDate)
            val replayBtn: TextView = v.findViewById(R.id.replayBtn)
            val assignBtn: TextView = v.findViewById(R.id.assignBtn)
            val deleteBtn: TextView = v.findViewById(R.id.deleteBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_teacher_saved_session, parent, false))

        override fun getItemCount() = sessions.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = sessions[position]
            holder.topic.text = (s["topic"] as? String)?.trim() ?: "(no topic)"
            val subj    = s["subject"] as? String ?: ""
            val chap    = s["chapter"] as? String ?: ""
            val steps   = (s["step_count"] as? Long)?.toInt() ?: (s["step_count"] as? Int) ?: 0
            holder.meta.text = "$subj · $chap · ${if (steps > 0) "$steps steps" else "BB Session"}"
            val savedAt = (s["saved_at"] as? Long) ?: 0L
            holder.date.text = if (savedAt > 0) fmt.format(Date(savedAt)) else ""
            holder.replayBtn.setOnClickListener { onReplay(s) }
            holder.assignBtn.setOnClickListener { onAssign(s) }
            holder.deleteBtn.setOnClickListener { onDelete(s) }
            holder.itemView.setOnLongClickListener { onAssign(s); true }
        }
    }
}
