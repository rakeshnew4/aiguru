package com.example.aiguru.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.R
import com.example.aiguru.models.Message
import com.google.android.material.imageview.ShapeableImageView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val context: Context,
    private val messages: MutableList<Message> = mutableListOf(),
    private val onVoiceClick: (Message) -> Unit = {},
    private val onStopClick: (Message) -> Unit = {},
    private val onImageClick: (Message) -> Unit = {},
    private val onRetry: (Message) -> Unit = {},
    private val onExplainClick: (Message) -> Unit = {}
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

            // --- Bubble ---
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = if (isUser) {
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (56 * dp).toInt()
                        marginEnd   = (8 * dp).toInt()
                    }
                } else {
                    // ChatGPT-like: AI replies use near full-width readable column
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (4 * dp).toInt()
                        marginEnd = (4 * dp).toInt()
                    }
                }
                if (isUser) {
                    setPadding(
                        (14 * dp).toInt(), (10 * dp).toInt(),
                        (14 * dp).toInt(), (10 * dp).toInt()
                    )
                    setBackgroundResource(R.drawable.bg_user_bubble)
                } else {
                    setPadding((2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt())
                    setBackgroundColor(Color.TRANSPARENT)
                }
            }

            // --- Image thumbnail (gallery photo, camera, or PDF page) ---
            if (message.imageUrl != null) {
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
                try {
                    Glide.with(context).load(message.imageUrl).centerCrop().into(img)
                } catch (_: Exception) {}
                bubble.addView(img)
            }

            // --- Text content ---
            if (message.content.isNotEmpty()) {
                val textColor = Color.parseColor("#111827")
                val msgText = TextView(context).apply {
                    text = parseMarkdown(message.content)
                    textSize = if (isUser) 16f else 17f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(
                        if (isUser) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setLineSpacing(0f, 1.3f)
                }
                bubble.addView(msgText)
            }

            // --- Speak / Stop buttons for AI messages ---
            if (!isUser) {
                val speakRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (6 * dp).toInt() }
                }

                fun actionButton(label: String, onTap: () -> Unit): TextView {
                    return TextView(context).apply {
                        text = label
                        textSize = 13f
                        setTextColor(Color.parseColor("#6B7280"))
                        setPadding((6 * dp).toInt(), (3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (8 * dp).toInt() }
                        setOnClickListener { onTap() }
                    }
                }

                val copyBtn = actionButton("⧉") {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("message", message.content))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
                val speakBtn = actionButton("🔊") { onVoiceClick(message) }
                val stopBtn = actionButton("■") { onStopClick(message) }
                val explainBtn = actionButton("BB") { onExplainClick(message) }.apply {
                    setTextColor(Color.WHITE)
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 10f * dp
                        setColor(Color.parseColor("#111827"))
                    }
                    background = bg
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                }

                speakRow.addView(copyBtn)
                speakRow.addView(speakBtn)
                speakRow.addView(stopBtn)
                speakRow.addView(explainBtn)
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
                    else        marginStart = (8 * dp).toInt()
                }
            }
            root.addView(timeText)
        }

        /**
         * Clean Markdown renderer with ChatGPT-like defaults:
         * - # / ## / ### headings (no emoji prefixes)
         * - bullet and numbered lists
         * - fenced code blocks ```...```
         * - **bold**, *italic*, `code`, ~~strike~~
         */
        private fun parseMarkdown(text: String): SpannableStringBuilder {
            val out = SpannableStringBuilder()
            val lines = text.lines()
            var i = 0

            while (i < lines.size) {
                val rawLine = lines[i]
                val trimmed = rawLine.trim()

                // Fenced code block
                if (trimmed.startsWith("```")) {
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code.append(lines[i])
                        if (i < lines.lastIndex) code.append('\n')
                        i++
                    }
                    val start = out.length
                    out.append(code.toString().trimEnd())
                    val end = out.length
                    if (start < end) {
                        out.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(Color.parseColor("#ECEFF3")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(Color.parseColor("#111827")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(RelativeSizeSpan(0.93f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (i < lines.lastIndex) out.append("\n\n")
                    i++
                    continue
                }

                val headingLevel = when {
                    trimmed.startsWith("### ") -> 3
                    trimmed.startsWith("## ") -> 2
                    trimmed.startsWith("# ") -> 1
                    else -> 0
                }

                val lineText = when {
                    headingLevel == 1 -> trimmed.removePrefix("# ")
                    headingLevel == 2 -> trimmed.removePrefix("## ")
                    headingLevel == 3 -> trimmed.removePrefix("### ")
                    trimmed.startsWith("- ") -> "• " + trimmed.removePrefix("- ")
                    trimmed.startsWith("* ") && !trimmed.startsWith("**") -> "• " + trimmed.removePrefix("* ")
                    trimmed == "---" || trimmed == "___" -> "────────────"
                    else -> rawLine
                }

                val lineSpanned = applyInlineSpans(lineText)
                if (headingLevel > 0) {
                    val size = when (headingLevel) {
                        1 -> 1.18f
                        2 -> 1.11f
                        else -> 1.06f
                    }
                    lineSpanned.setSpan(StyleSpan(Typeface.BOLD), 0, lineSpanned.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    lineSpanned.setSpan(RelativeSizeSpan(size), 0, lineSpanned.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                out.append(lineSpanned)
                if (i < lines.lastIndex) out.append('\n')
                i++
            }

            return out
        }

        private fun applyInlineSpans(text: String): SpannableStringBuilder {
            data class Span(val start: Int, val end: Int, val inner: String, val type: String)
            val spans = mutableListOf<Span>()

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
                        result.setSpan(BackgroundColorSpan(Color.parseColor("#ECEFF3")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(ForegroundColorSpan(Color.parseColor("#111827")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(RelativeSizeSpan(0.92f), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    "super"  -> {
                        result.setSpan(SuperscriptSpan(), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(RelativeSizeSpan(0.72f), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(ForegroundColorSpan(Color.parseColor("#374151")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    "sub"    -> {
                        result.setSpan(SubscriptSpan(), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(RelativeSizeSpan(0.72f), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(ForegroundColorSpan(Color.parseColor("#374151")), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
