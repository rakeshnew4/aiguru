package com.aiguruapp.student.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.R
import com.aiguruapp.student.models.QuizQuestion

/**
 * Adapter for the teacher quiz validation screen.
 * Each item shows the question with a checkbox.
 * Teacher checks/unchecks to keep/remove questions.
 * All questions are checked by default.
 */
class QuizValidationAdapter(
    private val questions: List<QuizQuestion>,
    private val onSelectionChanged: (selectedCount: Int) -> Unit
) : RecyclerView.Adapter<QuizValidationAdapter.VH>() {

    private val kept = BooleanArray(questions.size) { true }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox       = view.findViewById(R.id.questionKeepCheckbox)
        val number: TextView         = view.findViewById(R.id.questionNumber)
        val typeBadge: TextView      = view.findViewById(R.id.questionTypeBadge)
        val questionText: TextView   = view.findViewById(R.id.questionText)
        val optionsContainer: LinearLayout = view.findViewById(R.id.optionsContainer)
        val sampleAnswer: TextView   = view.findViewById(R.id.sampleAnswerText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_quiz_question_validate, parent, false))

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val q = questions[position]
        holder.number.text = "Q${position + 1}"
        holder.questionText.text = q.question

        holder.typeBadge.text = when (q) {
            is QuizQuestion.MCQ             -> "MCQ"
            is QuizQuestion.FillBlankTyped  -> "Fill Blank"
            is QuizQuestion.ShortAnswer     -> "Short Answer"
        }

        // Show MCQ options
        holder.optionsContainer.removeAllViews()
        if (q is QuizQuestion.MCQ) {
            holder.optionsContainer.visibility = View.VISIBLE
            q.options.forEachIndexed { i, option ->
                val optLabel = ('A' + i).toString()
                val tv = TextView(holder.itemView.context).apply {
                    text = "$optLabel. $option"
                    textSize = 12f
                    setTextColor(if (option == q.correctAnswer) 0xFF2E7D32.toInt() else 0xFF424242.toInt())
                    setPadding(0, 2, 0, 2)
                }
                holder.optionsContainer.addView(tv)
            }
        } else {
            holder.optionsContainer.visibility = View.GONE
        }

        // Sample answer for short answer
        if (q is QuizQuestion.ShortAnswer && q.sampleAnswer.isNotBlank()) {
            holder.sampleAnswer.text = "Sample: ${q.sampleAnswer}"
            holder.sampleAnswer.visibility = View.VISIBLE
        } else {
            holder.sampleAnswer.visibility = View.GONE
        }

        // Sync checkbox without re-firing listener
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = kept[position]
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            kept[position] = isChecked
            onSelectionChanged(kept.count { it })
        }
    }

    fun getKeptQuestions(): List<QuizQuestion> =
        questions.filterIndexed { i, _ -> kept[i] }

    fun areAllSelected(): Boolean = kept.all { it }

    fun setAllSelected(selected: Boolean) {
        kept.fill(selected)
        notifyDataSetChanged()
        onSelectionChanged(kept.count { it })
    }
}
