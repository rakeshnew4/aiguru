package com.example.aiguru

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

class ChatActivity : AppCompatActivity() {

    private lateinit var chatLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private val client = OkHttpClient()
    // TODO: Move API keys to local.properties or BuildConfig
    private val API_KEY = BuildConfig.GROQ_API_KEY // "Replace with your Groq API key"
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

        subjectName = intent.getStringExtra("subjectName") ?: ""
        chapterName = intent.getStringExtra("chapterName") ?: ""
        imagePath = intent.getStringExtra("imagePath")

        findViewById<TextView>(R.id.chatTitle).text = chapterName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        chatLayout = findViewById(R.id.chatLayout)
        scrollView = findViewById(R.id.scrollView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        // Convert image to base64 if available
        imagePath?.let { loadImageAsBase64(it) }

        // Quick action buttons
        findViewById<Button>(R.id.summarizeButton).setOnClickListener {
            sendMessage("Please summarize this chapter in simple points")
        }
        findViewById<Button>(R.id.explainButton).setOnClickListener {
            sendMessage("Please explain the main concepts in this chapter simply")
        }
        findViewById<Button>(R.id.quizButton).setOnClickListener {
            sendMessage("Create 5 quiz questions with answers from this chapter")
        }
        findViewById<Button>(R.id.notesButton).setOnClickListener {
            sendMessage("Make short revision notes for exam from this chapter")
        }

        sendButton.setOnClickListener {
            val message = messageInput.text.toString().trim()
            if (message.isNotEmpty()) {
                sendMessage(message)
                messageInput.setText("")
            }
        }

        // Welcome message
        addMessage("Hi! I am AI Guru. I am ready to help you with $chapterName. Use the quick buttons above or ask me anything!", false)
    }

    private fun loadImageAsBase64(path: String) {
        try {
            val uri = Uri.parse(path)
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            imageBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            imageBase64 = null
        }
    }

    private fun sendMessage(userMessage: String) {
        addMessage(userMessage, true)

        val context = "You are AI Guru, a helpful education tutor for students. " +
                "Subject: $subjectName, Chapter: $chapterName. " +
                "Answer in simple, clear language suitable for students. " +
                "If asked to summarize or explain, be thorough but easy to understand."

        val json = JSONObject()
        json.put("model", "llama-3.3-70b-versatile")
        val messagesArray = JSONArray()

        val systemMessage = JSONObject()
        systemMessage.put("role", "system")
        systemMessage.put("content", context)
        messagesArray.put(systemMessage)

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", userMessage)
        messagesArray.put(userMsg)

        json.put("messages", messagesArray)

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                addMessage("Error: ${e.message}", false)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                try {
                    val jsonResponse = JSONObject(responseBody ?: "")
                    val aiText = jsonResponse
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    addMessage(aiText, false)
                    saveNoteToFirestore(userMessage, aiText)
                } catch (e: Exception) {
                    addMessage("Error: $responseBody", false)
                }
            }
        })
    }

    private fun saveNoteToFirestore(question: String, answer: String) {
        val userId = auth.currentUser?.uid ?: return
        val note = hashMapOf(
            "question" to question,
            "answer" to answer,
            "subject" to subjectName,
            "chapter" to chapterName,
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("users").document(userId)
            .collection("notes")
            .add(note)
    }

    private fun addMessage(message: String, isUser: Boolean) {
        runOnUiThread {
            val textView = TextView(this)
            textView.text = message
            textView.textSize = 15f
            textView.setPadding(24, 16, 24, 16)
            textView.typeface = Typeface.DEFAULT

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 8
            params.bottomMargin = 8

            if (isUser) {
                textView.setBackgroundColor(Color.parseColor("#1A73E8"))
                textView.setTextColor(Color.WHITE)
                params.gravity = Gravity.END
                params.marginStart = 80
                params.marginEnd = 16
            } else {
                textView.setBackgroundColor(Color.WHITE)
                textView.setTextColor(Color.BLACK)
                params.gravity = Gravity.START
                params.marginStart = 16
                params.marginEnd = 80
            }

            textView.layoutParams = params
            chatLayout.addView(textView)
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}