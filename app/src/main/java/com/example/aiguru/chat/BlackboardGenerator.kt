package com.example.aiguru.chat

import com.example.aiguru.config.AdminConfigRepository
import org.json.JSONObject

/**
 * Generates a short, audio-first step-by-step lesson from a chat message.
 * Call from Dispatchers.IO — uses a blocking HTTP request.
 */
object BlackboardGenerator {

    data class BlackboardStep(
        /** Keywords / title for the screen (3–6 words, can use \\n and →). */
        val text: String,
        /** Speech for TTS (1–2 short sentences). */
        val speech: String
    )

    private const val SYSTEM_PROMPT = """YYou are an expert school teacher.

Convert the given explanation into a short, clear step-by-step visual lesson for a blackboard-style teaching mode.

Rules:
- Number of steps should depend on the explanation (usually 3–6 steps)
- Keep steps minimal but complete (no unnecessary steps)

For each step:
- "text":
  - Keywords only (NOT full sentences)
  - 6–10 words per line
  - Max 3 lines
  - You may use:
    - → arrows
    - \n line breaks
    - simple markdown for emphasis (**bold**, _italic_) where helpful
    - math expressions if needed (e.g., 2 + 3 = 5, a² + b²)
  - Should look clean and readable on a blackboard

- "speech":
  - 2-3 short sentences
  - Simple, spoken language (like explaining to a student)
  - Should clearly explain the step
  - Our speech engine is android TTS , so keep the speech more in plain texts

Special handling:
- For math problems:
  - Show step-by-step solving
  - Each step should represent one logical step (e.g., equation → simplification → result)
- For science/diagrams:
  - Focus on concept, process, or flow

General:
- Keep language very simple
- Avoid long explanations
- Do not repeat the same idea across steps
- Make it feel like a teacher explaining step-by-step

STRICT OUTPUT RULE:
- Return ONLY valid JSON
- No markdown blocks, no explanation text outside JSON

Output format:
{"steps":[{"text":" (a² - b²) = (a+b)(a-b)","speech":"This expression is called the difference of two squares.
It means we are subtracting b squared from a squared.
There is a useful formula for this.
a squared minus b squared can be written as a plus b, multiplied by a minus b.
So instead of calculating squares and subtracting, we can directly factor it.
In short, a squared minus b squared equals a plus b into a minus b."}]}"""

    /**
     * Generate teaching steps from [messageContent].
     * Must be called from a background thread (blocks on network).
     */
    fun generate(
        messageContent: String,
        onSuccess: (List<BlackboardStep>) -> Unit,
        onError: (String) -> Unit
    ) {
        val cfg = AdminConfigRepository.config
        val serverUrl = cfg.serverUrl.ifBlank { "http://108.181.187.227:8003" }

        val server = ServerProxyClient(
            serverUrl = serverUrl,
            modelName = "",
            apiKey    = cfg.serverApiKey
        )

        val question = "Break this explanation into teaching steps using SYSTEM_PROMPT rules:\n\n${messageContent.take(3000)}"
        val buffer   = StringBuilder()
        var streamErr: String? = null
        val latch    = java.util.concurrent.CountDownLatch(1)

        server.streamChat(
            question     = SYSTEM_PROMPT + "\n\nExplanation to convert:\n" + messageContent.take(3000),
            pageId       = "blackboard__lesson",
            studentLevel = 5,
            history      = emptyList(),
            onToken      = { token -> buffer.append(token) },
            onDone       = { _, _, _ -> latch.countDown() },
            onError      = { err -> streamErr = err; latch.countDown() }
        )

        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)

        if (streamErr != null) { onError(streamErr!!); return }

        val response = buffer.toString()
        try {
            val start = response.indexOf('{')
            val end   = response.lastIndexOf('}')
            if (start < 0 || end <= start) { onError("Invalid response format"); return }
            val arr = JSONObject(response.substring(start, end + 1)).getJSONArray("steps")
            val result = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BlackboardStep(
                    text   = obj.getString("text"),
                    speech = obj.getString("speech")
                )
            }
            if (result.isEmpty()) onError("No steps were generated")
            else onSuccess(result)
        } catch (e: Exception) {
            onError("Parse error: ${e.message}")
        }
    }
}
