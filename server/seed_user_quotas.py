#!/usr/bin/env python3
"""
seed_user_quotas.py
-------------------
Backfills quota / feature-flag fields on ALL existing user documents in Firestore.

For each user doc in users/ this script:
  1. Reads the user's current planId
  2. Looks up that plan's limits from the plans/ collection
  3. Writes the following fields to the user document (merge – never overwrites other fields):
       plan_daily_chat_limit   – max chat questions/day (0 = unlimited)
       plan_daily_bb_limit     – max blackboard sessions/day (0 = unlimited)
       plan_tts_enabled        – Android built-in TTS allowed
       plan_ai_tts_enabled     – AI voice synthesis allowed
       plan_blackboard_enabled – Blackboard mode allowed
       plan_image_enabled      – Image/PDF upload allowed

Run once (idempotent – safe to re-run):

    python seed_user_quotas.py

Service-account JSON is resolved in this order:
  1. FIREBASE_SERVICE_ACCOUNT env var
  2. fastapi server/firebase_serviceaccount.json  (relative to repo root)
"""

import os
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR / "fastapi server"))

import firebase_admin
from firebase_admin import credentials, firestore

# ── Credentials ───────────────────────────────────────────────────────────────
SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR / "fastapi server" / "firebase_serviceaccount.json"),
)

if not os.path.exists(SA_PATH):
    print(f"ERROR: Service-account file not found at:\n  {SA_PATH}")
    sys.exit(1)

cred = credentials.Certificate(SA_PATH)
firebase_admin.initialize_app(cred)
db = firestore.client()

# ── Per-plan quota definitions (must mirror seed_firestore.py PLANS[].limits) ─
# Keys match the Firestore field names inside plans/{planId}.limits
PLAN_LIMITS = {
    "free": {
        "daily_chat_questions": 12,
        "daily_bb_sessions":    2,
        "tts_enabled":          True,
        "ai_tts_enabled":       False,
        "blackboard_enabled":   True,
        "image_upload_enabled": False,
    },
    "student_basic": {
        "daily_chat_questions": 50,
        "daily_bb_sessions":    10,
        "tts_enabled":          True,
        "ai_tts_enabled":       False,
        "blackboard_enabled":   True,
        "image_upload_enabled": True,
    },
    "student_pro": {
        "daily_chat_questions": 0,   # 0 = unlimited
        "daily_bb_sessions":    0,
        "tts_enabled":          True,
        "ai_tts_enabled":       True,
        "blackboard_enabled":   True,
        "image_upload_enabled": True,
    },
    "school_unlimited": {
        "daily_chat_questions": 0,
        "daily_bb_sessions":    0,
        "tts_enabled":          True,
        "ai_tts_enabled":       True,
        "blackboard_enabled":   True,
        "image_upload_enabled": True,
    },
}

# Default (free-tier equivalent) used when planId is blank or unknown
DEFAULT_LIMITS = PLAN_LIMITS["free"]


def resolve_limits(plan_id: str) -> dict:
    """Return the limit dict for a given planId, falling back to free defaults."""
    return PLAN_LIMITS.get(plan_id or "free", DEFAULT_LIMITS)


def run():
    users_ref = db.collection("users")
    docs = list(users_ref.stream())
    total = len(docs)
    print(f"Found {total} user document(s).\n")

    updated = 0
    skipped = 0
    batch = db.batch()
    batch_count = 0

    for doc in docs:
        data = doc.to_dict() or {}
        uid = doc.id
        plan_id = data.get("planId", "free") or "free"
        lims = resolve_limits(plan_id)

        updates = {
            # Quota limits written to user doc so the Android app can read them
            # directly without requiring the admin_config/plans fetch to succeed.
            "plan_daily_chat_limit":  lims["daily_chat_questions"],
            "plan_daily_bb_limit":    lims["daily_bb_sessions"],
            "plan_tts_enabled":       lims["tts_enabled"],
            "plan_ai_tts_enabled":    lims["ai_tts_enabled"],
            "plan_blackboard_enabled": lims["blackboard_enabled"],
            "plan_image_enabled":     lims["image_upload_enabled"],
        }

        # Also seed question counters / timestamp if they are missing entirely,
        # so the day-rollover logic has something to compare against.
        if "chat_questions_today" not in data:
            updates["chat_questions_today"] = 0
        if "bb_sessions_today" not in data:
            updates["bb_sessions_today"] = 0
        if "questions_updated_at" not in data:
            updates["questions_updated_at"] = 0   # epoch 0 → treated as "different day" = fresh start
        if "bonus_questions_today" not in data:
            updates["bonus_questions_today"] = 0

        user_doc_ref = users_ref.document(uid)
        batch.set(user_doc_ref, updates, merge=True)
        batch_count += 1
        updated += 1
        print(f"  ✓  users/{uid}  plan={plan_id}  "
              f"chat_limit={lims['daily_chat_questions']}  "
              f"bb_limit={lims['daily_bb_sessions']}")

        # Firestore batch limit is 500 writes
        if batch_count >= 400:
            batch.commit()
            batch = db.batch()
            batch_count = 0

    if batch_count > 0:
        batch.commit()

    print(f"\n✅  Done — updated {updated} users, skipped {skipped}.")


if __name__ == "__main__":
    run()
