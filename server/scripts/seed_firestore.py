"""
Seed Firestore with plans, credits config, and sample daily question templates.

Usage:
    cd server
    python -m scripts.seed_firestore

Requires GOOGLE_APPLICATION_CREDENTIALS or ADC to be set up.
"""

from __future__ import annotations

import os
import sys

# Allow running from server/ or server/scripts/
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import firebase_admin
from firebase_admin import credentials, firestore


def _init_firebase():
    if not firebase_admin._apps:
        cred_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
        if cred_path:
            cred = credentials.Certificate(cred_path)
        else:
            cred = credentials.ApplicationDefault()
        firebase_admin.initialize_app(cred)
    return firestore.client()


# ── Plan definitions ──────────────────────────────────────────────────────────

PLANS = {
    "free": {
        "planId": "free",
        "name": "Free",
        "price_inr": 0,
        "duration_days": 0,  # forever
        "limits": {
            "daily_chat_questions": 12,
            "daily_bb_sessions": 2,
            "tts_enabled": True,
            "ai_tts_enabled": False,
            "blackboard_enabled": True,
            "image_upload_enabled": False,
            "quiz_enabled": True,
            "credits_on_activation": 0,
            "monthly_bonus_credits": 0,
        },
        "description": "Basic access with daily limits",
        "active": True,
    },
    "basic": {
        "planId": "basic",
        "name": "Basic",
        "price_inr": 99,
        "duration_days": 30,
        "limits": {
            "daily_chat_questions": 50,
            "daily_bb_sessions": 10,
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "blackboard_enabled": True,
            "image_upload_enabled": True,
            "quiz_enabled": True,
            "credits_on_activation": 100,
            "monthly_bonus_credits": 50,
        },
        "description": "Enhanced limits with AI TTS and image uploads",
        "active": True,
    },
    "pro": {
        "planId": "pro",
        "name": "Pro",
        "price_inr": 249,
        "duration_days": 30,
        "limits": {
            "daily_chat_questions": 0,       # 0 = unlimited
            "daily_bb_sessions": 0,          # 0 = unlimited
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "blackboard_enabled": True,
            "image_upload_enabled": True,
            "quiz_enabled": True,
            "credits_on_activation": 300,
            "monthly_bonus_credits": 150,
        },
        "description": "Unlimited access with all features",
        "active": True,
    },
    "school": {
        "planId": "school",
        "name": "School",
        "price_inr": 0,           # billed per-school separately
        "duration_days": 365,
        "limits": {
            "daily_chat_questions": 0,
            "daily_bb_sessions": 0,
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "blackboard_enabled": True,
            "image_upload_enabled": True,
            "quiz_enabled": True,
            "credits_on_activation": 200,
            "monthly_bonus_credits": 100,
        },
        "description": "School-wide access managed by institution",
        "active": True,
    },
}


# ── Daily question templates (used as fallback / LLM priming) ─────────────────

QUESTION_TEMPLATES = [
    {
        "question": "Why does ice float on water instead of sinking?",
        "subject": "Science",
        "topic": "States of Matter",
        "difficulty": 1,
        "hook": "Ice saves aquatic life in frozen lakes every winter",
    },
    {
        "question": "How does your brain store memories while you sleep?",
        "subject": "Biology",
        "topic": "Neuroscience",
        "difficulty": 2,
        "hook": "You replay your day 5x faster overnight",
    },
    {
        "question": "What would happen if gravity suddenly doubled on Earth?",
        "subject": "Physics",
        "topic": "Gravity",
        "difficulty": 3,
        "hook": "Most skyscrapers would collapse within minutes",
    },
    {
        "question": "Why do we yawn when we see others yawning?",
        "subject": "Biology",
        "topic": "Human Behavior",
        "difficulty": 1,
        "hook": "Even reading about yawning can trigger one",
    },
    {
        "question": "How did ancient Egyptians build the pyramids without modern machines?",
        "subject": "History",
        "topic": "Ancient Civilizations",
        "difficulty": 2,
        "hook": "Workers were paid employees, not slaves",
    },
    {
        "question": "Why do stars twinkle but planets appear as steady lights?",
        "subject": "Science",
        "topic": "Astronomy",
        "difficulty": 2,
        "hook": "Light travels 8 minutes from the Sun to reach your eye",
    },
    {
        "question": "How does the internet physically move data across continents?",
        "subject": "Technology",
        "topic": "Networking",
        "difficulty": 2,
        "hook": "99% of internet data travels through underwater cables",
    },
    {
        "question": "Why do your fingernails grow faster than your toenails?",
        "subject": "Biology",
        "topic": "Human Body",
        "difficulty": 1,
        "hook": "Nails grow faster on your dominant hand",
    },
    {
        "question": "What stops an airplane from falling out of the sky?",
        "subject": "Physics",
        "topic": "Aerodynamics",
        "difficulty": 2,
        "hook": "A plane can glide 100 km with no engine power",
    },
    {
        "question": "Why does music make some people feel strong emotions?",
        "subject": "Biology",
        "topic": "Neuroscience",
        "difficulty": 2,
        "hook": "Music triggers the same brain reward as food and love",
    },
]


def seed_plans(db) -> None:
    print("Seeding plans...")
    for plan_id, plan_data in PLANS.items():
        db.collection("plans").document(plan_id).set(plan_data, merge=True)
        print(f"  ✓ plans/{plan_id}")
    print(f"  {len(PLANS)} plans seeded.")


def seed_question_templates(db) -> None:
    print("Seeding question templates...")
    for i, tpl in enumerate(QUESTION_TEMPLATES):
        doc_id = f"tpl_{i:03d}"
        db.collection("daily_question_templates").document(doc_id).set(tpl, merge=True)
        print(f"  ✓ daily_question_templates/{doc_id}: {tpl['question'][:40]}...")
    print(f"  {len(QUESTION_TEMPLATES)} templates seeded.")


def seed_credit_config(db) -> None:
    print("Seeding credit config...")
    db.collection("app_config").document("credits").set({
        "daily_question_reward": 5,
        "task_completion_base_reward": 10,
        "referral_reward_referrer": 25,
        "referral_reward_referred": 10,
        "streak_bonus_7day": 20,
        "streak_bonus_30day": 100,
        "credits_per_inr": 10,  # 10 credits = ₹1 worth
    }, merge=True)
    print("  ✓ app_config/credits")


def main():
    print("Initializing Firebase...")
    db = _init_firebase()
    print("Connected.\n")

    seed_plans(db)
    print()
    seed_question_templates(db)
    print()
    seed_credit_config(db)
    print("\nSeed complete.")


if __name__ == "__main__":
    main()
