package com.example.aiguru.adapters

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.R
import com.example.aiguru.models.Message
import com.google.android.material.card.MaterialCardView
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

    inner class MessageViewHolder(itemView: LinearLayout) : RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView
        
        fun bind(message: Message) {
            container.removeAllViews()
            
            val isUser = message.isUser
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
                if (isUser) {
                    marginStart = 60
                    setMargins(60, 8, 16, 8)
                } else {
                    marginStart = 16
                    setMargins(16, 8, 60, 8)
                }
            }

            // Create card for message
            val card = MaterialCardView(context).apply {
                layoutParams = params
                cardElevation = 2f
                radius = 12f
                setCardBackgroundColor(
                    if (isUser) 
                        ContextCompat.getColor(context, R.color.design_default_color_primary)
                    else 
                        Color.parseColor("#F0F0F0")
                )
                cardCornerRadius = 12f
            }

            val messageLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setPadding(16, 12, 16, 12)
                }
                setPadding(16, 12, 16, 12)
            }

            // Text content
            when (message.messageType) {
                Message.MessageType.TEXT -> bindTextMessage(messageLayout, message, isUser)
                Message.MessageType.IMAGE -> bindImageMessage(messageLayout, message, isUser)
                Message.MessageType.VOICE -> bindVoiceMessage(messageLayout, message, isUser)
                Message.MessageType.PDF -> bindPdfMessage(messageLayout, message, isUser)
                Message.MessageType.MIXED -> bindMixedMessage(messageLayout, message, isUser)
            }

            card.addView(messageLayout)
            container.addView(card, params)

            // Add timestamp
            val timeView = TextView(context).apply {
                text = formatTime(message.timestamp)
                textSize = 10f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4
                    gravity = if (isUser) Gravity.END else Gravity.START
                    setMargins(if (isUser) 60 else 16, 4, if (isUser) 16 else 60, 0)
                }
            }
            container.addView(timeView)
        }

        private fun bindTextMessage(
            container: LinearLayout,
            message: Message,
            isUser: Boolean
        ) {
            val textView = TextView(context).apply {
                text = message.content
                textSize = 15f
                setTextColor(if (isUser) Color.WHITE else Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(textView)
        }

        private fun bindImageMessage(
            container: LinearLayout,
            message: Message,
            isUser: Boolean
        ) {
            // Thumbnail
            val imageView = ShapeableImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(200, 200).apply {
                    setMargins(0, 0, 0, 8)
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                radius = 8f
                setOnClickListener { onImageClick(message) }
            }
            
            message.imageUrl?.let {
                Picasso.get().load(it).into(imageView)
            }
            container.addView(imageView)

            // Caption if available
            if (message.content.isNotEmpty()) {
                val textView = TextView(context).apply {
                    text = message.content
                    textSize = 14f
                    setTextColor(if (isUser) Color.WHITE else Color.BLACK)
                }
                container.addView(textView)
            }
        }

        private fun bindVoiceMessage(
            container: LinearLayout,
            message: Message,
            isUser: Boolean
        ) {
            val voiceLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
            }

            val playButton = TextView(context).apply {
                text = "🔊 Play Voice"
                textSize = 14f
                setTextColor(if (isUser) Color.WHITE else Color.BLUE)
                setPadding(12, 8, 12, 8)
                setOnClickListener { onVoiceClick(message) }
            }
            voiceLayout.addView(playButton)
            container.addView(voiceLayout)

            // Caption
            if (message.content.isNotEmpty()) {
                val textView = TextView(context).apply {
                    text = message.content
                    textSize = 14f
                    setTextColor(if (isUser) Color.WHITE else Color.BLACK)
                }
                container.addView(textView)
            }
        }

        private fun bindPdfMessage(
            container: LinearLayout,
            message: Message,
            isUser: Boolean
        ) {
            val pdfLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
            }

            val pdfButton = TextView(context).apply {
                text = "📄 View PDF"
                textSize = 14f
                setTextColor(if (isUser) Color.WHITE else Color.BLUE)
                setPadding(12, 8, 12, 8)
            }
            pdfLayout.addView(pdfButton)
            container.addView(pdfLayout)

            // Filename
            if (message.content.isNotEmpty()) {
                val textView = TextView(context).apply {
                    text = message.content
                    textSize = 12f
                    setTextColor(if (isUser) Color.WHITE else Color.GRAY)
                }
                container.addView(textView)
            }
        }

        private fun bindMixedMessage(
            container: LinearLayout,
            message: Message,
            isUser: Boolean
        ) {
            // Display text-only for now, can be enhanced
            bindTextMessage(container, message, isUser)
        }

        private fun formatTime(timestamp: Long): String {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
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
