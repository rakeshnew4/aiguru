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
        "duration_days": 0,  # never expires
        "limits": {
            # ── Daily session gates ───────────────────────────────────────────
            "daily_chat_questions": 12,
            "daily_bb_sessions": 2,
            # ── Monthly token / TTS ceilings (0 = unlimited) ─────────────────
            "monthly_token_limit": 50_000,
            "monthly_tts_char_limit": 10_000,
            # ── Feature flags ─────────────────────────────────────────────────
            "tts_enabled": True,
            "ai_tts_enabled": False,
            "blackboard_enabled": True,
            "image_upload_enabled": False,
            "quiz_enabled": True,
            # ── Credits (Free gives 600 ≈ 60 000 tokens of usage budget) ─────
            # Each LLM call deducts floor(tokens/100); each TTS call deducts floor(chars/100)
            "credits_on_activation": 600,
            "monthly_bonus_credits": 0,
        },
        "description": "Free tier — 600 credits/month (≈60K tokens). Earn more by completing daily questions.",
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
            "monthly_token_limit": 300_000,
            "monthly_tts_char_limit": 100_000,
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "blackboard_enabled": True,
            "image_upload_enabled": True,
            "quiz_enabled": True,
            "credits_on_activation": 3_000,        # ~300K tokens of usage
            "monthly_bonus_credits": 500,
        },
        "description": "Basic — 3 000 credits/month (≈300K tokens) + AI TTS + image uploads.",
        "active": True,
    },
    "pro": {
        "planId": "pro",
        "name": "Pro",
        "price_inr": 249,
        "duration_days": 30,
        "limits": {
            "daily_chat_questions": 0,              # 0 = unlimited (gate disabled)
            "daily_bb_sessions": 0,
            "monthly_token_limit": 0,
            "monthly_tts_char_limit": 0,
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "blackboard_enabled": True,
            "image_upload_enabled": True,
            "quiz_enabled": True,
            "credits_on_activation": 10_000,       # ~1M tokens of usage
            "monthly_bonus_credits": 2_000,
        },
        "description": "Pro — 10 000 credits/month (≈1M tokens). Unlimited gates.",
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
            "monthly_token_limit": 0,
            "monthly_tts_char_limit": 0,
            "tts_enabled": True,
            "ai_tts_enabled": True,
            "blackboard_enabled": True,
            "image_upload_enabled": True,
            "quiz_enabled": True,
            "credits_on_activation": 5_000,
            "monthly_bonus_credits": 1_000,
        },
        "description": "School — 5 000 credits/month + 1 000 bonus. Institution managed.",
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


CREDIT_TOPUPS = {
    "topup_500": {
        "name": "500 Credits",
        "credits": 500,
        "bonus_credits": 0,
        "price_inr": 49,
        "description": "Quick top-up — about 50K tokens",
        "active": True,
        "popular": False,
    },
    "topup_1500": {
        "name": "1 500 Credits",
        "credits": 1_500,
        "bonus_credits": 200,
        "price_inr": 129,
        "description": "Best value — 200 bonus credits",
        "active": True,
        "popular": True,
    },
    "topup_5000": {
        "name": "5 000 Credits",
        "credits": 5_000,
        "bonus_credits": 1_000,
        "price_inr": 399,
        "description": "Power user — 1 000 bonus credits",
        "active": True,
        "popular": False,
    },
}


def seed_credit_topups(db) -> None:
    print("Seeding credit top-up packs...")
    for pack_id, pack in CREDIT_TOPUPS.items():
        db.collection("credit_topups").document(pack_id).set(pack, merge=True)
        total = pack["credits"] + pack["bonus_credits"]
        print(f"  ✓ credit_topups/{pack_id}: {total} credits @ ₹{pack['price_inr']}")
    print(f"  {len(CREDIT_TOPUPS)} top-up packs seeded.")


def seed_credit_config(db) -> None:
    print("Seeding credit config...")
    db.collection("app_config").document("credits").set({
        # ── Conversion rates (server uses these) ──────────────────────────────
        "tokens_per_credit": 100,       # 100 LLM tokens = 1 credit earned
        "tts_chars_per_credit": 100,    # 100 TTS chars  = 1 credit earned
        # ── Activity rewards ──────────────────────────────────────────────────
        "daily_question_reward": 5,
        "task_completion_base_reward": 10,
        "referral_reward_referrer": 25,
        "referral_reward_referred": 10,
        "streak_bonus_7day": 20,
        "streak_bonus_30day": 100,
        # ── Monetary reference ────────────────────────────────────────────────
        "credits_per_inr": 10,          # 10 credits = ₹1 face value
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
    print()
    seed_credit_topups(db)
    print("\nSeed complete.")


if __name__ == "__main__":
    main()
