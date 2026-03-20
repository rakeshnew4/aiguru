package com.example.aiguru.chat

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Streams responses from a custom self-hosted server.
 *
 * Expected server contract:
 *   POST <serverUrl>/chat-stream
 *   Request body : {"text": "<user message>"}
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
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Combine system prompt + user text into a single text field
        val combined = if (systemPrompt.isBlank()) userText
                       else "$systemPrompt\n\n$userText"
        val json = JSONObject().apply { put("text", combined) }
        executeStream(json, onToken, onDone, onError)
    }

    override fun streamWithImage(
        systemPrompt: String,
        userText: String,
        base64Image: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Image not supported by this server format — fall back to text only
        streamText(systemPrompt, userText, onToken, onDone, onError)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun executeStream(
        json: JSONObject,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
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
            response.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    try {
                        val obj = JSONObject(data)
                        if (obj.optBoolean("done", false)) break
                        val token = obj.optString("text", "")
                        if (token.isNotEmpty()) onToken(token)
                    } catch (_: Exception) { }
                }
            }
            onDone()
        } catch (e: IOException) {
            Log.e("ServerProxyClient", "IOException: ${e.message}", e)
            onError(e.message ?: "Network error")
        }
    }
}
