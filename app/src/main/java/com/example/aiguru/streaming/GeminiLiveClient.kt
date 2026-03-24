package com.example.aiguru.streaming

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the **Gemini Multimodal Live API** (BidiGenerateContent).
 *
 * Protocol overview:
 *   1. [connect] opens WSS + sends a `setup` message with model, system prompt, voice config.
 *   2. Server replies `{"setupComplete": {}}` → [onSetupComplete] fires → mic starts.
 *   3. Mic loop: [AudioStreamer] delivers 16 kHz PCM chunks → we base64-encode and send
 *      as `realtime_input` messages.
 *   4. Server replies with audio chunks (24 kHz PCM) and/or text transcripts.
 *      Audio → [PcmAudioPlayer], text → [onTranscript].
 *   5. `{"serverContent": {"turnComplete": true}}` → [onTurnComplete].
 *   6. [sendText] sends a `client_content` message for text-mode turns.
 *   7. [disconnect] stops the mic, sends `end` message, closes the WebSocket.
 *
 * All callbacks fire on OkHttp's dispatcher thread — **switch to main thread for UI**.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val systemPrompt: String,
    private val voiceName: String = "Charon",
    private val onSetupComplete: () -> Unit,
    private val onTranscript: (text: String, isUser: Boolean) -> Unit,
    private val onTurnComplete: () -> Unit,
    private val onError: (String) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        // Native-audio model — higher quality, built-in voice activity detection
        private const val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        // Gemini Live WebSocket endpoint
        private fun wsUrl(key: String) =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        // No pingInterval — Gemini Live does not respond to WebSocket PING frames
        .build()

    private var webSocket: WebSocket? = null
    val audioPlayer = PcmAudioPlayer()
    private val audioStreamer = AudioStreamer { pcm -> sendAudioChunk(pcm) }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Open the WebSocket and begin the Live session. */
    fun connect() {
        audioPlayer.start()
        val request = Request.Builder().url(wsUrl(apiKey)).build()
        webSocket = http.newWebSocket(request, Listener())
        Log.i(TAG, "Connecting to Gemini Live…")
    }

    /**
     * Send a text message to Gemini (text-mode turn).
     * Can be called while connected even if [audioStreamer] is running.
     */
    fun sendText(text: String) {
        val msg = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turn_complete", true)
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", text) })
                        })
                    })
                })
            })
        }
        webSocket?.send(msg.toString())
        Log.d(TAG, "Sent text turn: ${text.take(80)}…")
    }

    /** Stop mic + close WebSocket cleanly. */
    fun disconnect() {
        audioStreamer.stop()
        audioPlayer.stop()
        try {
            webSocket?.send(JSONObject().apply { put("type", "end") }.toString())
            webSocket?.close(1000, "Client disconnecting")
        } catch (_: Exception) { /* already closed */ }
        webSocket = null
        Log.i(TAG, "Disconnected")
    }

    val isConnected: Boolean get() = webSocket != null

    // ── WebSocket Listener ────────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket open — sending setup")
            ws.send(buildSetupMessage())
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val root = JSONObject(text)

                // ── Setup complete ──────────────────────────────────────────
                if (root.has("setupComplete")) {
                    Log.i(TAG, "Setup complete — starting mic")
                    onSetupComplete()
                    audioStreamer.start()
                    return
                }

                // ── Server content ──────────────────────────────────────────
                val sc = root.optJSONObject("serverContent") ?: return
                val turnComplete = sc.optBoolean("turnComplete", false)

                sc.optJSONObject("modelTurn")?.let { modelTurn ->
                    val parts = modelTurn.optJSONArray("parts") ?: return@let
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        // Audio chunk (inline PCM)
                        part.optJSONObject("inlineData")?.let { inline ->
                            val mime = inline.optString("mimeType", "")
                            if (mime.startsWith("audio/pcm")) {
                                val pcm = Base64.decode(inline.getString("data"), Base64.NO_WRAP)
                                audioPlayer.enqueue(pcm)
                            }
                        }

                        // Text transcript
                        val partText = part.optString("text", "")
                        if (partText.isNotBlank()) {
                            onTranscript(partText, false)
                        }
                    }
                }

                // Input transcription (what the user actually said)
                sc.optJSONObject("inputTranscription")?.let { tr ->
                    val userText = tr.optString("text", "")
                    if (userText.isNotBlank()) onTranscript(userText, true)
                }

                // Output transcription (what the AI said, as text)
                sc.optJSONObject("outputTranscription")?.let { tr ->
                    val aiText = tr.optString("text", "")
                    if (aiText.isNotBlank()) onTranscript(aiText, false)
                }

                if (turnComplete) {
                    onTurnComplete()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Message parse error: $e — raw=${text.take(200)}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            audioStreamer.stop()
            audioPlayer.stop()
            webSocket = null
            onError(t.message ?: "WebSocket connection failed")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code / $reason")
            audioStreamer.stop()
            audioPlayer.stop()
            webSocket = null
            onDisconnected()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSetupMessage(): String {
        return JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().apply { put("AUDIO") })
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voiceName)
                            })
                        })
                    })
                })
                // Enable input transcription so we capture what the user said
                put("input_audio_transcription", JSONObject())
                // Enable output transcription so we capture what the AI said as text too
                put("output_audio_transcription", JSONObject())
            })
        }.toString()
    }

    /** Encode a PCM chunk as base64 and stream it to Gemini. */
    private fun sendAudioChunk(pcm: ByteArray) {
        val msg = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mime_type", "audio/pcm;rate=16000")
                        put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    })
                })
            })
        }
        webSocket?.send(msg.toString())
    }
}
