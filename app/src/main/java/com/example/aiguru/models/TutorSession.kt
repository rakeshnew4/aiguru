package com.example.aiguru.models

/**
 * Holds live state for one chat session (student ↔ AI tutor, per chapter).
 * Updated by TutorController after every exchange.
 */
data class TutorSession(
    val studentId: String,
    val subject: String,
    val chapter: String,
    var currentPage: Int = 1,
    var mode: TutorMode = TutorMode.AUTO,
    var lastIntent: TutorIntent = TutorIntent.GENERAL,
    var lastQuestion: String = "",
    var interactionCount: Int = 0,
    var confusionCount: Int = 0,
    val conceptsAsked: MutableList<String> = mutableListOf(),
    var mistakesDetected: Int = 0,
    var chapterSummary: String = "",
    var latestPageContext: String = ""
)

/** How the tutor should behave this session.  AUTO = LLM decides. */
enum class TutorMode {
    AUTO,     // Adaptive — tutor detects what student needs
    EXPLAIN,  // Focus on clear explanations + analogies
    PRACTICE, // Guided practice problems — no direct answers
    EVALUATE  // Quiz / test understanding mode
}

/** Intent the LLM detected in the student's last message. */
enum class TutorIntent {
    EXPLAIN,   // Student wants something explained
    SIMPLIFY,  // Student is confused, needs simpler language
    EVALUATE,  // Student gave an answer and wants feedback
    HOMEWORK,  // Student is asking for homework help
    CONFUSED,  // Student explicitly says they are confused
    GENERAL    // General conversation / other
}
