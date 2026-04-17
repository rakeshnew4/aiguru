#!/usr/bin/env python3
"""
seed_roles_and_features.py
--------------------------
Seeds Firestore with:

  1. plans/{planId}/limits  — adds new feature-flag fields to every plan
     (uses merge so existing quota/limit fields are not touched)

  2. app_config/page_access  — documents the role + plan-feature requirements
     for every page key.  The app reads AccessGate.kt; this doc is the
     admin-visible "source of truth" and can be used by future admin tools.

Run from the repo root:
    python seed_roles_and_features.py

Safe to re-run (idempotent — uses set/merge).
"""

import os, sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR / "fastapi server"))

import firebase_admin
from firebase_admin import credentials, firestore

# ── Auth ──────────────────────────────────────────────────────────────────────
SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR / "firebase_serviceaccount.json"),
)
if not os.path.exists(SA_PATH):
    print(f"ERROR: Service-account file not found: {SA_PATH}")
    sys.exit(1)

if not firebase_admin._apps:
    cred = credentials.Certificate(SA_PATH)
    firebase_admin.initialize_app(cred)

db = firestore.client()

# ─────────────────────────────────────────────────────────────────────────────
# 1.  PLAN FEATURE FLAGS
#     Defines what each plan can access.
#     Fields are nested under `limits` in the plan document to match PlanLimits.kt
#
#     free       — everything on, but ai_tts off and lower quotas
#     student_basic — same as free with slightly more quota
#     student_pro   — all features, higher quota
#     school_unlimited — everything, unlimited
# ─────────────────────────────────────────────────────────────────────────────

PLAN_LIMITS_FEATURES = {
    "free": {
        # Page / section toggles
        "progress_dashboard_enabled": True,
        "library_enabled":            True,
        "quiz_enabled":               True,
        "revision_enabled":           True,
        "ncert_viewer_enabled":       True,
        # Existing feature flags (ensure they're present)
        "blackboard_enabled":         True,
        "voice_mode_enabled":         True,
        "ai_tts_enabled":             False,   # AI voice off on free
        "pdf_enabled":                False,
        "flashcards_enabled":         True,
        "image_upload_enabled":       False,
    },
    "student_basic": {
        "progress_dashboard_enabled": True,
        "library_enabled":            True,
        "quiz_enabled":               True,
        "revision_enabled":           True,
        "ncert_viewer_enabled":       True,
        "blackboard_enabled":         True,
        "voice_mode_enabled":         True,
        "ai_tts_enabled":             True,
        "pdf_enabled":                True,
        "flashcards_enabled":         True,
        "image_upload_enabled":       True,
    },
    "student_pro": {
        "progress_dashboard_enabled": True,
        "library_enabled":            True,
        "quiz_enabled":               True,
        "revision_enabled":           True,
        "ncert_viewer_enabled":       True,
        "blackboard_enabled":         True,
        "voice_mode_enabled":         True,
        "ai_tts_enabled":             True,
        "pdf_enabled":                True,
        "flashcards_enabled":         True,
        "image_upload_enabled":       True,
    },
    "school_unlimited": {
        "progress_dashboard_enabled": True,
        "library_enabled":            True,
        "quiz_enabled":               True,
        "revision_enabled":           True,
        "ncert_viewer_enabled":       True,
        "blackboard_enabled":         True,
        "voice_mode_enabled":         True,
        "ai_tts_enabled":             True,
        "pdf_enabled":                True,
        "flashcards_enabled":         True,
        "image_upload_enabled":       True,
    },
}

def seed_plan_features():
    plans_ref = db.collection("plans")

    # Fetch all existing plan IDs
    existing_ids = {doc.id for doc in plans_ref.stream()}
    # Add any plan IDs that are defined here but don't exist yet
    all_ids = existing_ids | set(PLAN_LIMITS_FEATURES.keys())

    for plan_id in all_ids:
        features = PLAN_LIMITS_FEATURES.get(plan_id, PLAN_LIMITS_FEATURES["free"])
        # Merge the feature flags into the `limits` sub-map
        plans_ref.document(plan_id).set({"limits": features}, merge=True)
        print(f"  ✓ plans/{plan_id}/limits  — feature flags merged")

# ─────────────────────────────────────────────────────────────────────────────
# 2.  PAGE ACCESS RULES
#     Stored at: app_config/page_access
#     Fields:
#       pages.<page_key>.display_name    — human-readable label
#       pages.<page_key>.allowed_roles   — ["guest","student","student_school","teacher"]
#       pages.<page_key>.plan_feature    — PlanLimits field key (or null)
#       pages.<page_key>.description     — notes for admin
# ─────────────────────────────────────────────────────────────────────────────

PAGE_ACCESS = {
    # ── Teacher-only ──────────────────────────────────────────────────────────
    "teacher_dashboard": {
        "display_name":  "Teacher Dashboard",
        "allowed_roles": ["teacher"],
        "plan_feature":  None,
        "description":   "Manage students and view stats. Registered teachers only.",
    },
    "teacher_tasks": {
        "display_name":  "Teacher Tasks",
        "allowed_roles": ["teacher"],
        "plan_feature":  None,
        "description":   "Create and assign tasks to students. Teachers only.",
    },
    "teacher_quiz_validation": {
        "display_name":  "Quiz Validation",
        "allowed_roles": ["teacher"],
        "plan_feature":  None,
        "description":   "Review and publish AI-generated quizzes. Teachers only.",
    },
    "teacher_chat_review": {
        "display_name":  "Chat Review",
        "allowed_roles": ["teacher"],
        "plan_feature":  None,
        "description":   "Review student chat transcripts. Teachers only.",
    },
    "teacher_saved_content": {
        "display_name":  "Saved Content",
        "allowed_roles": ["teacher"],
        "plan_feature":  None,
        "description":   "Manage saved blackboard sessions. Teachers only.",
    },

    # ── School-registered students + teachers ─────────────────────────────────
    "tasks": {
        "display_name":  "My Tasks",
        "allowed_roles": ["student_school", "teacher"],
        "plan_feature":  None,
        "description":   "Assigned tasks for school-registered students.",
    },

    # ── All authenticated users (no guest) + plan gated ──────────────────────
    "progress_dashboard": {
        "display_name":  "Progress Dashboard",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "progress_dashboard_enabled",
        "description":   "AI learning progress charts. Requires login + plan flag.",
    },
    "blackboard": {
        "display_name":  "Visual Blackboard",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "blackboard_enabled",
        "description":   "Interactive AI whiteboard session.",
    },
    "library": {
        "display_name":  "Chapter Library",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "library_enabled",
        "description":   "Saved chapters and notes.",
    },
    "quiz": {
        "display_name":  "Practice Quiz",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "quiz_enabled",
        "description":   "AI-generated MCQ and short-answer quizzes.",
    },
    "revision": {
        "display_name":  "Revision Mode",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "revision_enabled",
        "description":   "Guided revision with AI.",
    },
    "ncert_viewer": {
        "display_name":  "NCERT Viewer",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "ncert_viewer_enabled",
        "description":   "In-app NCERT PDF viewer.",
    },
    "ai_voice": {
        "display_name":  "AI Voice (TTS)",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "ai_tts_enabled",
        "description":   "Server-side AI voice synthesis.",
    },
    "voice_mode": {
        "display_name":  "Voice Mode",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "voice_mode_enabled",
        "description":   "Microphone-based voice input in chat.",
    },
    "pdf_upload": {
        "display_name":  "PDF Upload",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "pdf_enabled",
        "description":   "Upload custom PDF chapters.",
    },
    "flashcards": {
        "display_name":  "Flashcards",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  "flashcards_enabled",
        "description":   "AI-generated flashcard sets.",
    },
    "user_profile": {
        "display_name":  "Profile",
        "allowed_roles": ["student", "student_school", "teacher"],
        "plan_feature":  None,
        "description":   "View / edit user profile. Requires login.",
    },

    # ── Join school (student without school only) ─────────────────────────────
    "join_school": {
        "display_name":  "Join a School",
        "allowed_roles": ["student"],
        "plan_feature":  None,
        "description":   "Link account to a school via referral code. Hidden once joined.",
    },

    # ── Available to everyone ─────────────────────────────────────────────────
    "subscription_plans": {
        "display_name":  "Subscription Plans",
        "allowed_roles": ["guest", "student", "student_school", "teacher"],
        "plan_feature":  None,
        "description":   "Upgrade / manage subscription. Visible to all users.",
    },
    "chat": {
        "display_name":  "AI Chat",
        "allowed_roles": ["guest", "student", "student_school", "teacher"],
        "plan_feature":  None,
        "description":   "General AI chat. Quota-limited but open to all.",
    },
}

def seed_page_access():
    doc_ref = db.collection("app_config").document("page_access")
    doc_ref.set({"pages": PAGE_ACCESS, "_seeded_by": "seed_roles_and_features.py"}, merge=True)
    print(f"  ✓ app_config/page_access  — {len(PAGE_ACCESS)} page rules written")

# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("\n=== Seeding plan feature flags ===")
    seed_plan_features()

    print("\n=== Seeding page access rules ===")
    seed_page_access()

    print("\nDone ✓")
    print("\nTo toggle a feature for a plan, update plans/{planId}/limits in Firebase console.")
    print("E.g. set  plans/free/limits/quiz_enabled = false  to disable quiz for free users.")
