content = """package com.example.aiguru.models

import android.content.Context

/** Persists the user's server configuration. All LLM calls go through the server proxy. */
data class ModelConfig(
    val serverUrl: String = "http://108.181.187.227:8003",
    val serverModel: String = "",
    val serverApiKey: String = ""
) {
    companion object {
        private const val PREFS = "model_config"

        fun load(context: Context): ModelConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ModelConfig(
                serverUrl    = p.getString("server_url",     "http://108.181.187.227:8003")!!,
                serverModel  = p.getString("server_model",   "")!!,
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
"""

target = r"C:\Users\rakes\OneDrive\Desktop\aiguru\app\src\main\java\com\example\aiguru\models\ModelConfig.kt"
with open(target, "w", encoding="utf-8") as f:
    f.write(content)
print("ModelConfig.kt rewritten")
