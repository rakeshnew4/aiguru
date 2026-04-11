package com.aiguruapp.student.models

import android.content.Context

/** Persists the server URL and optional model override for the AI Guru backend. */
data class ModelConfig(
    val serverUrl: String = "",
    val serverModel: String = "",
    val serverApiKey: String = ""
) {
    companion object {
        private const val PREFS = "model_config"

        fun load(context: Context): ModelConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ModelConfig(
                serverUrl    = p.getString("server_url",    "")!!,
                serverModel  = p.getString("server_model",  "")!!,
                serverApiKey = p.getString("server_api_key", "")!!
            )
        }

        fun save(context: Context, config: ModelConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("server_url",     config.serverUrl)
                putString("server_model",   config.serverModel)
                putString("server_api_key", config.serverApiKey)
                apply()
            }
        }
    }
}
