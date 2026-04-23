"""
Android architecture summary for AI agents.

Update this file when changing:
- app/src/main/AndroidManifest.xml
- SplashActivity / BaseActivity / HomeActivity / ChatHostActivity / FullChatFragment / BlackboardActivity
- top-level navigation or entrypoint behavior
"""

APP_ENTRYPOINTS = {
    "manifest": "app/src/main/AndroidManifest.xml",
    "launcher_activity": "SplashActivity",
    "startup_sequence": [
        "SplashActivity",
        "OnboardingActivity if first launch",
        "HomeActivity for the main signed-in app shell",
        "LoginActivity if SessionManager says the user is not logged in",
    ],
}


BASE_SCREEN_ARCHITECTURE = {
    "BaseActivity": (
        "Shared base for most app screens. Enables edge-to-edge UI, applies school branding, "
        "and injects the floating calculator overlay."
    ),
    "SplashActivity": (
        "Performs update/maintenance gating, boots AppStartRepository in parallel, then routes "
        "to onboarding or HomeActivity."
    ),
    "HomeActivity": (
        "Main dashboard for signed-in users. Loads subjects, quotas, offers, drawer navigation, "
        "quick actions, and launch points into chat/Blackboard/library/tasks."
    ),
    "ChatHostActivity": (
        "Thin activity that hosts FullChatFragment and forwards launch-time extras like autoPrompt, "
        "PDF page, or image path."
    ),
    "FullChatFragment": (
        "Main student chat implementation. Handles images, PDF pages, voice, TTS, notes, "
        "history persistence, backend streaming, and Blackboard nudges."
    ),
    "ChapterActivity": (
        "Tabbed chapter workspace that hosts FullChatFragment, page list/PDF flow, saved sessions, "
        "and quiz launch."
    ),
    "BlackboardActivity": (
        "Dedicated full-screen, step-by-step lesson player with progressive loading, TTS/AI TTS, "
        "interactive quiz frames, ask-bar followups, and session save/publish flows."
    ),
}


MAIN_ACTIVITY_MAP = {
    "auth_and_profile": [
        "LoginActivity",
        "EmailAuthActivity",
        "SignupActivity",
        "SchoolLoginActivity",
        "SchoolJoinActivity",
        "UserProfileActivity",
    ],
    "student_learning": [
        "HomeActivity",
        "SubjectActivity",
        "ChapterActivity",
        "ChatHostActivity",
        "BlackboardActivity",
        "QuizSetupActivity",
        "QuizActivity",
        "QuizResultActivity",
        "LibraryActivity",
        "PageViewerActivity",
        "RevisionActivity",
        "NcertViewerActivity",
        "NotesActivity",
        "ProgressDashboardActivity",
    ],
    "subscription_and_plans": [
        "SubscriptionActivity",
        "ModelSettingsActivity",
    ],
    "teacher_and_school": [
        "TeacherDashboardActivity",
        "TeacherTasksActivity",
        "TeacherTaskReportActivity",
        "TeacherChatReviewActivity",
        "TeacherChatHostActivity",
        "TeacherSavedContentActivity",
        "TeacherQuizValidationActivity",
        "SchoolAdminActivity",
        "TasksActivity",
    ],
}


KEY_LAYOUT_FACTS = {
    "chat_hosting_pattern": (
        "activity_chat_host.xml is only a fragment host; the real chat UI lives in activity_chat.xml "
        "and is rendered by FullChatFragment."
    ),
    "blackboard_rendering_pattern": (
        "BlackboardActivity builds and updates a dynamic lesson view tree at runtime, including SVG/WebView "
        "content, rather than using one static card per step in XML."
    ),
    "chapter_pattern": (
        "ChapterActivity is a workspace shell with tabs for Pages, Chat, and saved Blackboard sessions."
    ),
}


ARCHITECTURE_PSEUDOCODE = """
def app_launch():
    open SplashActivity
    SplashActivity.fetch_bootstrap_data_in_parallel()
    SplashActivity.check_updates_and_maintenance()
    if onboarding_not_done:
        open OnboardingActivity
    else:
        open HomeActivity


def main_student_navigation():
    HomeActivity -> SubjectActivity -> ChapterActivity
    ChapterActivity tabs:
        Pages tab
        Chat tab -> FullChatFragment
        Saved tab -> saved BB sessions / notes
    BlackboardActivity can be launched from:
        Home quick action
        FullChatFragment nudge / action
        ChapterActivity
        task replay / saved session / teacher flows
""".strip()


KEY_FILES = {
    "app/src/main/AndroidManifest.xml": "Permissions, activity registration, launcher, and exported screens.",
    "app/src/main/java/com/aiguruapp/student/SplashActivity.kt": "Real entrypoint and update gate.",
    "app/src/main/java/com/aiguruapp/student/BaseActivity.kt": "Shared edge-to-edge + branding + calculator behavior.",
    "app/src/main/java/com/aiguruapp/student/HomeActivity.kt": "Main dashboard and navigation hub.",
    "app/src/main/java/com/aiguruapp/student/ChatHostActivity.kt": "Thin wrapper around FullChatFragment.",
    "app/src/main/java/com/aiguruapp/student/FullChatFragment.kt": "Largest student chat implementation.",
    "app/src/main/java/com/aiguruapp/student/ChapterActivity.kt": "Chapter workspace and tab shell.",
    "app/src/main/java/com/aiguruapp/student/BlackboardActivity.kt": "Full Blackboard lesson player.",
}


RISKS_FOR_AGENTS = [
    "Changing SplashActivity can silently break update-gating or onboarding routing for the whole app.",
    "Changing FullChatFragment usually affects both standalone chat and embedded chapter chat because the same fragment is reused.",
    "Changing SessionManager login assumptions can cascade into HomeActivity routing, Firestore IDs, quotas, and backend auth headers.",
    "Changing manifest export/launchMode values can break Razorpay UPI return flow or deep-link style handoffs.",
]

