package com.example.aiguru.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.R

class SubjectAdapter(
    private val subjects: MutableList<String>,
    private val onItemClick: (String) -> Unit,
    private val onItemLongClick: (String) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.ViewHolder>() {

    private val colors = intArrayOf(
        Color.parseColor("#1565C0"), // deep blue
        Color.parseColor("#6A1B9A"), // purple
        Color.parseColor("#00695C"), // teal
        Color.parseColor("#BF360C"), // red-orange
        Color.parseColor("#2E7D32"), // green
        Color.parseColor("#0277BD"), // light blue
        Color.parseColor("#AD1457"), // pink
        Color.parseColor("#4527A0"), // deep purple
        Color.parseColor("#00838F"), // cyan
        Color.parseColor("#558B2F")  // lime green
    )

    private val emojis = arrayOf(
        "📘", "🔢", "🌍", "⚗️", "📖",
        "🔬", "💻", "🎨", "🎵", "🏃",
        "🧬", "🧪", "📐", "🗺️", "📜"
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardHeader: LinearLayout = view.findViewById(R.id.cardHeader)
        val subjectIcon: TextView = view.findViewById(R.id.subjectIcon)
        val subjectName: TextView = view.findViewById(R.id.subjectName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subject_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val subject = subjects[position]
        val idx = Math.abs(subject.lowercase().hashCode()) % colors.size
        holder.cardHeader.setBackgroundColor(colors[idx])
        holder.subjectIcon.text = emojis[Math.abs(subject.hashCode()) % emojis.size]
        holder.subjectName.text = subject
        holder.itemView.setOnClickListener { onItemClick(subject) }
        holder.itemView.setOnLongClickListener { onItemLongClick(subject); true }
    }

    override fun getItemCount() = subjects.size
}
