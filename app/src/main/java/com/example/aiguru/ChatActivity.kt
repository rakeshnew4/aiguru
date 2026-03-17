package com.example.aiguru

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbarwidget.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.example.aiguru.adapters.MessageAdapter
import com.example.aiguru.models.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var toolbar: MaterialToolbar
    private lateinit var loadingLayout: LinearLayout

    private val client = OkHttpClient()
    private val API_KEY = BuildConfig.GROQ_API_KEY
    private val API_URL = "https://api.groq.com/openai/v1/chat/completions"

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var subjectName: String
    private lateinit var chapterName: String
    
    private var imagePath: String? = null
    private var imageBase64: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        subjectName = intent.getStringExtra("subjectName") ?: "Unknown"
        chapterName = intent.getStringExtra("chapterName") ?: "Unknown Chapter"
        imagePath = intent.getStringExtra("imagePath")

        initializeUI()
        imagePath?.let { loadImageAsBase64(it) }
        loadChatHistory()
        addWelcomeMessage()
    }

    private fun initializeUI() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.title = chapterName
        toolbar.setNavigationOnClickListener { finish() }

        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageAdapter = MessageAdapter(
            context = this,
            onVoiceClick = { message -> playVoice(message) },
            onImageClick = { message -> viewImage(message) }
        )
        messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }

        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        loadingLayout = findViewById(R.id.loadingLayout)

        setupQuickActionButtons()
        setupMediaButtons()

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                messageInput.setText("")
            }
        }

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sendButton.isEnabled = s?.toString()?.trim()?.isNotEmpty() == true
            }
        })
    }

    private fun setupQuickActionButtons() {
        findViewById<MaterialButton>(R.id.summarizeButton).setOnClickListener {
            sendMessage("Please summarize this chapter in 5-7 key points")
        }
        findViewById<MaterialButton>(R.id.explainButton).setOnClickListener {
            sendMessage("Please explain the main concepts in this chapter in simple terms")
        }
        findViewById<MaterialButton>(R.id.quizButton).setOnClickListener {
            sendMessage("Create 5 quiz questions with multiple choice options and answers")
        }
        findViewById<MaterialButton>(R.id.notesButton).setOnClickListener {
            sendMessage("Make short, concise revision notes for exam preparation")
        }
    }

    private fun setupMediaButtons() {
        findViewById<MaterialButton>(R.id.voiceButton).setOnClickListener {
            Toast.makeText(this, "Voice input coming soon!", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.imageButton).setOnClickListener {
            Toast.makeText(this, "Image upload coming soon!", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.pdfButton).setOnClickListener {
            Toast.makeText(this, "PDF upload coming soon!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImageAsBase64(path: String) {
        try {
            val uri = Uri.parse(path)
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            imageBase64 = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            imageBase64 = null
        }
    }

    private fun loadChatHistory() {
        val userId = auth.currentUser?.uid ?: return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.collection("users").document(userId)
                    .collection("chats")
                    .document("${subjectName}_${chapterName}")
                    .collection("messages")
                    .orderBy("timestamp")
                    .limit(50)
                    .get()
                    .addOnSuccess { snapshot ->
                        val messages = snapshot.documents.mapNotNull { doc ->
                            try {
                                Message(
                                    id = doc.id,
                                    content = doc.getString("content") ?: "",
                                    isUser = doc.getBoolean("isUser") ?: true,
                                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                    imageUrl = doc.getString("imageUrl"),
                                    messageType = Message.MessageType.valueOf(
                                        doc.getString("messageType") ?: "TEXT"
                                    )
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (messages.isNotEmpty()) {
                            runOnUiThread {
                                messageAdapter.addMessages(messages)
                                messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                            }
                        }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun addWelcomeMessage() {
        val welcomeMessage = Message(
            id = UUID.randomUUID().toString(),
            content = "👋 Hi! I'm AI Guru. I'm here to help you master $chapterName. " +
                    "Use the quick buttons above or ask me anything about the topic!",
            isUser = false,
            messageType = Message.MessageType.TEXT
        )
        messageAdapter.addMessage(welcomeMessage)
    }

    private fun sendMessage(userText: String) {
        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            content = userText,
            isUser = true,
            messageType = Message.MessageType.TEXT
        )
        messageAdapter.addMessage(userMessage)
        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)

        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val context = "You are AI Guru, a helpful education tutor for students. " +
                        "Subject: $subjectName, Chapter: $chapterName. " +
                        "Answer in simple, clear language suitable for students. " +
                        "If asked to summarize or explain, be thorough but easy to understand."

                val json = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", context)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userText)
                        })
                    })
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            showLoading(false)
                            val errorMessage = Message(
                                id = UUID.randomUUID().toString(),
                                content = "Error: Unable to get response. Please check your internet connection.",
                                isUser = false,
                                messageType = Message.MessageType.TEXT
                            )
                            messageAdapter.addMessage(errorMessage)
                            messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        runOnUiThread {
                            showLoading(false)
                        }
                        val responseBody = response.body?.string()
                        try {
                            val jsonResponse = JSONObject(responseBody ?: "")
                            val aiText = jsonResponse
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")

                            val aiMessage = Message(
                                id = UUID.randomUUID().toString(),
                                content = aiText,
                                isUser = false,
                                messageType = Message.MessageType.TEXT
                            )

                            runOnUiThread {
                                messageAdapter.addMessage(aiMessage)
                                messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                                saveMessageToFirestore(userMessage)
                                saveMessageToFirestore(aiMessage)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@ChatActivity,
                                    "Error parsing response",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                runOnUiThread {
                    showLoading(false)
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveMessageToFirestore(message: Message) {
        val userId = auth.currentUser?.uid ?: return
        val messageData = hashMapOf(
            "content" to message.content,
            "isUser" to message.isUser,
            "timestamp" to message.timestamp,
            "messageType" to message.messageType.name
        )

        db.collection("users").document(userId)
            .collection("chats")
            .document("${subjectName}_${chapterName}")
            .collection("messages")
            .document(message.id)
            .set(messageData)
    }

    private fun showLoading(show: Boolean) {
        runOnUiThread {
            loadingLayout.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun playVoice(message: Message) {
        Toast.makeText(this, "Voice playback coming soon!", Toast.LENGTH_SHORT).show()
    }

    private fun viewImage(message: Message) {
        Toast.makeText(this, "Image viewer coming soon!", Toast.LENGTH_SHORT).show()
    }
}