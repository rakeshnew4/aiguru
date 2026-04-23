"""
Android learning flow summary for AI agents.

Update this file when changing:
- HomeActivity
- SubjectActivity / ChapterActivity
- FullChatFragment
- BlackboardActivity
- Quiz activities
- launch paths between those screens
"""

PRIMARY_USER_JOURNEYS = {
    "dashboard_to_chapter_chat": [
        "HomeActivity shows subjects and quick actions",
        "SubjectActivity lists chapters under one subject",
        "ChapterActivity opens a chapter workspace",
        "Chat tab creates or reuses FullChatFragment(subjectName, chapterName)",
    ],
    "general_chat": [
        "HomeActivity -> ChatHostActivity(subject='General', chapter='General Chat')",
        "ChatHostActivity hosts FullChatFragment",
    ],
    "blackboard_lesson": [
        "Home quick action or chat nudge launches BlackboardActivity",
        "BlackboardActivity can generate a new lesson, replay a saved session, or load from cache/task",
    ],
    "quiz_flow": [
        "ChapterActivity -> QuizSetupActivity",
        "QuizSetupActivity -> QuizActivity",
        "QuizActivity -> QuizResultActivity",
    ],
}


FULL_CHAT_FRAGMENT_FLOW = {
    "role": "Primary chat UX engine used in both ChatHostActivity and ChapterActivity.",
    "major_capabilities": [
        "Streams backend answers via ServerProxyClient",
        "Maintains recent history for prompt context",
        "Attaches image or cropped PDF page context",
        "Handles voice recognition and TTS response playback",
        "Saves notes and page context",
        "Can nudge or launch Blackboard lessons",
    ],
}


FULL_CHAT_PSEUDOCODE = """
def FullChatFragment_sendMessage(userText):
    load effective limits from cachedMetadata + AdminConfigRepository
    run guest or normal quota check
    build user Message and show optimistic UI
    save user message via ChatHistoryRepository
    build pageId = safe(subject) + "__" + safe(chapter)
    collect recent message history
    create ServerProxyClient with effective server URL
    streamChat(
        question=userText,
        pageId=pageId,
        mode="normal",
        languageTag=currentLang,
        history=recentHistory,
        imageData/ imageBase64 if present,
    )
    while streaming:
        append tokens into the streaming assistant message
        update loading status if SSE status frame arrives
    onDone:
        record tokens with PlanEnforcer.recordTokensUsed()
        update cachedMetadata counters
        persist final assistant message with transcription/extraSummary
        optionally persist page transcript returned by server
        optionally speak response if voice flow is active
""".strip()


BLACKBOARD_FLOW = {
    "role": "Dedicated visual/audio lesson player, not just chat with a different prompt.",
    "major_capabilities": [
        "Loads from cache, saved session, global task cache, or fresh generation",
        "Uses BlackboardGenerator intent + chunk generation for progressive lesson loading",
        "Renders mixed frame types including concept, memory, summary, diagram, and interactive quiz frames",
        "Supports ask-bar followup chat inside BlackboardActivity",
        "Supports normal TTS and AI TTS, plus session save/publish",
    ],
}


BLACKBOARD_PSEUDOCODE = """
def BlackboardActivity_onCreate():
    read extras (message, subject, chapter, cache/session/task IDs, duration, imageBase64, replay flags)
    initialize TTS, AI TTS engine, ask bar, quota chip, controls
    if replay/global cache/saved session is present:
        load steps from Firestore/local cache and render immediately
    else:
        call BlackboardGenerator.callIntent(topic, totalSteps, ...)
        then call BlackboardGenerator.generateChunk(...) for the first chunk
        progressively request more chunks as the student approaches the end


def BlackboardGenerator_pipeline(topic):
    call ServerProxyClient.streamChat(... mode='blackboard_intent' ...) to get structured outline
    call ServerProxyClient.streamChat(... mode='blackboard' ...) to get chunk JSON
    parse steps/frames
    BlackboardActivity renders each frame and speaks it
""".strip()


QUIZ_FLOW = {
    "QuizSetupActivity": "Collects chapter quiz options and launches QuizActivity.",
    "QuizActivity": (
        "Renders questions locally, grades MCQ/fill blank instantly, calls backend only for short-answer "
        "evaluation and final attempt submission."
    ),
    "QuizResultActivity": (
        "Shows score breakdown, records stats/task completion, and can launch BlackboardActivity "
        "for wrong-answer remediation."
    ),
}


QUIZ_PSEUDOCODE = """
def quiz_user_flow():
    QuizSetupActivity prepares quiz request
    QuizApiClient.generateQuiz() fetches quiz JSON from backend
    QuizActivity shows questions one by one
    MCQ/fill blank are graded immediately on device
    short answer uses QuizApiClient.evaluateShortAnswer()
    finishQuiz():
        compute final local result
        fire-and-forget submitQuiz() to backend
        open QuizResultActivity with quizJson + answersJson + score extras
""".strip()


CHAPTER_WORKSPACE_FLOW = {
    "ChapterActivity": [
        "Loads subjectName/chapterName from intent",
        "Sets up tabs for Pages, Chat, and saved content",
        "Hosts FullChatFragment in the Chat tab",
        "Handles PDF page viewing/asking, page storage, and quiz launch",
    ],
    "Page flow": [
        "PageViewerActivity can return a rendered/cropped PDF page back to FullChatFragment",
        "FullChatFragment then turns that page into image/PDF context for chat",
    ],
}


TEACHER_AND_REPLAY_NOTES = [
    "Teacher screens are separate activities and are gated through AccessGate + SessionManager role flags.",
    "BlackboardActivity teacher mode can publish generated lessons so students can consume shared cached sessions.",
    "Saved Blackboard sessions and teacher-assigned cached lessons bypass fresh LLM generation when replayed.",
]

