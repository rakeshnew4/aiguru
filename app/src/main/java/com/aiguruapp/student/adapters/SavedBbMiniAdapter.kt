package com.aiguruapp.student.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Compact adapter for saved BB sessions shown inside the Saved tab of ChapterActivity.
 * Each item shows the topic + date + a Replay button.
 */
class SavedBbMiniAdapter(
    private val sessions: List<Map<String, Any>>,
    private val onReplay: (Map<String, Any>) -> Unit
) : RecyclerView.Adapter<SavedBbMiniAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val topic:     TextView = view.findViewById(R.id.bbMiniTopic)
        val date:      TextView = view.findViewById(R.id.bbMiniDate)
        val replayBtn: TextView = view.findViewById(R.id.bbMiniReplayBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_bb_mini, parent, false)
        return VH(v)
    }

    override fun getItemCount() = sessions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val session = sessions[position]
        holder.topic.text = (session["topic"] as? String) ?: "BB Session"
        val ts = (session["savedAt"] as? Long) ?: (session["timestamp"] as? Long) ?: 0L
        holder.date.text = if (ts > 0) dateFormat.format(Date(ts)) else ""
        holder.replayBtn.setOnClickListener { onReplay(session) }
        holder.itemView.setOnClickListener { onReplay(session) }
    }
}
