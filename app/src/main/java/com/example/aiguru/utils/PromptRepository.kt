package com.example.aiguru.utils

import android.content.Context
import com.example.aiguru.models.TutorMode
import org.json.JSONObject

/**
 * Loads [tutor_prompts.json] from assets once, then provides typed accessors.
 * Edit [app/src/main/assets/tutor_prompts.json] to change any prompt without touching Kotlin code.
 */
object PromptRepository {

    private var json: JSONObject? = null

    /** Must be called once before any other method — safe to call multiple times. */
    fun init(context: Context) {
        if (json != null) return
        val text = context.assets.open("tutor_prompts.json").bufferedReader().readText()
        json = JSONObject(text)
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    fun getSystemPromptHeader(): String =
        require().optString("system_prompt_header", "You are a helpful tutor.")

    fun getSystemPromptFooter(): String =
        require().optString("system_prompt_footer", "")

    fun getTutorRules(): List<String> {
        val arr = require().getJSONArray("tutor_rules")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun getModeGuide(mode: TutorMode): String {
        val key = mode.name  // AUTO / EXPLAIN / PRACTICE / EVALUATE
        return require().getJSONObject("mode_guides").optString(key, "MODE=$key: Respond helpfully.")
    }

    /**
     * Returns a quick-action prompt with {subject} and {chapter} replaced.
     * Keys match the JSON quick_actions object: "explain", "quiz", "notes", etc.
     */
    fun getQuickAction(key: String, subject: String, chapter: String): String {
        val template = require().getJSONObject("quick_actions").optString(key, "")
        return template.fill(subject, chapter)
    }

    fun getWelcomeMessage(subject: String, chapter: String): String =
        require().optString("welcome_message", "").fill(subject, chapter)

    /** Returns the language suffix to append to the system prompt, or empty string. */
    fun getLanguageInstruction(langCode: String): String =
        require().getJSONObject("language_instructions").optString(langCode, "")

    fun getBlackboardSystemPrompt(): String =
        require().optString("blackboard_system_prompt", "")

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun require(): JSONObject =
        json ?: error("PromptRepository not initialized — call PromptRepository.init(context) in onCreate.")

    private fun String.fill(subject: String, chapter: String): String =
        replace("{subject}", subject).replace("{chapter}", chapter)
}
