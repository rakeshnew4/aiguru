"""
Android state, config, storage, and backend client summary for AI agents.

Update this file when changing:
- SessionManager / TokenManager / AdminConfigRepository / AppStartRepository
- PlanEnforcer / AccessGate
- FirestoreManager / ChatHistoryRepository / NotesRepository / ResponseCacheService / StorageService
- ServerProxyClient / QuizApiClient / PaymentApiClient / HttpClientManager
"""

STATE_LAYERS = {
    "session_and_identity": {
        "files": [
            "app/src/main/java/com/aiguruapp/student/utils/SessionManager.kt",
            "app/src/main/java/com/aiguruapp/student/auth/TokenManager.kt",
        ],
        "purpose": (
            "SharedPreferences-backed login/session data plus Firebase ID token fetching "
            "for backend Authorization headers."
        ),
    },
    "runtime_config": {
        "files": [
            "app/src/main/java/com/aiguruapp/student/config/AdminConfigRepository.kt",
            "app/src/main/java/com/aiguruapp/student/config/AppStartRepository.kt",
            "app/src/main/java/com/aiguruapp/student/config/AppUpdateConfigCache.kt",
        ],
        "purpose": "Fetch Firestore-driven app config, plans, offers, notifications, schools, update config.",
    },
    "feature_and_quota_gating": {
        "files": [
            "app/src/main/java/com/aiguruapp/student/config/PlanEnforcer.kt",
            "app/src/main/java/com/aiguruapp/student/config/AccessGate.kt",
        ],
        "purpose": "Android-side feature checks, quota display helpers, token counters, and role gating.",
    },
    "cloud_and_local_data": {
        "files": [
            "app/src/main/java/com/aiguruapp/student/firestore/FirestoreManager.kt",
            "app/src/main/java/com/aiguruapp/student/chat/ChatHistoryRepository.kt",
            "app/src/main/java/com/aiguruapp/student/chat/NotesRepository.kt",
            "app/src/main/java/com/aiguruapp/student/services/ResponseCacheService.kt",
            "app/src/main/java/com/aiguruapp/student/services/StorageService.kt",
        ],
        "purpose": "Conversation, notes, metadata, local cache, and persistent file storage.",
    },
    "network_clients": {
        "files": [
            "app/src/main/java/com/aiguruapp/student/http/HttpClientManager.kt",
            "app/src/main/java/com/aiguruapp/student/chat/ServerProxyClient.kt",
            "app/src/main/java/com/aiguruapp/student/quiz/QuizApiClient.kt",
            "app/src/main/java/com/aiguruapp/student/payments/PaymentApiClient.kt",
        ],
        "purpose": "Central OkHttp clients and endpoint-specific request wrappers.",
    },
}


SESSION_AND_AUTH_PSEUDOCODE = """
def SessionManager_getFirestoreUserId(context):
    if FirebaseAuth.currentUser exists:
        return live firebase uid
    elif shared prefs firebase uid exists:
        return stored uid
    elif schoolId + studentId exist:
        return schoolId_studentId fallback
    else:
        return "guest_user"


def TokenManager_buildAuthHeader(forceRefresh=False):
    token = getToken(forceRefresh)
    return "Bearer " + token if token exists else null
""".strip()


CONFIG_AND_BOOTSTRAP_NOTES = [
    "AdminConfigRepository is the main source of server URL, plan limits, Razorpay key, guest UID, and feature defaults.",
    "AppStartRepository preloads plans/offers/notifications/update config/schools in parallel from SplashActivity.",
    "AppUpdateConfigCache stores app update config locally for offline fallback.",
    "Many screens assume AdminConfigRepository.fetchIfStale() has already been called at app start.",
]


QUOTA_AND_ACCESS_NOTES = [
    "PlanEnforcer still exists on Android for local checks/UI sync, but backend quota enforcement is authoritative for chat and Blackboard.",
    "AccessGate is mostly about screen/feature visibility based on role + cached plan limits.",
    "HomeActivity uses Firestore users_table counters plus plan docs to render remaining quota UI.",
]


NETWORK_CLIENT_SUMMARY = {
    "HttpClientManager": {
        "standardClient": "General API calls",
        "longTimeoutClient": "LLM/SSE streaming",
        "ncertClient": "Browser-like NCERT/PDF downloads with custom TLS fallback",
    },
    "ServerProxyClient": {
        "endpoints": ["/chat-stream", "/users/register"],
        "notes": [
            "Builds JSON payload for chat or Blackboard requests",
            "Adds Firebase Bearer token via TokenManager",
            "Retries once on HTTP 401 with forceRefresh token",
            "Parses SSE status/text/done frames",
        ],
    },
    "QuizApiClient": {
        "endpoints": ["/quiz/generate", "/quiz/evaluate-answer", "/quiz/submit"],
        "notes": "Blocking OkHttp client for quiz generation and evaluation.",
    },
    "PaymentApiClient": {
        "endpoints": ["/payments/razorpay/create-order", "/payments/razorpay/verify"],
        "notes": "Wraps Razorpay backend order and verification calls.",
    },
}


FIRESTORE_AND_LOCAL_STORAGE = {
    "FirestoreManager": (
        "Central Android Firestore helper for user metadata, subjects, chapters, conversations, "
        "messages, plans/offers, and various app documents. Offline disk cache is enabled."
    ),
    "ChatHistoryRepository": (
        "Per subject+chapter conversation wrapper around FirestoreManager.loadMessages/saveMessage."
    ),
    "NotesRepository": (
        "Local SharedPreferences note store keyed by userId + subject + chapter + note type."
    ),
    "ResponseCacheService": (
        "24-hour SharedPreferences cache for question/answer pairs."
    ),
    "StorageService": (
        "Persistent external Documents/AI Guru storage for PDFs, images, audio, cache, metadata."
    ),
}


ANDROID_STATE_PSEUDOCODE = """
def startup_state_load():
    AppStartRepository.fetchAll()
    AdminConfigRepository.fetchIfStale()
    SessionManager provides user identity
    Home/Chat/Blackboard screens fetch UserMetadata from FirestoreManager as needed


def chat_request_from_android():
    serverUrl = AdminConfigRepository.effectiveServerUrl()
    authHeader = TokenManager.buildAuthHeader()
    client = ServerProxyClient(serverUrl, ...)
    client.streamChat(question, pageId, mode, languageTag, history, imageData, imageBase64)


def persist_chat_result():
    ChatHistoryRepository.saveMessage(user message)
    stream response
    on success:
        PlanEnforcer.recordTokensUsed(...)
        ChatHistoryRepository.saveMessage(assistant message)
        optionally persist page transcript / notes / stats
""".strip()


KEY_FILES = {
    "app/src/main/java/com/aiguruapp/student/utils/SessionManager.kt": "Local session, plan, role, language, and UID helpers.",
    "app/src/main/java/com/aiguruapp/student/auth/TokenManager.kt": "Firebase ID token fetch and auth header construction.",
    "app/src/main/java/com/aiguruapp/student/config/AdminConfigRepository.kt": "Server URL and plan/config loader.",
    "app/src/main/java/com/aiguruapp/student/config/AppStartRepository.kt": "Parallel boot fetches at app launch.",
    "app/src/main/java/com/aiguruapp/student/config/PlanEnforcer.kt": "Android-side limits and token/quota helpers.",
    "app/src/main/java/com/aiguruapp/student/firestore/FirestoreManager.kt": "Central Firestore access layer.",
    "app/src/main/java/com/aiguruapp/student/chat/ServerProxyClient.kt": "Main backend stream client.",
    "app/src/main/java/com/aiguruapp/student/http/HttpClientManager.kt": "Shared OkHttp pools and timeout policies.",
}


RISKS_FOR_AGENTS = [
    "Changing SessionManager key semantics can break auth routing, Firestore IDs, role checks, and quota displays.",
    "Changing AdminConfigRepository affects effective server URL, feature flags, and payment setup across the app.",
    "Changing ServerProxyClient response parsing affects all normal chat and Blackboard streaming behavior.",
    "Changing FirestoreManager or repository wrappers can affect offline behavior and many screens at once.",
]

