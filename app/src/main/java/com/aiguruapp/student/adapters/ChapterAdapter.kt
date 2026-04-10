package com.aiguruapp.student.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.R
import com.google.android.material.button.MaterialButton

data class ChapterItem(
    val name: String,
    val ncertPdfUrl: String? = null   // null = no NCERT link for this chapter
)

class ChapterAdapter(
    private val chapters: MutableList<ChapterItem>,
    private val onItemClick: (ChapterItem) -> Unit,
    private val onItemLongClick: (ChapterItem) -> Unit,
    private val onNcertClick: (ChapterItem) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.ViewHolder>() {

    private val colors = intArrayOf(
        Color.parseColor("#1565C0"),
        Color.parseColor("#6A1B9A"),
        Color.parseColor("#00695C"),
        Color.parseColor("#BF360C"),
        Color.parseColor("#2E7D32"),
        Color.parseColor("#0277BD"),
        Color.parseColor("#AD1457"),
        Color.parseColor("#4527A0")
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val numberView: TextView = view.findViewById(R.id.chapterNumber)
        val nameView: TextView = view.findViewById(R.id.chapterName)
        val ncertButton: MaterialButton = view.findViewById(R.id.ncertButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chapter = chapters[position]
        val color = colors[position % colors.size]

        // Tint the oval background with the cycle color
        val base = ContextCompat.getDrawable(holder.itemView.context, R.drawable.bg_chapter_number)!!
        val tinted = DrawableCompat.wrap(base.constantState!!.newDrawable()).mutate()
        DrawableCompat.setTint(tinted, color)
        holder.numberView.background = tinted
        holder.numberView.text = (position + 1).toString()

        holder.nameView.text = chapter.name
        holder.itemView.setOnClickListener { onItemClick(chapter) }
        holder.itemView.setOnLongClickListener { onItemLongClick(chapter); true }

        // Show/hide NCERT button
        if (!chapter.ncertPdfUrl.isNullOrEmpty()) {
            holder.ncertButton.visibility = View.VISIBLE
            holder.ncertButton.setOnClickListener { onNcertClick(chapter) }
        } else {
            holder.ncertButton.visibility = View.GONE
        }
    }

    override fun getItemCount() = chapters.size
}
