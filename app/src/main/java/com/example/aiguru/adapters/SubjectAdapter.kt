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

    // Soft pastel tints for the icon circle — looks good on light theme
    private val circleTints = intArrayOf(
        Color.parseColor("#FFE8D6"), // warm orange-peach
        Color.parseColor("#D6EEFF"), // sky blue
        Color.parseColor("#D6F5E8"), // mint green
        Color.parseColor("#F0D6FF"), // soft purple
        Color.parseColor("#FFD6D6"), // light red
        Color.parseColor("#FFF5D6"), // warm yellow
        Color.parseColor("#D6F5FF"), // cyan
        Color.parseColor("#FFD6F0"), // pink
        Color.parseColor("#E8FFD6"), // lime
        Color.parseColor("#D6D6FF")  // periwinkle
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
        val colorIdx = Math.abs(subject.lowercase().hashCode()) % circleTints.size
        val tint = circleTints[colorIdx]

        // Tint the circle background behind the emoji
        (holder.accentBar.background as? GradientDrawable)?.setColor(tint)
            ?: holder.accentBar.setBackgroundColor(tint)

        holder.subjectIcon.text = emojiFor(subject)
        holder.subjectName.text = subject
        holder.itemView.setOnClickListener { onItemClick(subject) }
        holder.itemView.setOnLongClickListener { onItemLongClick(subject); true }
    }

    override fun getItemCount() = subjects.size
}
