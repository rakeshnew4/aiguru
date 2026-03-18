package com.example.aiguru.adapters

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
        holder.label.text = pages[position]
        holder.viewBtn.setOnClickListener { onView(position) }
        holder.askBtn.setOnClickListener { onAsk(position) }
    }

    override fun getItemCount() = pages.size
}
