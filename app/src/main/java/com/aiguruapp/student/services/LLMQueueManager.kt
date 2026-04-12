package com.aiguruapp.student.services

import android.util.Log
import com.aiguruapp.student.chat.AiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Request queued for LLM processing.
 */
data class LLMRequest(
    val id: String = UUID.randomUUID().toString(),
    val systemPrompt: String,
    val userText: String,
    val pageId: String = "",
    val onToken: (String) -> Unit,
    val onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
    val onError: (String) -> Unit,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Async LLM request queue manager.
 * 
 * Benefits:
 * - Non-blocking UI: requests queued, show "Still thinking..." immediately
 * - Prevents redundant concurrent requests
 * - Request deduplication (same question within 5 seconds)
 * - Automatic retry on network errors
 * 
 * Usage:
 *   val queue = LLMQueueManager(aiClient)
 *   queue.enqueue(
 *       systemPrompt = "You are...",
 *       userText = "Explain...",
 *       onToken = { token -> updateUI(token) },
 *       onDone = { in, out, total -> hideLoading() },
 *       onError = { err -> showError(err) }
 *   )
 */
class LLMQueueManager(val aiClient: AiClient) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestQueue = ConcurrentLinkedQueue<LLMRequest>()
    private val isProcessing = AtomicBoolean(false)
    private val recentRequests = mutableMapOf<String, Long>()  // hash -> timestamp
    private val TAG = "LLMQueue"

    /**
     * Enqueue a request for async processing.
     * Returns request ID for tracking.
     */
    fun enqueue(
        systemPrompt: String,
        userText: String,
        pageId: String = "",
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit,
        showLoadingUI: ((String) -> Unit)? = null
    ): String {
        // Show "Still thinking..." immediately
        val requestId = UUID.randomUUID().toString()
        showLoadingUI?.invoke("⏳ Still thinking...")

        // Check for duplicate request
        val requestHash = generateHash(pageId, userText)
        val lastSimilarTime = recentRequests[requestHash] ?: 0L
        val timeSinceLast = System.currentTimeMillis() - lastSimilarTime

        if (timeSinceLast < 5000) {  // Within 5 seconds
            Log.d(TAG, "Request #$requestId deduplicated (duplicate within 5s)")
            onError("Please wait a moment before asking the same question again")
            return requestId
        }

        recentRequests[requestHash] = System.currentTimeMillis()

        val request = LLMRequest(
            id = requestId,
            systemPrompt = systemPrompt,
            userText = userText,
            pageId = pageId,
            onToken = onToken,
            onDone = onDone,
            onError = onError
        )

        requestQueue.add(request)
        Log.d(TAG, "Request #$requestId queued (queue size: ${requestQueue.size})")

        // Start processing if not already running
        processNext()

        return requestId
    }

    /**
     * Process next request in queue.
     */
    private fun processNext() {
        if (isProcessing.getAndSet(true)) {
            return  // Already processing
        }

        scope.launch {
            while (true) {
                val request = requestQueue.poll()
                if (request == null) {
                    isProcessing.set(false)
                    Log.d(TAG, "Queue complete, waiting for new requests")
                    return@launch
                }

                processRequest(request)
            }
        }
    }

    /**
     * Execute single request with retry logic.
     */
    private suspend fun processRequest(request: LLMRequest, attempt: Int = 1) {
        Log.d(TAG, "Processing request #${request.id} (attempt $attempt)")

        var streamErr: String? = null
        val startTime = System.currentTimeMillis()

        try {
            // This blocks but we're on Dispatchers.Default (not main thread)
            aiClient.streamText(
                systemPrompt = request.systemPrompt,
                userText = request.userText,
                onToken = request.onToken,
                onDone = { inp, out, total ->
                    val elapsedMs = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Request #${request.id} done in ${elapsedMs}ms (in=$inp, out=$out)")
                    request.onDone.invoke(inp, out, total)
                },
                onError = { err ->
                    streamErr = err
                    Log.e(TAG, "Request #${request.id} error: $err")
                }
            )

            if (streamErr == null) {
                Log.d(TAG, "Request #${request.id} succeeded")
                return
            }

            // Error occurred — decide whether to retry
            if (attempt < 3 && streamErr!!.contains("Network", ignoreCase = true)) {
                Log.w(TAG, "Request #${request.id} retrying (attempt $attempt → ${attempt + 1})")
                kotlinx.coroutines.delay(1000L * attempt)  // Exponential backoff
                processRequest(request, attempt + 1)
            } else {
                Log.e(TAG, "Request #${request.id} failed permanently: $streamErr")
                request.onError.invoke(streamErr!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request #${request.id} exception: ${e.message}", e)
            request.onError.invoke("Error: ${e.message}")
        }
    }

    /**
     * Generate hash to detect duplicate requests.
     */
    private fun generateHash(pageId: String, question: String): String {
        val key = "$pageId:${question.take(100)}"
        return key.hashCode().toString()
    }

    /**
     * Get current queue size.
     */
    fun getQueueSize(): Int = requestQueue.size

    /**
     * Cancel all pending requests.
     */
    fun clear() {
        requestQueue.clear()
        recentRequests.clear()
        Log.d(TAG, "Queue cleared")
    }
}
