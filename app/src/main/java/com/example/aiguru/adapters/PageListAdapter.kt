package com.example.aiguru.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.R
import com.google.android.material.button.MaterialButton

class PageListAdapter(
    private val pages: List<String>,
    private val onView: (Int) -> Unit,
    private val onAsk: (Int) -> Unit
) : RecyclerView.Adapter<PageListAdapter.PageViewHolder>() {

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
        holder.label.text = if (position == 0) "★ $label" else label
        val rowBg = if (position % 2 == 0) "#EAF3FF" else "#FFF3E0"
        holder.itemView.setBackgroundColor(Color.parseColor(rowBg))
        holder.label.setTextColor(Color.parseColor(if (position == 0) "#0B3F7A" else "#143A66"))
        holder.viewBtn.strokeColor = ColorStateList.valueOf(Color.parseColor("#1E88E5"))
        holder.askBtn.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (position == 0) "#EF6C00" else "#FB8C00")
        )
        holder.viewBtn.setOnClickListener { onView(position) }
        holder.askBtn.setOnClickListener { onAsk(position) }
    }

    override fun getItemCount() = pages.size
}
