package com.example.aiguru
import com.example.aiguru.BuildConfig
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.example.aiguru.adapters.MessageAdapter
import com.example.aiguru.models.Flashcard
import com.example.aiguru.models.Message
import com.example.aiguru.utils.MediaManager
import com.example.aiguru.utils.TextToSpeechManager
import com.example.aiguru.utils.TTSCallback
import com.example.aiguru.utils.VoiceManager
import com.example.aiguru.utils.VoiceRecognitionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class ChatActivity : AppCompatActivity(), VoiceRecognitionCallback {

    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var toolbar: MaterialToolbar
    private lateinit var loadingLayout: LinearLayout
    private lateinit var voiceButton: MaterialButton
    private lateinit var imageButton: MaterialButton
    private lateinit var pdfButton: MaterialButton
    private lateinit var imagePreviewStrip: LinearLayout
    private lateinit var imagePreviewThumbnail: ImageView
    private lateinit var imagePreviewLabel: TextView
    private lateinit var removeImageButton: MaterialButton
    private lateinit var listeningIndicator: TextView

    private val client = OkHttpClient()
    private val API_KEY = BuildConfig.GROQ_API_KEY
    private val API_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val MODEL_TEXT = "llama-3.3-70b-versatile"
    private val MODEL_VISION = "meta-llama/llama-4-scout-17b-16e-instruct"

    private lateinit var db: FirebaseFirestore
    private lateinit var subjectName: String
    private lateinit var chapterName: String

    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var mediaManager: MediaManager

    private var selectedImageUri: Uri? = null
    private var cameraImageUri: Uri? = null
    private var isListening = false

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) showImagePreview(uri)
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) showImagePreview(cameraImageUri!!)
        }

    private val pickPdfLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) Toast.makeText(this, "PDF support coming soon", Toast.LENGTH_SHORT).show()
        }

    private val systemPrompt = """You are AI Guru, a friendly and encouraging educational tutor for school and college students.
Your goals:
- Explain concepts clearly with simple language and real-world examples
- Break down complex topics into easy-to-understand steps  
- Be patient, supportive, and always encouraging
- Use bullet points, numbered lists, and structure your answers clearly
- When asked for quizzes, format clearly with Q: and A: on separate lines
- When asked for notes, use headings (with ##) and well-organized sections
- Always relate new concepts to things the student already knows
- Keep responses focused and appropriately detailed for the topic"""

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        db = FirebaseFirestore.getInstance()
        subjectName = intent.getStringExtra("subjectName") ?: "General"
        chapterName = intent.getStringExtra("chapterName") ?: "Study Session"

        voiceManager = VoiceManager(this)
        ttsManager = TextToSpeechManager(this)
        mediaManager = MediaManager(this)

        initializeUI()
        loadChatHistory()
    }

    private fun initializeUI() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.title = chapterName
        toolbar.subtitle = subjectName
        toolbar.setNavigationOnClickListener { finish() }

        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageAdapter = MessageAdapter(
            context = this,
            onVoiceClick = { msg ->
                ttsManager.speak(msg.content, object : com.example.aiguru.utils.TTSCallback {
                    override fun onStart() {}
                    override fun onComplete() {}
                    override fun onError(error: String) {}
                })
            },
            onImageClick = { }
        )
        messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messagesRecyclerView.adapter = messageAdapter

        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        loadingLayout = findViewById(R.id.loadingLayout)
        voiceButton = findViewById(R.id.voiceButton)
        imageButton = findViewById(R.id.imageButton)
        pdfButton = findViewById(R.id.pdfButton)
        imagePreviewStrip = findViewById(R.id.imagePreviewStrip)
        imagePreviewThumbnail = findViewById(R.id.imagePreviewThumbnail)
        imagePreviewLabel = findViewById(R.id.imagePreviewLabel)
        removeImageButton = findViewById(R.id.removeImageButton)
        listeningIndicator = findViewById(R.id.listeningIndicator)

        setupButtons()
        setupQuickActions()

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            val hasImage = selectedImageUri != null
            if (text.isNotEmpty() || hasImage) {
                val prompt = text.ifEmpty {
                    "Please explain what you see in this image in the context of $chapterName."
                }
                sendMessage(prompt)
                messageInput.setText("")
            }
        }
    }

    private fun setupButtons() {
        voiceButton.setOnClickListener {
            if (isListening) {
                voiceManager.stopListening()
            } else {
                checkPermissionAndStartListening()
            }
        }

        imageButton.setOnClickListener { showImageSourceDialog() }
        pdfButton.setOnClickListener { openPdfPicker() }

        removeImageButton.setOnClickListener {
            selectedImageUri = null
            imagePreviewStrip.visibility = View.GONE
        }
    }

    private fun setupQuickActions() {
        findViewById<MaterialButton>(R.id.summarizeButton).setOnClickListener {
            sendMessage(
                "Please summarize the key points of \"$chapterName\" in a structured format with clear headings and bullet points."
            )
        }
        findViewById<MaterialButton>(R.id.explainButton).setOnClickListener {
            sendMessage(
                "Explain \"$chapterName\" in simple, easy-to-understand language. Use real-world examples and analogies to make it clear for a student."
            )
        }
        findViewById<MaterialButton>(R.id.quizButton).setOnClickListener {
            sendMessage(
                "Create 5 quiz questions about \"$chapterName\". For each question show the answer after it. Use this exact format:\nQ: [question]\nA: [answer]\n\nMake the questions test key concepts and definitions."
            )
        }
        findViewById<MaterialButton>(R.id.notesButton).setOnClickListener {
            sendMessage(
                "Create comprehensive study notes for \"$chapterName\" with:\n• Key concepts and definitions\n• Important facts to remember\n• Summary of main points\n• Any formulas or rules to know"
            )
        }
        findViewById<MaterialButton>(R.id.flashcardsButton).setOnClickListener {
            generateFlashcards()
        }
    }

    private fun checkPermissionAndStartListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else startVoiceInput()
    }

    private fun startVoiceInput() {
        isListening = true
        voiceButton.text = "⏹️"
        listeningIndicator.visibility = View.VISIBLE
        voiceManager.startListening(this)
    }

    private fun showImagePreview(uri: Uri) {
        selectedImageUri = uri
        imagePreviewStrip.visibility = View.VISIBLE
        Glide.with(this).load(uri).centerCrop().into(imagePreviewThumbnail)
        imagePreviewLabel.text = mediaManager.getFileInfo(uri)
    }

    private fun showImageSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add Image")
            .setItems(arrayOf("📷  Take Photo", "🖼️  Choose from Gallery")) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> pickImageLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE
            )
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraImageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        cameraImageUri?.let { cameraLauncher.launch(it) }
    }

    private fun openPdfPicker() = pickPdfLauncher.launch("application/pdf")

    private fun loadChatHistory() {
        db.collection("users").document("testuser123")
            .collection("chats")
            .document("${subjectName}_${chapterName}")
            .collection("messages")
            .orderBy("timestamp")
            .limit(50)
            .get()
            .addOnSuccessListener { snapshot ->
                val messages = snapshot.documents.mapNotNull {
                    try {
                        Message(
                            id = it.id,
                            content = it.getString("content") ?: "",
                            isUser = it.getBoolean("isUser") ?: true,
                            timestamp = it.getLong("timestamp") ?: System.currentTimeMillis(),
                            messageType = Message.MessageType.valueOf(
                                it.getString("messageType") ?: "TEXT"
                            )
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                if (messages.isEmpty()) {
                    addWelcomeMessage()
                } else {
                    messageAdapter.addMessages(messages)
                    messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                }
            }
            .addOnFailureListener { addWelcomeMessage() }
    }

    private fun addWelcomeMessage() {
        messageAdapter.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                content = "👋 Hello! I'm AI Guru, your personal tutor for **$chapterName** ($subjectName).\n\nUse the quick buttons above to Summarize, get an Explanation, take a Quiz, generate Study Notes, or create Flashcards. You can also ask me anything by typing or using your voice! 🎤",
                isUser = false,
                messageType = Message.MessageType.TEXT
            )
        )
    }

    private fun sendMessage(userText: String) {
        val imageUri = selectedImageUri
        selectedImageUri = null
        imagePreviewStrip.visibility = View.GONE

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            content = userText,
            isUser = true,
            imageUrl = imageUri?.toString(),
            messageType = if (imageUri != null) Message.MessageType.IMAGE else Message.MessageType.TEXT
        )
        messageAdapter.addMessage(userMessage)
        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val responseText = if (imageUri != null) {
                    val base64 = mediaManager.uriToBase64(imageUri)
                    if (base64 != null) callGroqAPIWithImage(userText, base64) else callGroqAPI(userText)
                } else {
                    callGroqAPI(userText)
                }

                saveMessageToFirestore(userMessage.copy(imageUrl = null))

                runOnUiThread {
                    showLoading(false)
                    if (responseText != null) {
                        val aiMessage = Message(UUID.randomUUID().toString(), responseText, false)
                        messageAdapter.addMessage(aiMessage)
                        saveMessageToFirestore(aiMessage)
                        messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                    } else {
                        showError("Couldn't get a response. Check your connection and try again.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showLoading(false)
                    showError("Error: ${e.message ?: "Unknown error"}")
                }
            }
        }
    }

    private fun callGroqAPI(userText: String): String? {
        val json = JSONObject().apply {
            put("model", MODEL_TEXT)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content",
                    "Subject: $subjectName | Chapter: $chapterName\n\n$userText"))
            })
            put("temperature", 0.7)
            put("max_tokens", 2048)
        }
        return executeGroqRequest(json)
    }

    private fun callGroqAPIWithImage(userText: String, base64Image: String): String? {
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Subject: $subjectName | Chapter: $chapterName\n\n$userText")
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
            })
        }
        val json = JSONObject().apply {
            put("model", MODEL_VISION)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", contentArray))
            })
            put("temperature", 0.7)
            put("max_tokens", 2048)
        }
        return executeGroqRequest(json)
    }

    private fun executeGroqRequest(json: JSONObject): String? {
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) return null
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: IOException) {
            null
        }
    }

    private fun generateFlashcards() {
        val prompt = """Generate exactly 5 educational flashcards for "$chapterName" (Subject: $subjectName).
Use ONLY this exact format with no extra text between cards:
Q: [question]
A: [answer]

Q: [question]
A: [answer]

Make questions test key concepts, definitions, or important facts."""

        messageAdapter.addMessage(
            Message(UUID.randomUUID().toString(),
                "🃏 Generating revision flashcards for $chapterName…", true)
        )
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val responseText = callGroqAPI(prompt)
            runOnUiThread {
                showLoading(false)
                if (responseText != null) {
                    val cards = parseFlashcards(responseText)
                    if (cards.isNotEmpty()) {
                        messageAdapter.addMessage(
                            Message(UUID.randomUUID().toString(),
                                "✅ ${cards.size} flashcards ready! Opening revision mode…", false)
                        )
                        startActivity(
                            Intent(this@ChatActivity, RevisionActivity::class.java)
                                .putExtra("flashcards", ArrayList(cards))
                        )
                    } else {
                        showError("Could not parse flashcards. Please try again.")
                    }
                } else {
                    showError("Failed to generate flashcards. Check your connection.")
                }
            }
        }
    }

    private fun parseFlashcards(text: String): List<Flashcard> {
        val cards = mutableListOf<Flashcard>()
        var question = ""
        for (line in text.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Q:") -> question = trimmed.removePrefix("Q:").trim()
                trimmed.startsWith("A:") && question.isNotEmpty() -> {
                    cards.add(Flashcard(question, trimmed.removePrefix("A:").trim()))
                    question = ""
                }
            }
        }
        return cards
    }

    private fun saveMessageToFirestore(message: Message) {
        val data = hashMapOf(
            "id" to message.id,
            "content" to message.content,
            "isUser" to message.isUser,
            "timestamp" to message.timestamp,
            "messageType" to message.messageType.name
        )
        db.collection("users").document("testuser123")
            .collection("chats").document("${subjectName}_${chapterName}")
            .collection("messages").document(message.id)
            .set(data)
    }

    private fun showLoading(show: Boolean) {
        loadingLayout.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // VoiceRecognitionCallback
    override fun onResults(text: String) {
        runOnUiThread {
            isListening = false
            voiceButton.text = "🎤"
            listeningIndicator.visibility = View.GONE
            messageInput.setText(text)
            messageInput.setSelection(text.length)
        }
    }

    override fun onPartialResults(text: String) {
        runOnUiThread {
            messageInput.setText(text)
            messageInput.setSelection(text.length)
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            isListening = false
            voiceButton.text = "🎤"
            listeningIndicator.visibility = View.GONE
        }
    }

    override fun onListeningStarted() {}

    override fun onListeningFinished() {
        runOnUiThread {
            isListening = false
            voiceButton.text = "🎤"
            listeningIndicator.visibility = View.GONE
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            // Re-try whichever permission was just granted
            val perm = permissions.firstOrNull()
            if (perm == android.Manifest.permission.RECORD_AUDIO) startVoiceInput()
            else if (perm == android.Manifest.permission.CAMERA) openCamera()
        }
    }

    override fun onDestroy() {
        voiceManager.destroy()
        ttsManager.destroy()
        super.onDestroy()
    }
}

