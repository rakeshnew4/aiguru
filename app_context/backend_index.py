"""
Entry index for backend context.

Update this file when:
- a new backend area deserves its own context file
- file names or routing guidance for agents changes
"""

CONTEXT_FILE_PURPOSES = {
    "app_context/backend_architecture.py": (
        "FastAPI app composition, auth/config, Firestore usage, model routing, "
        "and the main server dependencies."
    ),
    "app_context/backend_chat_services.py": (
        "Chat, Blackboard, quiz, users, payments, TTS, library endpoints, plus "
        "Android caller mapping and request/response flow."
    ),
    "app_context/backend_svg_pipeline.py": (
        "Standalone diagram generation, Blackboard diagram rendering, JS engine, "
        "Python SVG renderers, raw SVG builder, and fallback order."
    ),
}


ROUTING_GUIDE = {
    "Need startup/auth/config/firestore/model info": "app_context/backend_architecture.py",
    "Need chat-stream or endpoint behavior": "app_context/backend_chat_services.py",
    "Need SVG, diagrams, or Blackboard visuals": "app_context/backend_svg_pipeline.py",
}


ACTIVE_SOURCE_ROOTS = {
    "backend": "server/app",
    "android_callers": "app/src/main/java/com/aiguruapp/student",
    "js_diagram_engine": "server/app/static/engine",
}


IMPORTANT_REALITIES = [
    "The active backend is the FastAPI app under server/app, not the Android module.",
    "generate_response() currently routes through the LiteLLM proxy path when USE_LITELLM_PROXY is enabled.",
    "context_service.get_context() is still a stub and does not perform real retrieval.",
    "Blackboard visuals are finalized inside image_search_titles.py, not inside chat.py alone.",
    "Standalone /diagram/generate is narrower than Blackboard visuals because diagram_service.py validates types against svg_builder._RENDERERS.",
    "After Blackboard post-processing, image_description usually contains a direct Wikimedia image URL, not the original natural-language description.",
]


AGENT_READ_ORDER = """
1. Start here to choose the right context file.
2. Read backend_architecture.py if the task changes auth, config, model routing,
   Firestore write behavior, startup wiring, or environment assumptions.
3. Read backend_chat_services.py if the task changes request contracts, SSE
   frames, Blackboard chat flow, quizzes, payments, TTS, registration, or
   Android backend calls.
4. Read backend_svg_pipeline.py if the task changes diagram_type handling,
   Blackboard visuals, JS engine scenes, SVG builders, or diagram prompts.
5. After code changes, update the matching context file so the summaries stay
   aligned with the source of truth.
""".strip()
