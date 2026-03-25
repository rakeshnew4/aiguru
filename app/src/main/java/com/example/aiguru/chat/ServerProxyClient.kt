package com.example.aiguru.chat

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Streams responses from a custom self-hosted server.
 *
 * Expected server contract:
 *   POST <serverUrl>/chat-stream
 *   Request body : {
 *     "question":      "<user message>",
 *     "page_id":       "<subject>__<chapter>",
 *     "student_level": <int 1-12>,
 *     "history":       ["user: ...", "assistant: ...", ...],
 *     "image_data":    { "transcript": "...", "paragraphs": [...],
 *                        "diagrams": [...], "key_terms": [...] }  // optional
 *   }
 *   Response SSE : data: {"text": "<token>"}  (repeated)
 *                  data: {"done": true}          (terminal frame)
 *
 * [serverUrl] should be the base URL, e.g. "http://192.168.1.10:8000".
 * [apiKey] is optional — sent as Bearer token when non-empty.
 *
 * All calls are blocking — invoke from Dispatchers.IO.
 */
class ServerProxyClient(
    private val serverUrl: String,
    private val modelName: String,   // kept for API compatibility, not sent to this server
    private val apiKey: String = ""
) : AiClient {

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private val endpoint: String get() {
        val base = serverUrl.trimEnd('/')
        return if (base.endsWith("/chat-stream")) base else "$base/chat-stream"
    }

    override fun streamText(
        systemPrompt: String,
        userText: String,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        // Fallback: no page_id / history available — wrap system prompt into question
        val combined = if (systemPrompt.isBlank()) userText else "$systemPrompt\n\n$userText"
        streamChat(
            question     = combined,
            pageId       = "",
            studentLevel = 5,
            history      = emptyList(),
            onToken      = onToken,
            onDone       = onDone,
            onError      = onError
        )
    }

    /**
     * Primary entry point — builds the full ChatRequest payload.
     *
     * @param question      The student's question (plain text, no system prompt)
     * @param pageId        "subject__chapter" identifier for the server's RAG context
     * @param studentLevel  Grade level as integer (1–12). Default 5.
     * @param history       Prior turns as ["user: ...", "assistant: ..."] strings
     * @param imageData     Optional structured data extracted from an attached image/PDF page.
     *                      Sent as `image_data` so the server can use full transcript + diagrams.
     */
    fun streamChat(
        question: String,
        pageId: String,
        studentLevel: Int = 5,
        history: List<String> = emptyList(),
        imageData: JSONObject? = null,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val historyArray = JSONArray().apply { history.forEach { put(it) } }
        val json = JSONObject().apply {
            put("question",      question)
            put("page_id",       pageId)
            put("student_level", studentLevel)
            put("history",       historyArray)
            if (imageData != null) put("image_data", imageData)
        }
        Log.d("ServerProxyClient", "streamChat → imageData=${if (imageData != null) "present (${imageData.optString("transcript", "").take(40)}…)" else "none"}")
        executeStream(json, onToken, onDone, onError)
    }

    override fun streamWithImage(
        systemPrompt: String,
        userText: String,
        base64Image: String,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        // Image not supported by this server format — fall back to text only
        streamText(systemPrompt, userText, onToken, onDone, onError)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun executeStream(
        json: JSONObject,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotEmpty()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        Log.d("ServerProxyClient", "→ POST $endpoint")
        try {
            val response = client.newCall(reqBuilder.build()).execute()
            Log.d("ServerProxyClient", "← HTTP ${response.code}")
            if (!response.isSuccessful) {
                onError("HTTP ${response.code}: ${response.message}")
                return
            }
            var inputTokens = 0; var outputTokens = 0; var totalTokens = 0
            response.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    try {
                        val obj = JSONObject(data)
                        if (obj.optBoolean("done", false)) {
                            inputTokens  = obj.optInt("inputTokens",  0)
                            outputTokens = obj.optInt("outputTokens", 0)
                            totalTokens  = obj.optInt("totalTokens",  0)
                            Log.d("TokenDebug", "[ServerProxy] done frame → in=$inputTokens out=$outputTokens total=$totalTokens raw=$data")
                            break
                        }
                        val token = obj.optString("text", "")
                        if (token.isNotEmpty()) onToken(token)
                    } catch (_: Exception) { }
                }
            }
            onDone(inputTokens, outputTokens, totalTokens)
            Log.d("TokenDebug", "[ServerProxy] onDone called with in=$inputTokens out=$outputTokens total=$totalTokens")
        } catch (e: IOException) {
            Log.e("ServerProxyClient", "IOException: ${e.message}", e)
            onError(e.message ?: "Network error")
        }
    }
}
