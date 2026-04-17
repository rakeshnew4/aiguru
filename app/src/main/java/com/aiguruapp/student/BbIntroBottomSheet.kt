package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.aiguruapp.student.firestore.StudentStatsManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BbIntroBottomSheet
 * ──────────────────
 * Shown on first home-screen open (or when seen_bb_intro == false in students_stats).
 * Presents 3 ready-to-try Blackboard Mode topics so the user immediately
 * understands what BB Mode is and can experience it with one tap.
 *
 * Once dismissed (either via "Maybe Later" or by picking a topic), the sheet
 * never appears again — [StudentStatsManager.markBbIntroSeen] is called before
 * the sheet opens to guarantee this even if the user force-quits mid-show.
 */
class BbIntroBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "BbIntroBottomSheet"

        /** Sample topics wired to the three topic cards. */
        private data class Topic(
            val subject: String,
            val chapter: String,
            val message: String
        )

        private val MATH = Topic(
            subject = "Mathematics",
            chapter = "Algebra",
            message = "Explain how to solve quadratic equations ax² + bx + c = 0 step by step. " +
                      "Show 2 clear examples: one using the quadratic formula and one using factoring. " +
                      "Include a tip on when to use each method."
        )
        private val SCIENCE = Topic(
            subject = "Science",
            chapter = "Plant Biology",
            message = "Explain photosynthesis: how do plants make food from sunlight? " +
                      "Include the chemical equation (6CO₂ + 6H₂O → C₆H₁₂O₆ + 6O₂), " +
                      "the role of chlorophyll, chloroplasts, and what happens during light " +
                      "and dark reactions. Keep it clear for a Class 9 student."
        )
        private val ENGLISH = Topic(
            subject = "English",
            chapter = "Grammar",
            message = "Explain all 12 English tenses with examples. " +
                      "For each tense give: the structure (formula), 2 example sentences, " +
                      "and a memory tip. Make it easy to understand for a Class 8–10 student."
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bb_intro_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.bbIntroMathCard).setOnClickListener    { launchBb(MATH) }
        view.findViewById<View>(R.id.bbIntroScienceCard).setOnClickListener { launchBb(SCIENCE) }
        view.findViewById<View>(R.id.bbIntroEnglishCard).setOnClickListener { launchBb(ENGLISH) }
        view.findViewById<View>(R.id.bbIntroSkipBtn).setOnClickListener     { dismiss() }
    }

    private fun launchBb(topic: Topic) {
        val ctx = requireContext()
        val uid = SessionManager.getFirestoreUserId(ctx)
        val lang = SessionManager.getPreferredLang(ctx).ifBlank { "en-US" }

        startActivity(
            Intent(ctx, BlackboardActivity::class.java)
                .putExtra(BlackboardActivity.EXTRA_MESSAGE, topic.message)
                .putExtra(BlackboardActivity.EXTRA_SUBJECT, topic.subject)
                .putExtra(BlackboardActivity.EXTRA_CHAPTER, topic.chapter)
                .putExtra(BlackboardActivity.EXTRA_USER_ID, uid)
                .putExtra(BlackboardActivity.EXTRA_LANGUAGE_TAG, lang)
        )
        dismiss()
    }
}
