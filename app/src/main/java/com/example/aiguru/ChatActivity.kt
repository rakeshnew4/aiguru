package com.example.aiguru
import com.example.aiguru.BuildConfig
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.example.aiguru.adapters.MessageAdapter
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

class ChatActivity : AppCompatActivity(), VoiceRecognitionCallback, TTSCallback {

    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var toolbar: MaterialToolbar
    private lateinit var loadingLayout: LinearLayout
    private lateinit var voiceButton: MaterialButton
    private lateinit var imageButton: MaterialButton
    private lateinit var pdfButton: MaterialButton

    private val client = OkHttpClient()
    private val API_KEY = BuildConfig.GROQ_API_KEY
    private val API_URL = "https://api.groq.com/openai/v1/chat/completions"

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private lateinit var subjectName: String
    private lateinit var chapterName: String

    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var mediaManager: MediaManager

    private var imageBase64: String? = null
    private var isListening = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PICK_IMAGE_REQUEST = 101
        private const val PICK_PDF_REQUEST = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        subjectName = intent.getStringExtra("subjectName") ?: "Unknown"
        chapterName = intent.getStringExtra("chapterName") ?: "Unknown Chapter"

        voiceManager = VoiceManager(this)
        ttsManager = TextToSpeechManager(this)
        mediaManager = MediaManager(this)

        initializeUI()
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
            onVoiceClick = { playVoiceMessage(it) },
            onImageClick = { viewImage(it) }
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

        setupButtons()

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
                sendButton.isEnabled = !s.isNullOrBlank()
            }
        })
    }

    private fun setupButtons() {
        voiceButton.setOnClickListener {
            if (isListening) {
                voiceManager.stopListening()
                isListening = false
                voiceButton.text = "🎤"
            } else {
                checkPermissionAndStartListening()
            }
        }

        imageButton.setOnClickListener { openImagePicker() }
        pdfButton.setOnClickListener { openPdfPicker() }
    }

    private fun checkPermissionAndStartListening() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
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
        voiceManager.startListening(this)
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    private fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/pdf" }
        startActivityForResult(intent, PICK_PDF_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            when (requestCode) {
                PICK_IMAGE_REQUEST -> Toast.makeText(this, "Image selected", Toast.LENGTH_SHORT).show()
                PICK_PDF_REQUEST -> Toast.makeText(this, "PDF selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadChatHistory() {
        val userId = auth.currentUser?.uid ?: return

        db.collection("users").document(userId)
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
                messageAdapter.addMessages(messages)
            }
    }

    private fun addWelcomeMessage() {
        messageAdapter.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                content = "👋 Hi! Ask anything about $chapterName",
                isUser = false,
                messageType = Message.MessageType.TEXT
            )
        )
    }

    private fun sendMessage(userText: String) {
        val userMessage = Message(UUID.randomUUID().toString(), userText, true)
        messageAdapter.addMessage(userMessage)

        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "user").put("content", userText))
                    })
                }

                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread { showLoading(false) }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val text = JSONObject(response.body?.string() ?: "")
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")

                        runOnUiThread {
                            showLoading(false)
                            messageAdapter.addMessage(
                                Message(UUID.randomUUID().toString(), text, false)
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                runOnUiThread { showLoading(false) }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loadingLayout.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun playVoiceMessage(message: Message) {
        ttsManager.speak(message.content, this)
    }

    private fun viewImage(message: Message) {
        Toast.makeText(this, "Image preview coming soon", Toast.LENGTH_SHORT).show()
    }

    override fun onResults(text: String) {
        runOnUiThread {
            isListening = false
            voiceButton.text = "🎤"
            messageInput.setText(text)
        }
    }

    override fun onError(error: String) {}
    override fun onPartialResults(text: String) {}
    override fun onListeningStarted() {}
    override fun onListeningFinished() {}

    override fun onStart() {
        super.onStart()
    }
    override fun onComplete() {}

    override fun onDestroy() {
        voiceManager.destroy()
        ttsManager.destroy()
        super.onDestroy()
    }
}