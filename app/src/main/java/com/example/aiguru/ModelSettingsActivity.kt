package com.example.aiguru

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiguru.models.ModelConfig
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class ModelSettingsActivity : BaseActivity() {

    companion object {
        val GROQ_TEXT_MODELS = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-70b-versatile",
            "llama-3.1-8b-instant",
            "gemma2-9b-it",
            "mixtral-8x7b-32768"
        )
        val GROQ_VISION_MODELS = listOf(
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "llava-v1.5-7b-4096-preview"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)

        val toolbar             = findViewById<MaterialToolbar>(R.id.toolbar)
        val radioGroup          = findViewById<RadioGroup>(R.id.providerRadioGroup)
        val groqSection         = findViewById<LinearLayout>(R.id.groqSection)
        val serverSection       = findViewById<LinearLayout>(R.id.serverSection)
        val textModelSpinner    = findViewById<Spinner>(R.id.textModelSpinner)
        val visionModelSpinner  = findViewById<Spinner>(R.id.visionModelSpinner)
        val serverUrlInput      = findViewById<EditText>(R.id.serverUrlInput)
        val serverModelInput    = findViewById<EditText>(R.id.serverModelInput)
        val serverApiKeyInput   = findViewById<EditText>(R.id.serverApiKeyInput)
        val saveButton          = findViewById<MaterialButton>(R.id.saveButton)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Populate spinners
        textModelSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, GROQ_TEXT_MODELS
        )
        visionModelSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, GROQ_VISION_MODELS
        )

        // Restore saved config
        val config = ModelConfig.load(this)
        val isGroqInitially = config.provider == ModelConfig.Provider.GROQ

        radioGroup.check(if (isGroqInitially) R.id.radioGroq else R.id.radioServer)
        groqSection.visibility   = if (isGroqInitially) View.VISIBLE else View.GONE
        serverSection.visibility = if (isGroqInitially) View.GONE   else View.VISIBLE

        textModelSpinner.setSelection(
            GROQ_TEXT_MODELS.indexOf(config.groqTextModel).coerceAtLeast(0)
        )
        visionModelSpinner.setSelection(
            GROQ_VISION_MODELS.indexOf(config.groqVisionModel).coerceAtLeast(0)
        )
        serverUrlInput.setText(config.serverUrl)
        serverModelInput.setText(config.serverModel)
        serverApiKeyInput.setText(config.serverApiKey)

        // Toggle sections on radio change
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isGroq = checkedId == R.id.radioGroq
            groqSection.visibility   = if (isGroq) View.VISIBLE else View.GONE
            serverSection.visibility = if (isGroq) View.GONE   else View.VISIBLE
        }

        saveButton.setOnClickListener {
            val isGroq    = radioGroup.checkedRadioButtonId == R.id.radioGroq
            val serverUrl = serverUrlInput.text.toString().trim()

            if (!isGroq && serverUrl.isEmpty()) {
                Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            ModelConfig.save(this, ModelConfig(
                provider        = if (isGroq) ModelConfig.Provider.GROQ else ModelConfig.Provider.SERVER,
                groqTextModel   = GROQ_TEXT_MODELS[textModelSpinner.selectedItemPosition],
                groqVisionModel = GROQ_VISION_MODELS[visionModelSpinner.selectedItemPosition],
                serverUrl       = serverUrl,
                serverModel     = serverModelInput.text.toString().trim(),
                serverApiKey    = serverApiKeyInput.text.toString().trim()
            ))

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
