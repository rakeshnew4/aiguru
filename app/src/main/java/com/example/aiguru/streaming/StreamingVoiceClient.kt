package com.example.aiguru.streaming

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the AI Guru streaming voice server.
 *
 * Lifecycle:
 *   connect(sessionId, systemPrompt)
 *     → opens WebSocket → sends "init" → starts [AudioStreamer]
 *   disconnect()
 *     → stops [AudioStreamer] → closes WebSocket
 *
 * All callbacks are fired on OkHttp's dispatcher thread.
 * Switch to main thread in the caller when touching UI.
 */
class StreamingVoiceClient(
    private val serverUrl: String,          // e.g.  "ws://192.168.1.10:8765"
    private val onPartialText:  (String) -> Unit,   // live STT transcription
    private val onToken:        (String) -> Unit,   // LLM token (stream to UI)
    private val onFinalText:    (String) -> Unit,   // complete AI turn
    private val onAudioChunk:   (ByteArray) -> Unit,// TTS audio MP3 chunk
    private val onInterrupted:  ()       -> Unit,
    private val onConnected:    ()       -> Unit,
    private val onDisconnected: ()       -> Unit,
    private val onError:        (String) -> Unit,
) {
    companion object {
        private const val TAG = "StreamingVoiceClient"
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // keep alive
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val audioStreamer = AudioStreamer { pcm -> sendAudioChunk(pcm) }

    /** Open connection and begin audio capture. */
    fun connect(sessionId: String, systemPrompt: String, language: String = "en-US") {
        val url = "${serverUrl.trimEnd('/')}/voice"
        val request = Request.Builder().url(url).build()

        webSocket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $url")
                // Send session init before any audio
                ws.send(JSONObject().apply {
                    put("type",          "init")
                    put("session_id",    sessionId)
                    put("system_prompt", systemPrompt)
                    put("language",      language)
                }.toString())
                onConnected()
                audioStreamer.start()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.getString("type")) {
                        "partial_text" -> onPartialText(msg.getString("text"))
                        "token"        -> onToken(msg.getString("text"))
                        "final_text"   -> onFinalText(msg.getString("text"))
                        "audio_chunk"  -> {
                            val bytes = Base64.decode(msg.getString("data"), Base64.NO_WRAP)
                            onAudioChunk(bytes)
                        }
                        "interrupted"  -> onInterrupted()
                        "error"        -> onError(msg.optString("message", "Unknown server error"))
                        else           -> Log.d(TAG, "Unknown message type: ${msg.getString("type")}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Message parse error: $e — raw=$text")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                audioStreamer.stop()
                onError(t.message ?: "WebSocket connection failed")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code / $reason")
                audioStreamer.stop()
                onDisconnected()
            }
        })
    }

    /**
     * Encode a PCM chunk as base64 and send it to the server.
     * Called automatically by [AudioStreamer] for every 20 ms frame.
     */
    private fun sendAudioChunk(pcm: ByteArray) {
        val msg = JSONObject().apply {
            put("type", "audio_chunk")
            put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
        }
        webSocket?.send(msg.toString())
    }

    /**
     * Signal the server to stop the current AI turn immediately.
     * The server will reply with {"type":"interrupted"}.
     */
    fun sendInterrupt() {
        webSocket?.send(JSONObject().apply { put("type", "interrupt") }.toString())
    }

    /** Stop audio capture and close the WebSocket. */
    fun disconnect() {
        audioStreamer.stop()
        try {
            webSocket?.send(JSONObject().apply { put("type", "end") }.toString())
            webSocket?.close(1000, "Client disconnecting")
        } catch (_: Exception) { /* already closed */ }
        webSocket = null
    }

    /** True while the WebSocket is open. */
    val isConnected: Boolean
        get() = webSocket != null
}
