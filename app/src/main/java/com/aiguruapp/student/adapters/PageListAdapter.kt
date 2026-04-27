package com.aiguruapp.student.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.R
import com.google.android.material.button.MaterialButton

class PageListAdapter(
    private val pages: List<String>,
    private val onView: (Int) -> Unit,
    private val onAsk: (Int) -> Unit
) : RecyclerView.Adapter<PageListAdapter.PageViewHolder>() {

    /** When set, replaces both the View and Ask button clicks with a single action. */
    var onItemClickOverride: ((Int) -> Unit)? = null

    /** Page indices (0-based) the user has already opened. Drives the ✓ indicator. */
    var openedPages: Set<Int> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }

    inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.pageLabel)
        val viewBtn: MaterialButton = view.findViewById(R.id.viewPageButton)
        val askBtn: MaterialButton = view.findViewById(R.id.askPageButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter_page, parent, false)
        return PageViewHolder(v)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val label = pages[position]
        val isOpened = position in openedPages
        // Row background: green for viewed, alternating blue/orange otherwise
        val rowBg = when {
            isOpened      -> "#E8F5E9"
            position % 2 == 0 -> "#EAF3FF"
            else          -> "#FFF3E0"
        }
        holder.itemView.setBackgroundColor(Color.parseColor(rowBg))
        // Label: prefix ✓ for viewed pages; ★ for first page
        holder.label.text = when {
            isOpened      -> "✓ $label"
            position == 0 -> "★ $label"
            else          -> label
        }
        holder.label.setTextColor(Color.parseColor(
            when {
                isOpened      -> "#2E7D32"
                position == 0 -> "#0B3F7A"
                else          -> "#143A66"
            }
        ))
        holder.viewBtn.strokeColor = ColorStateList.valueOf(Color.parseColor("#1E88E5"))
        holder.askBtn.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (position == 0) "#EF6C00" else "#FB8C00")
        )
        val override = onItemClickOverride
        if (override != null) {
            holder.viewBtn.text = "📖 Open"
            holder.askBtn.visibility = View.GONE
            holder.viewBtn.setOnClickListener { override(position) }
        } else {
            holder.askBtn.visibility = View.VISIBLE
            holder.viewBtn.setOnClickListener { onView(position) }
            holder.askBtn.setOnClickListener { onAsk(position) }
        }
    }

    override fun getItemCount() = pages.size
}
