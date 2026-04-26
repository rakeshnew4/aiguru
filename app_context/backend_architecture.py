"""
Backend architecture summary for AI agents.

Update this file when changing:
- server/app/main.py
- server/app/core/*
- server/app/services/llm_service.py
- server/app/services/user_service.py
- global runtime assumptions (LiteLLM, Firestore, auth, CORS, TTS credentials)
"""

APP_ENTRYPOINT = {
    "file": "server/app/main.py",
    "app_factory_style": "single module-level FastAPI app",
    "routers": [
        "chat",
        "payments",
        "library",
        "quiz",
        "analyze_image",
        "tts",
        "admin",
        "users",
        "bb",
        "diagram",
    ],
    "extra_endpoints": ["/admin", "/health"],
    "static_mount": "/static -> server/app/static",
}


AUTH_AND_RUNTIME = {
    "auth_dependency": "server/app/core/auth.py::require_auth",
    "auth_source": "Firebase ID token from Authorization: Bearer <token>",
    "dev_bypass": "When AUTH_REQUIRED=False, require_auth returns a dev user instead of validating Firebase.",
    "firebase_singleton": "server/app/core/firebase_auth.py initializes Firebase Admin once and exposes get_firestore_db()/verify_firebase_token().",
    "service_account_bootstrap": (
        "main.py auto-sets GOOGLE_APPLICATION_CREDENTIALS to firebase_serviceaccount.json "
        "if it exists and the env var is not already set."
    ),
}


MODEL_AND_PROVIDER_FLOW = {
    "settings_file": "server/app/core/config.py",
    "tiers": {
        "power": "best-capability tier",
        "cheaper": "balanced tier",
        "faster": "fast structured-task tier",
    },
    "current_llm_behavior": (
        "server/app/services/llm_service.py::generate_response() currently expects "
        "USE_LITELLM_PROXY=True and sends all tiers through _call_litellm_proxy(). "
        "Request body includes thinking={type:enabled, budget_tokens:32} and "
        "extra_body={cache_control:{type:ephemeral}} for Gemini explicit caching."
    ),
    "provider_clients_present": [
        "Gemini client code exists",
        "Groq client code exists",
        "Bedrock client code exists",
        "LiteLLM proxy path is the active unified path",
    ],
    "important_behavior": (
        "generate_response() returns an error dict with provider='error' on failure "
        "instead of raising in the final public path."
    ),
}


DATA_AND_STATE = {
    "primary_database": "Firestore",
    "major_collections": [
        "users_table",
        "users",
        "plans",
        "quizzes",
        "attempts",
        "payment_intents",
        "payment_receipts",
        "payment_webhooks",
        "activity_logs",
        "subjects / chapters related collections",
    ],
    "quota_source_of_truth": (
        "user_service.check_and_record_quota() is the authoritative daily quota gate "
        "for chat and blackboard."
    ),
    "context_lookup": (
        "context_service.get_context(page_id) is currently a stub that returns "
        "'Context for page {page_id}'."
    ),
}


KEY_FILES = {
    "server/app/main.py": "App startup, router mounting, CORS, static admin portal, health endpoint.",
    "server/app/core/config.py": "Environment-driven settings and tier-to-model mapping.",
    "server/app/core/auth.py": "FastAPI auth dependency with optional development bypass.",
    "server/app/core/firebase_auth.py": "Firebase Admin singleton and Firestore/auth helpers.",
    "server/app/services/llm_service.py": "All LLM calls funnel here before provider routing.",
    "server/app/services/user_service.py": "Plan activation, quotas, usage counters, activity logging.",
}


ARCHITECTURE_PSEUDOCODE = """
def startup():
    maybe_set_google_application_credentials_from_project_root()
    app = FastAPI(...)
    app.add_middleware(CORSMiddleware, ...)
    app.include_router(chat_router)
    app.include_router(payments_router)
    app.include_router(library_router)
    app.include_router(quiz_router)
    app.include_router(analyze_image_router)
    app.include_router(tts_router)
    app.include_router(admin_router)
    app.include_router(users_router)
    app.include_router(bb_router)
    app.include_router(diagram_router)
    app.mount("/static", ...)


def require_auth(request):
    if AUTH_REQUIRED is False:
        return dev_user_from_unverified_payload_if_possible()
    token = read_bearer_token()
    decoded = verify_firebase_token(token)
    return AuthUser(uid=decoded["uid"], email=decoded.get("email", ""))


def generate_response(prompt, images=None, tier="cheaper", system_prompt=None):
    assert USE_LITELLM_PROXY is True
    model_config = settings.get_model_config(tier)
    return _call_litellm_proxy(prompt, model_config, images, system_prompt)
""".strip()


CHANGE_RISKS = [
    "Changing request auth behavior affects every protected router because most endpoints depend on require_auth().",
    "Changing generate_response() behavior impacts chat, Blackboard planning, quiz generation/evaluation, BB grading, image analysis, and SVG LLM generation.",
    "Changing user_service quota or plan fields affects both server-side limits and Android-side metadata assumptions.",
    "Replacing context_service with real retrieval is high-impact because chat prompts currently assume it always returns a simple string.",
]

