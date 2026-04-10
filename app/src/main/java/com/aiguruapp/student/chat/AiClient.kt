package com.aiguruapp.student.chat

/**
 * Common streaming interface for all LLM backends (Groq, server proxy, etc.).
 * All methods are blocking — call from Dispatchers.IO.
 *
 * [onDone] is called with (inputTokens, outputTokens, totalTokens) once the stream ends.
 * Values are 0 when the backend doesn't report usage.
 */
interface AiClient {

    /**
     * Stream a text-only chat completion.
     * [onToken] is called for each partial token on the calling thread.
     * [onDone]  is called once the stream ends cleanly, with token usage.
     * [onError] is called on network/HTTP failures.
     */
    fun streamText(
        systemPrompt: String,
        userText: String,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Stream a vision + text completion.
     * [base64Image] must be a JPEG base64 string (no data-URL prefix).
     */
    fun streamWithImage(
        systemPrompt: String,
        userText: String,
        base64Image: String,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    )
}
