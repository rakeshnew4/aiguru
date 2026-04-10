package com.aiguruapp.student.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.R
import com.aiguruapp.student.models.LibraryBook
import com.google.android.material.button.MaterialButton

class LibraryAdapter(
    private val books: List<LibraryBook>,
    private val onAddToSubject: (LibraryBook) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.BookViewHolder>() {

    inner class BookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.bookTitle)
        val meta: TextView = view.findViewById(R.id.bookMeta)
        val addButton: MaterialButton = view.findViewById(R.id.addToSubjectButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_library_book, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val book = books[position]
        // Make filename human-readable: underscores/hyphens → spaces, capitalize
        holder.title.text = book.title.replace("_", " ").replace("-", " ")
            .split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        holder.meta.text = "${book.grade} • ${book.subject}"
        holder.addButton.setOnClickListener { onAddToSubject(book) }
    }

    override fun getItemCount() = books.size
}
