package com.example.aiguru.adapters

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.R
import com.example.aiguru.models.Message
import com.google.android.material.imageview.ShapeableImageView
import com.squareup.picasso.Picasso
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val context: Context,
    private val messages: MutableList<Message> = mutableListOf(),
    private val onVoiceClick: (Message) -> Unit = {},
    private val onImageClick: (Message) -> Unit = {},
    private val onRetry: (Message) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val dp = context.resources.displayMetrics.density

    inner class MessageViewHolder(root: LinearLayout) : RecyclerView.ViewHolder(root) {
        private val root: LinearLayout = root

        fun bind(message: Message) {
            root.removeAllViews()
            val isUser = message.isUser

            // --- Bubble ---
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * dp).toInt()
                    bottomMargin = (2 * dp).toInt()
                    gravity = if (isUser) Gravity.END else Gravity.START
                    if (isUser) {
                        marginStart = (64 * dp).toInt()
                        marginEnd   = (12 * dp).toInt()
                    } else {
                        marginStart = (12 * dp).toInt()
                        marginEnd   = (64 * dp).toInt()
                    }
                }
                setPadding(
                    (14 * dp).toInt(), (10 * dp).toInt(),
                    (14 * dp).toInt(), (10 * dp).toInt()
                )
                setBackgroundResource(
                    if (isUser) R.drawable.bg_user_bubble
                    else R.drawable.bg_ai_bubble
                )
            }

            // --- Image thumbnail (for IMAGE messages) ---
            if (message.messageType == Message.MessageType.IMAGE && message.imageUrl != null) {
                val img = ShapeableImageView(context).apply {
                    val sz = (160 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        bottomMargin = if (message.content.isEmpty()) 0 else (8 * dp).toInt()
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                        .setAllCornerSizes(8f * dp).build()
                    setOnClickListener { onImageClick(message) }
                }
                Picasso.get().load(message.imageUrl).into(img)
                bubble.addView(img)
            }

            // --- Text content with markdown rendering ---
            if (message.content.isNotEmpty()) {
                val textColor = if (isUser) Color.WHITE
                else ContextCompat.getColor(context, R.color.text_primary)

                val msgText = TextView(context).apply {
                    text = parseMarkdown(message.content)
                    textSize = 15f
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setLineSpacing(2f * dp, 1f)
                }
                bubble.addView(msgText)
            }

            // --- "Read aloud" button for AI messages ---
            if (!isUser) {
                val speakRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (6 * dp).toInt() }
                }
                val speakBtn = TextView(context).apply {
                    text = "🔊"
                    textSize = 15f
                    setPadding(
                        (8 * dp).toInt(), (2 * dp).toInt(),
                        (2 * dp).toInt(), (2 * dp).toInt()
                    )
                    setOnClickListener { onVoiceClick(message) }
                }
                speakRow.addView(speakBtn)
                bubble.addView(speakRow)
            }

            root.addView(bubble)

            // --- Timestamp ---
            val timeText = TextView(context).apply {
                text = formatTime(message.timestamp)
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin    = (2 * dp).toInt()
                    bottomMargin = (4 * dp).toInt()
                    gravity = if (isUser) Gravity.END else Gravity.START
                    if (isUser) marginEnd   = (14 * dp).toInt()
                    else        marginStart = (16 * dp).toInt()
                }
            }
            root.addView(timeText)
        }

        /**
         * Renders basic Markdown:
         *  - Lines starting with ##/# → bold, slightly larger
         *  - Lines starting with - or * (followed by space) → bullet •
         *  - **text** → bold span
         */
        private fun parseMarkdown(text: String): SpannableStringBuilder {
            val ssb = SpannableStringBuilder()
            val lines = text.lines()
            for ((idx, rawLine) in lines.withIndex()) {
                val trimmed = rawLine.trim()
                val isHeading = trimmed.startsWith("## ") || trimmed.startsWith("# ")
                val processedText = when {
                    trimmed.startsWith("## ") -> "📌 " + trimmed.removePrefix("## ")
                    trimmed.startsWith("# ")  -> trimmed.removePrefix("# ")
                    trimmed.startsWith("- ")  -> "  •  " + trimmed.removePrefix("- ")
                    trimmed.startsWith("* ") && !trimmed.startsWith("**") ->
                        "  •  " + trimmed.removePrefix("* ")
                    trimmed.startsWith("• ")  -> "  " + trimmed
                    else -> rawLine
                }

                // Apply **bold** spans
                val result = SpannableStringBuilder()
                var lastEnd = 0
                val boldRegex = Regex("""\*\*(.+?)\*\*""")
                for (match in boldRegex.findAll(processedText)) {
                    result.append(processedText.substring(lastEnd, match.range.first))
                    val start = result.length
                    result.append(match.groupValues[1])
                    result.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start, result.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    lastEnd = match.range.last + 1
                }
                result.append(processedText.substring(lastEnd))

                // Heading → bold + slightly bigger
                if (isHeading) {
                    result.setSpan(StyleSpan(Typeface.BOLD), 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    result.setSpan(RelativeSizeSpan(1.1f), 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                ssb.append(result)
                if (idx < lines.size - 1) ssb.append('\n')
            }
            return ssb
        }

        private fun formatTime(timestamp: Long): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return MessageViewHolder(layout)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun addMessages(newMessages: List<Message>) {
        val startPosition = messages.size
        messages.addAll(newMessages)
        notifyItemRangeInserted(startPosition, newMessages.size)
    }

    fun updateMessage(index: Int, message: Message) {
        if (index in messages.indices) {
            messages[index] = message
            notifyItemChanged(index)
        }
    }

    fun removeMessage(index: Int) {
        if (index in messages.indices) {
            messages.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun getMessages(): List<Message> = messages.toList()

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }
}
