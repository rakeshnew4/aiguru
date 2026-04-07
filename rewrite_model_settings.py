content = """package com.example.aiguru

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import com.example.aiguru.models.ModelConfig
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class ModelSettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)

        val toolbar           = findViewById<MaterialToolbar>(R.id.toolbar)
        val serverUrlInput    = findViewById<EditText>(R.id.serverUrlInput)
        val serverModelInput  = findViewById<EditText>(R.id.serverModelInput)
        val serverApiKeyInput = findViewById<EditText>(R.id.serverApiKeyInput)
        val saveButton        = findViewById<MaterialButton>(R.id.saveButton)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val config = ModelConfig.load(this)
        serverUrlInput.setText(config.serverUrl)
        serverModelInput.setText(config.serverModel)
        serverApiKeyInput.setText(config.serverApiKey)

        saveButton.setOnClickListener {
            val serverUrl = serverUrlInput.text.toString().trim()
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ModelConfig.save(this, ModelConfig(
                serverUrl    = serverUrl,
                serverModel  = serverModelInput.text.toString().trim(),
                serverApiKey = serverApiKeyInput.text.toString().trim()
            ))
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
"""

target = r"C:\Users\rakes\OneDrive\Desktop\aiguru\app\src\main\java\com\example\aiguru\ModelSettingsActivity.kt"
with open(target, "w", encoding="utf-8") as f:
    f.write(content)
print("ModelSettingsActivity.kt rewritten")
