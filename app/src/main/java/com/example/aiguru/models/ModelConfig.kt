package com.example.aiguru.models

import android.content.Context

/** Persists the user's chosen LLM backend and model configuration. */
data class ModelConfig(
    val provider: Provider = Provider.SERVER,
    val groqTextModel: String = "llama-3.3-70b-versatile",
    val groqVisionModel: String = "meta-llama/llama-4-scout-17b-16e-instruct",
    val serverUrl: String = "http://108.181.187.227:8003/chat-stream",
    val serverModel: String = "",
    val serverApiKey: String = ""
) {
    enum class Provider { GROQ, SERVER }

    companion object {
        private const val PREFS = "model_config"

        fun load(context: Context): ModelConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val providerName = p.getString("provider", Provider.SERVER.name) ?: Provider.SERVER.name
            return ModelConfig(
                provider        = try { Provider.valueOf(providerName) } catch (_: Exception) { Provider.SERVER },
                groqTextModel   = p.getString("groq_text_model",   "llama-3.3-70b-versatile")!!,
                groqVisionModel = p.getString("groq_vision_model", "meta-llama/llama-4-scout-17b-16e-instruct")!!,
                serverUrl       = p.getString("server_url",        "http://108.181.187.227:8003")!!,
                serverModel     = p.getString("server_model",      "")!!,
                serverApiKey    = p.getString("server_api_key",    "")!!
            )
        }

        fun save(context: Context, config: ModelConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("provider",           config.provider.name)
                putString("groq_text_model",    config.groqTextModel)
                putString("groq_vision_model",  config.groqVisionModel)
                putString("server_url",         config.serverUrl)
                putString("server_model",       config.serverModel)
                putString("server_api_key",     config.serverApiKey)
                apply()
            }
        }
    }
}
