package com.aiguruapp.student

import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aiguruapp.student.models.Flashcard
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class RevisionActivity : BaseActivity() {

    private lateinit var flashcards: ArrayList<Flashcard>
    private var currentIndex = 0
    private var isShowingAnswer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_revision)

        flashcards = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("flashcards", ArrayList::class.java)
                ?.filterIsInstance<Flashcard>()
                ?.let { ArrayList(it) } ?: ArrayList()
        } else {
            @Suppress("UNCHECKED_CAST")
            intent.getSerializableExtra("flashcards") as? ArrayList<Flashcard> ?: ArrayList()
        }

        if (flashcards.isEmpty()) {
            finish()
            return
        }

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val flashcard = findViewById<MaterialCardView>(R.id.flashcard)
        val prevButton = findViewById<MaterialButton>(R.id.prevButton)
        val nextButton = findViewById<MaterialButton>(R.id.nextButton)

        flashcard.setOnClickListener { toggleCard() }
        prevButton.setOnClickListener { showCard(currentIndex - 1) }
        nextButton.setOnClickListener { showCard(currentIndex + 1) }

        showCard(0)
    }

    private fun showCard(index: Int) {
        if (index < 0 || index >= flashcards.size) return
        currentIndex = index
        isShowingAnswer = false

        val card = flashcards[currentIndex]
        findViewById<TextView>(R.id.cardLabel).text = "QUESTION"
        findViewById<TextView>(R.id.cardText).text = card.question

        val progress = ((currentIndex + 1) * 100) / flashcards.size
        findViewById<ProgressBar>(R.id.progressBar).progress = progress
        findViewById<TextView>(R.id.cardCountText).text =
            "Card ${currentIndex + 1} of ${flashcards.size}"

        findViewById<MaterialButton>(R.id.prevButton).isEnabled = currentIndex > 0
        findViewById<MaterialButton>(R.id.nextButton).isEnabled =
            currentIndex < flashcards.size - 1
    }

    private fun toggleCard() {
        val card = flashcards[currentIndex]
        isShowingAnswer = !isShowingAnswer
        if (isShowingAnswer) {
            findViewById<TextView>(R.id.cardLabel).text = "ANSWER"
            findViewById<TextView>(R.id.cardText).text = card.answer
        } else {
            findViewById<TextView>(R.id.cardLabel).text = "QUESTION"
            findViewById<TextView>(R.id.cardText).text = card.question
        }
    }
}
