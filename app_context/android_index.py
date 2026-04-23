"""
Entry index for Android app context.

Use this first when an AI agent needs to understand the Android side of the
project without scanning the full `app/src/main/java` tree.

Update this file when:
- a new Android context module is added
- responsibilities move between screens or state layers
- the recommended reading order changes
"""

CONTEXT_FILE_PURPOSES = {
    "app_context/android_architecture.py": (
        "Manifest entrypoints, core activities/fragments, app structure, startup, "
        "branding, and top-level navigation."
    ),
    "app_context/android_learning_flows.py": (
        "Student learning flows: Home -> Subject -> Chapter -> Chat/Blackboard/Quiz, "
        "plus teacher/saved-session entrypoints."
    ),
    "app_context/android_state_and_network.py": (
        "Session, auth token handling, admin config, quotas, Firestore/local state, "
        "OkHttp clients, and backend API callers."
    ),
}


ROUTING_GUIDE = {
    "Need app startup, screen map, or navigation": "app_context/android_architecture.py",
    "Need chat, Blackboard, quiz, or chapter UX flow": "app_context/android_learning_flows.py",
    "Need session/config/quota/network/storage behavior": "app_context/android_state_and_network.py",
}


ACTIVE_SOURCE_ROOTS = {
    "manifest": "app/src/main/AndroidManifest.xml",
    "android_app_code": "app/src/main/java/com/aiguruapp/student",
    "layouts": "app/src/main/res/layout",
    "assets": "app/src/main/assets",
}


IMPORTANT_REALITIES = [
    "SplashActivity is the true launcher; HomeActivity is not the LAUNCHER screen.",
    "FullChatFragment is the main chat implementation; ChatHostActivity is mostly a thin wrapper.",
    "BlackboardActivity is a separate full-screen lesson system, not just a chat mode toggle.",
    "Android still stores some state locally even though quotas and plan activation are now server-authoritative.",
    "AdminConfigRepository and AppStartRepository are the main runtime-config/bootstrap loaders from Firestore.",
]


AGENT_READ_ORDER = """
1. Start here to choose the right Android context file.
2. Read android_architecture.py for startup, manifest, main screens, and activity/fragment roles.
3. Read android_learning_flows.py for user journeys and feature-specific behavior.
4. Read android_state_and_network.py for auth, quotas, Firestore, SharedPreferences, storage, and API clients.
5. After changing Android code, update the matching context file so it stays usable as an LLM routing layer.
""".strip()

