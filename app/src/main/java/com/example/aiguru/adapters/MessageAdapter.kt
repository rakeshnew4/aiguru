package com.example.aiguru.adapters

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
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
    private val onStopClick: (Message) -> Unit = {},
    private val onImageClick: (Message) -> Unit = {},
    private val onRetry: (Message) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val dp = context.resources.displayMetrics.density

    inner class MessageViewHolder(root: LinearLayout) : RecyclerView.ViewHolder(root) {
        private val root: LinearLayout = root

        fun bind(message: Message) {
            root.removeAllViews()
            val isUser = message.isUser

            // Row container: [avatar] [bubble] for AI  /  [bubble] for user (right-aligned)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin    = (4 * dp).toInt()
                    bottomMargin = (2 * dp).toInt()
                }
                gravity = if (isUser) Gravity.END else Gravity.START
            }

            // AI avatar circle
            if (!isUser) {
                val avatar = TextView(context).apply {
                    text = "AI"
                    textSize = 9f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_avatar_circle)
                    val sz = (32 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = (6 * dp).toInt()
                        topMargin = (4 * dp).toInt()
                    }
                }
                row.addView(avatar)
            }

            // --- Bubble ---
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (isUser) {
                        marginStart = (56 * dp).toInt()
                        marginEnd   = (8 * dp).toInt()
                    } else {
                        marginEnd = (56 * dp).toInt()
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

            // --- Image thumbnail ---
            if (message.messageType == Message.MessageType.IMAGE && message.imageUrl != null) {
                val img = ShapeableImageView(context).apply {
                    val sz = (160 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        bottomMargin = if (message.content.isEmpty()) 0 else (8 * dp).toInt()
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                        .setAllCornerSizes(10f * dp).build()
                    setOnClickListener { onImageClick(message) }
                }
                Picasso.get().load(message.imageUrl).into(img)
                bubble.addView(img)
            }

            // --- Text content ---
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

            // --- Speak / Stop buttons for AI messages ---
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
                    text = "▶ Speak"
                    textSize = 11f
                    setTextColor(Color.parseColor("#6B7280"))
                    setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                    setOnClickListener { onVoiceClick(message) }
                }
                val stopBtn = TextView(context).apply {
                    text = "■ Stop"
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (4 * dp).toInt() }
                    setOnClickListener { onStopClick(message) }
                }
                speakRow.addView(speakBtn)
                speakRow.addView(stopBtn)
                bubble.addView(speakRow)
            }

            row.addView(bubble)
            root.addView(row)

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
                    gravity      = if (isUser) Gravity.END else Gravity.START
                    if (isUser) marginEnd   = (12 * dp).toInt()
                    else        marginStart = (44 * dp).toInt()   // align with bubble (past avatar)
                }
            }
            root.addView(timeText)
        }

        /**
         * Enhanced Markdown renderer supporting:
         *  - ### / ## / # headings with color
         *  - - / * bullets → • 
         *  - --- divider
         *  - **bold**, *italic*, `code`, ~~strikethrough~~
         *  - ^2 / ^{n+1}  → SuperscriptSpan  (math: x², aⁿ)
         *  - _{2} / chemical H_2O → SubscriptSpan  (chemistry: H₂O, CO₂)
         *  - $$...$$ inline math block (highlighted purple box)
         */
        private fun parseMarkdown(text: String): SpannableStringBuilder {
            val ssb = SpannableStringBuilder()
            val lines = text.lines()

            for ((idx, rawLine) in lines.withIndex()) {
                val trimmed = rawLine.trim()

                // Detect heading
                val headingLevel = when {
                    trimmed.startsWith("### ") -> 3
                    trimmed.startsWith("## ")  -> 2
                    trimmed.startsWith("# ")   -> 1
                    else -> 0
                }
                val headingSize = when (headingLevel) {
                    1 -> 1.22f
                    2 -> 1.14f
                    3 -> 1.07f
                    else -> 1f
                }
                val headingColor = when (headingLevel) {
                    1 -> Color.parseColor("#1A237E")
                    2 -> Color.parseColor("#283593")
                    3 -> Color.parseColor("#1565C0")
                    else -> -1
                }

                // Transform line prefix
                val lineText = when {
                    headingLevel == 1 -> "🎯 " + trimmed.removePrefix("# ")
                    headingLevel == 2 -> "📌 " + trimmed.removePrefix("## ")
                    headingLevel == 3 -> "  📎 " + trimmed.removePrefix("### ")
                    trimmed.startsWith("- ")  -> "  •  " + trimmed.removePrefix("- ")
                    trimmed.startsWith("* ") && !trimmed.startsWith("**") ->
                        "  •  " + trimmed.removePrefix("* ")
                    trimmed.startsWith("• ")  -> "  " + trimmed
                    trimmed.startsWith("1. ") || trimmed.matches(Regex("^\\d+\\. .*")) -> "  " + rawLine.trim()
                    trimmed == "---" || trimmed == "___" -> "─────────────────────"
                    else -> rawLine
                }

                val result = applyInlineSpans(lineText)

                // Apply heading styles
                if (headingLevel > 0) {
                    result.setSpan(StyleSpan(Typeface.BOLD), 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    result.setSpan(RelativeSizeSpan(headingSize), 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    result.setSpan(ForegroundColorSpan(headingColor), 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                ssb.append(result)
                if (idx < lines.size - 1) ssb.append('\n')
            }
            return ssb
        }

        /**
         * Applies inline spans: bold, italic, code, strikethrough,
         * superscript (^2 / ^{expr}), subscript (_{n} / chemical H_2O),
         * and math blocks ($$..$$).
         */
        private fun applyInlineSpans(text: String): SpannableStringBuilder {
            data class Span(val start: Int, val end: Int, val inner: String, val type: String)
            val spans = mutableListOf<Span>()

            // Math blocks: $$...$$ → purple highlighted box
            Regex("""\$\$(.+?)\$\$""").findAll(text).forEach {
                spans.add(Span(it.range.first, it.range.last + 1, it.groupValues[1], "math"))
            }
            // Bold: **text**
            Regex("""\*\*(.+?)\*\*""").findAll(text).forEach {
                spans.add(Span(it.range.first, it.range.last + 1, it.groupValues[1], "bold"))
            }
            // Italic: *text* (not **)
            Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""").findAll(text).forEach {
                spans.add(Span(it.range.first, it.range.last + 1, it.groupValues[1], "italic"))
            }
            // Strikethrough: ~~text~~
            Regex("""~~(.+?)~~""").findAll(text).forEach {
                spans.add(Span(it.range.first, it.range.last + 1, it.groupValues[1], "strike"))
            }
            // Code: `text`
            Regex("""`([^`]+)`""").findAll(text).forEach {
                spans.add(Span(it.range.first, it.range.last + 1, it.groupValues[1], "code"))
            }
            // Superscript: ^{expr} or ^digit+
            Regex("""\^\{([^}]+)\}|\^([0-9+\-nNxX]+)""").findAll(text).forEach { m ->
                val inner = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
                spans.add(Span(m.range.first, m.range.last + 1, inner, "super"))
            }
            // Subscript: chemical H_2O style or _{expr}
            Regex("""_\{([^}]+)\}|(?<=[A-Za-z])_([0-9]+)""").findAll(text).forEach { m ->
                val inner = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
                spans.add(Span(m.range.first, m.range.last + 1, inner, "sub"))
            }

            spans.sortBy { it.start }

            val result = SpannableStringBuilder()
            var lastEnd = 0
            for (span in spans) {
                if (span.start < lastEnd) continue  // overlapping, skip
                result.append(text.substring(lastEnd, span.start))
                val sStart = result.length
                result.append(span.inner)
                val sEnd = result.length
                when (span.type) {
                    "bold"   -> result.setSpan(StyleSpan(Typeface.BOLD), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    "italic" -> result.setSpan(StyleSpan(Typeface.ITALIC), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    "strike" -> result.setSpan(android.text.style.StrikethroughSpan(), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    "code"   -> {
                        result.setSpan(TypefaceSpan("monospace"), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(BackgroundColorSpan(Color.parseColor("#F1F3F4")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(ForegroundColorSpan(Color.parseColor("#C62828")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(RelativeSizeSpan(0.92f), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    "super"  -> {
                        result.setSpan(SuperscriptSpan(), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(RelativeSizeSpan(0.72f), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(ForegroundColorSpan(Color.parseColor("#6A1B9A")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    "sub"    -> {
                        result.setSpan(SubscriptSpan(), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(RelativeSizeSpan(0.72f), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(ForegroundColorSpan(Color.parseColor("#00695C")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    "math"   -> {
                        result.setSpan(TypefaceSpan("monospace"), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(BackgroundColorSpan(Color.parseColor("#F5F0FF")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(ForegroundColorSpan(Color.parseColor("#7B1FA2")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(StyleSpan(Typeface.BOLD), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                lastEnd = span.end
            }
            result.append(text.substring(lastEnd))
            return result
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

    /** Update a streaming message by its id with new accumulated content. */
    fun updateMessage(msgId: String, newContent: String) {
        val index = messages.indexOfFirst { it.id == msgId }
        if (index >= 0) {
            messages[index] = messages[index].copy(content = newContent)
            notifyItemChanged(index)
        }
    }

    fun hasMessage(msgId: String): Boolean = messages.any { it.id == msgId }

    fun removeMessage(index: Int) {
        if (index in messages.indices) {
            messages.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun getMessages(): List<Message> = messages.toList()

    fun getLastAIMessage(): Message? = messages.lastOrNull { !it.isUser }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }
}
