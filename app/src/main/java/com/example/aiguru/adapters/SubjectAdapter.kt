package com.example.aiguru.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.R

class SubjectAdapter(
    private val subjects: MutableList<String>,
    private val onItemClick: (String) -> Unit,
    private val onItemLongClick: (String) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.ViewHolder>() {

    // Neon/vivid accent colors matching the dark card aesthetic
    private val accentColors = intArrayOf(
        Color.parseColor("#00E5FF"), // cyan
        Color.parseColor("#D500F9"), // vivid purple
        Color.parseColor("#2979FF"), // bright blue
        Color.parseColor("#FF1744"), // red
        Color.parseColor("#FF6D00"), // deep orange
        Color.parseColor("#00E676"), // green
        Color.parseColor("#FFEA00"), // yellow
        Color.parseColor("#F50057"), // pink
        Color.parseColor("#00B0FF"), // light blue
        Color.parseColor("#76FF03")  // lime
    )

    // Subject-specific emoji mapping — falls back to index-based
    private fun emojiFor(subject: String): String {
        return when (subject.lowercase().trim()) {
            "mathematics", "maths", "math"   -> "🧮"
            "science"                         -> "⚗️"
            "physics"                         -> "⚡"
            "chemistry"                       -> "🧪"
            "biology"                         -> "🧬"
            "computer", "computers",
            "computer science", "it",
            "information technology"          -> "💻"
            "english"                         -> "📖"
            "history"                         -> "📜"
            "geography"                       -> "🌍"
            "economics"                       -> "📊"
            "art", "arts", "fine arts"        -> "🎨"
            "music"                           -> "🎵"
            "physical education", "pe",
            "sports", "gym"                   -> "🏃"
            "social science", "social studies"-> "🗺️"
            "hindi"                           -> "🪷"
            "sanskrit"                        -> "📿"
            "french", "german", "spanish"     -> "🌐"
            "psychology"                      -> "🧠"
            "philosophy"                      -> "💭"
            "business studies", "business"    -> "💼"
            "accountancy", "accounts"         -> "🧾"
            else -> {
                val fallbacks = arrayOf("📘", "🔢", "🔬", "📐", "🗺️", "🎯", "🔭", "🧩")
                fallbacks[Math.abs(subject.hashCode()) % fallbacks.size]
            }
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val accentBar: View = view.findViewById(R.id.accentBar)
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
        val colorIdx = Math.abs(subject.lowercase().hashCode()) % accentColors.size
        val accent = accentColors[colorIdx]

        // Set accent bar color
        holder.accentBar.setBackgroundColor(accent)

        // Set emoji
        holder.subjectIcon.text = emojiFor(subject)

        holder.subjectName.text = subject
        holder.itemView.setOnClickListener { onItemClick(subject) }
        holder.itemView.setOnLongClickListener { onItemLongClick(subject); true }
    }

    override fun getItemCount() = subjects.size
}
