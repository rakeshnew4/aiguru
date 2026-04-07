package com.example.aiguru

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.adapters.MessageAdapter
import com.example.aiguru.chat.ChatHistoryRepository
import com.example.aiguru.chat.ServerProxyClient
import com.example.aiguru.config.AdminConfigRepository
import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.models.Message
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Lightweight chat tab embedded inside ChapterActivity.
 * Loads and displays conversation history for this chapter, sends text messages
 * to the AI backend, and streams responses back.
 *
 * "Open Full Chat" button launches ChatActivity for voice, images, quiz, etc.
 */
class ChatFragment : Fragment() {

    companion object {
        fun newInstance(subjectName: String, chapterName: String) = ChatFragment().apply {
            arguments = Bundle().apply {
                putString("subjectName", subjectName)
                putString("chapterName", chapterName)
            }
        }
    }

    private lateinit var subjectName: String
    private lateinit var chapterName: String
    private lateinit var userId: String
    private lateinit var historyRepo: ChatHistoryRepository

    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var chatInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var loadingText: TextView

    private var isLoading = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chat_tab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        subjectName = arguments?.getString("subjectName") ?: "Subject"
        chapterName = arguments?.getString("chapterName") ?: "Chapter"
        userId      = SessionManager.getFirestoreUserId(requireContext())
        historyRepo = ChatHistoryRepository(userId, subjectName, chapterName)

        messagesRecyclerView = view.findViewById(R.id.chatMessagesRecyclerView)
        chatInput    = view.findViewById(R.id.chatInput)
        sendButton   = view.findViewById(R.id.chatSendButton)
        loadingText  = view.findViewById(R.id.chatLoadingText)

        messageAdapter = MessageAdapter(
            context        = requireContext(),
            onVoiceClick   = { },
            onStopClick    = { },
            onImageClick   = { },
            onExplainClick = { }
        )
        messagesRecyclerView.layoutManager =
            LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        messagesRecyclerView.adapter = messageAdapter

        // "Open Full Chat" passes any drafted text as autoPrompt to ChatActivity
//        view.findViewById<MaterialButton>(R.id.openFullChatButton).setOnClickListener {
//            val draft = chatInput.text.toString().trim()
//            startActivity(
//                Intent(requireContext(), ChatActivity::class.java)
//                    .putExtra("subjectName", subjectName)
//                    .putExtra("chapterName", chapterName)
//                    .also { if (draft.isNotBlank()) it.putExtra("autoPrompt", draft) }
//            )
//        }

        sendButton.setOnClickListener {
            val text = chatInput.text.toString().trim()
            if (text.isNotBlank() && !isLoading) {
                chatInput.setText("")
                sendMessage(text)
            }
        }

        // Allow Enter key to send
        chatInput.setOnEditorActionListener { _, _, _ ->
            val text = chatInput.text.toString().trim()
            if (text.isNotBlank() && !isLoading) { chatInput.setText(""); sendMessage(text) }
            true
        }

        // Prime the admin config (cached, non-blocking)
        AdminConfigRepository.fetchIfStale()

        // Load conversation history
        historyRepo.loadHistory(
            onMessages = { msgs ->
                messageAdapter.addMessages(msgs)
                scrollToBottom()
            },
            onEmpty = { /* fresh chat — no welcome message needed here */ }
        )
    }

    private fun sendMessage(text: String) {
        isLoading = true
        loadingText.visibility = View.VISIBLE
        sendButton.isEnabled = false

        val userMsg = Message(
            id        = UUID.randomUUID().toString(),
            content   = text,
            isUser    = true,
            timestamp = System.currentTimeMillis()
        )
        messageAdapter.addMessage(userMsg)
        historyRepo.saveMessage(userMsg)
        scrollToBottom()

        // Build history for the AI (last 20 turns)
        val history = messageAdapter.getMessages().dropLast(1).takeLast(20).map { msg ->
            if (msg.isUser) "user: ${msg.content}" else "assistant: ${msg.content}"
        }

        // Placeholder streaming message
        val aiMsgId = UUID.randomUUID().toString()
        val aiMsg = Message(id = aiMsgId, content = "…", isUser = false,
            timestamp = System.currentTimeMillis())
        messageAdapter.addMessage(aiMsg)
        scrollToBottom()

        val pageId = "${FirestoreManager.safeId(subjectName)}__${FirestoreManager.safeId(chapterName)}"
        val cfg    = AdminConfigRepository.config
        val client = ServerProxyClient(
            serverUrl = cfg.serverUrl.ifBlank { "http://108.181.187.227:8003" },
            modelName = "",
            apiKey    = cfg.serverApiKey,
            userId    = userId
        )

        val acc = StringBuilder()
        lifecycleScope.launch(Dispatchers.IO) {
            client.streamChat(
                question = text,
                pageId   = pageId,
                history  = history,
                onToken  = { token ->
                    acc.append(token)
                    requireActivity().runOnUiThread {
                        messageAdapter.updateMessage(aiMsgId, acc.toString())
                        scrollToBottom()
                    }
                },
                onDone = { _, _, _ ->
                    val saved = aiMsg.copy(content = acc.toString())
                    historyRepo.saveMessage(saved)
                    requireActivity().runOnUiThread {
                        isLoading = false
                        loadingText.visibility = View.GONE
                        sendButton.isEnabled = true
                    }
                },
                onError = { err ->
                    requireActivity().runOnUiThread {
                        messageAdapter.updateMessage(aiMsgId, "⚠️ $err")
                        isLoading = false
                        loadingText.visibility = View.GONE
                        sendButton.isEnabled = true
                        Toast.makeText(requireContext(),
                            "Connection error. Check internet & try again.",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    private fun scrollToBottom() {
        val count = messageAdapter.itemCount
        if (count > 0) messagesRecyclerView.scrollToPosition(count - 1)
    }
}
