#!/usr/bin/env python3
"""
seed_user_defaults.py
---------------------
Reads the template user document from users/{SOURCE_UID} and stores it in
admin_config/user_defaults as the { default_data: {...} } field.

On every new user registration, user_service.copy_default_data_to_user(uid)
reads this document and merges it into the new user's document — giving all
new users a consistent starting state (subjects, chapters, preferences, etc.)
configured by the admin.

SOURCE_UID is the Firestore user document to use as the template.
Identity/auth fields (userId, email, name, …) are automatically excluded
before storing so they are never copied to other users.

Usage:
    python seed_user_defaults.py
    python seed_user_defaults.py --source-uid <other_uid>
    python seed_user_defaults.py --dry-run
"""

import argparse
import json
import os
import sys
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, firestore

# ── Config ────────────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR / "firebase_serviceaccount.json"),
)

SOURCE_UID = "LpdfEUxoArdZw7QzTQOr9rCIDrB3"   # the template user document

# Fields that must NEVER be copied to other users
_EXCLUDE_FIELDS = {
    "userId", "uid", "email", "name", "grade", "schoolId", "schoolName",
    "created_at", "updated_at", "last_active", "last_login",
    "planId", "planName", "plan_start_date", "plan_expiry_date",
    "plan_daily_chat_limit", "plan_daily_bb_limit",
    "tts_enabled", "ai_tts_enabled", "blackboard_enabled", "image_upload_enabled",
    "chat_questions_today", "bb_sessions_today", "questions_updated_at",
    "credits", "tts_credits", "starter_credits_given",
    "litellm_key", "fcm_token", "referral_code", "referred_by",
    "daily_chat_questions", "daily_bb_sessions",
}


def _copy_subcollections(src_ref, dst_ref, dry_run: bool, depth: int = 0, max_depth: int = 3) -> int:
    """
    Recursively copies all subcollections from src_ref to dst_ref as real Firestore
    subcollections (not embedded in the document). Batches writes, 499 per flush.
    Returns total number of docs written.
    """
    if depth >= max_depth:
        return 0
    total = 0
    for col_ref in src_ref.collections():
        col_name = col_ref.id
        child_docs = list(col_ref.stream())
        batch = db.batch() if not dry_run else None
        count = 0
        for doc in child_docs:
            dst_doc_ref = dst_ref.collection(col_name).document(doc.id)
            if not dry_run:
                batch.set(dst_doc_ref, doc.to_dict() or {})
            count += 1
            if not dry_run and count % 499 == 0:
                batch.commit()
                batch = db.batch()
        if not dry_run and count > 0 and count % 499 != 0:
            batch.commit()
        indent = "  " * depth
        print(f"    {indent}'{col_name}': {count} docs")
        total += count
        # recurse into each child doc's own subcollections
        for doc in child_docs:
            dst_doc_ref = dst_ref.collection(col_name).document(doc.id)
            total += _copy_subcollections(doc.reference, dst_doc_ref, dry_run, depth + 1, max_depth)
    return total

# ── Args ──────────────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="Seed admin_config/user_defaults from a template user")
parser.add_argument("--source-uid", default=SOURCE_UID,
                    help=f"Firestore UID of the template user (default: {SOURCE_UID})")
parser.add_argument("--dry-run", action="store_true",
                    help="Print what would be written without writing to Firestore")
args = parser.parse_args()

# ── Firestore init ────────────────────────────────────────────────────────────
if not os.path.exists(SA_PATH):
    print(f"ERROR: Service-account file not found:\n  {SA_PATH}")
    sys.exit(1)

cred = credentials.Certificate(SA_PATH)
firebase_admin.initialize_app(cred)
db = firestore.client()

# ── Read source document ──────────────────────────────────────────────────────
print(f"\n{'='*60}")
print(f"  Reading template user: users/{args.source_uid}")
print(f"{'='*60}")

source_ref = db.collection("users").document(args.source_uid)
source_doc = source_ref.get()

if not source_doc.exists:
    print(f"\nERROR: Document users/{args.source_uid} does not exist.")
    print("Check the UID and try again.")
    sys.exit(1)

raw_data = source_doc.to_dict() or {}
print(f"  Found {len(raw_data)} fields in root document")

# Strip identity/auth fields
default_data = {k: v for k, v in raw_data.items() if k not in _EXCLUDE_FIELDS}
excluded = [k for k in raw_data if k in _EXCLUDE_FIELDS]

print(f"\n  Excluded {len(excluded)} identity/auth fields: {', '.join(sorted(excluded)) or '(none)'}")
print(f"\n  Root fields to copy ({len(default_data)}):")
for k, v in sorted(default_data.items()):
    val_preview = str(v)[:80] + "..." if len(str(v)) > 80 else str(v)
    print(f"    {k}: {val_preview}")

# Write root fields first
target_path = "admin_config/user_defaults"
template_ref = db.collection("admin_config").document("user_defaults")

# Copy subcollections as real Firestore subcollections (avoids 1 MB doc limit)
print(f"\n  Copying subcollections to {target_path}/ ...")
total_docs = _copy_subcollections(source_ref, template_ref, args.dry_run)
print(f"  Total subcollection docs: {total_docs}")

if args.dry_run:
    print(f"\n[DRY RUN] Would write root document to {target_path}:")
    print(json.dumps({"default_data": default_data}, indent=2, default=str))
    print(f"\n[DRY RUN] Would copy {total_docs} subcollection docs to {target_path}/<col>/<id>")
    print("\n[DRY RUN] No changes written.")
else:
    print(f"\n  Writing root document to {target_path} ...")
    template_ref.set({"default_data": default_data})
    print(f"\n✅  admin_config/user_defaults written successfully")
    print(f"    Root fields       : {len(default_data)}")
    print(f"    Total subcol docs : {total_docs}")
    print()
    print("New users will automatically receive these defaults on first registration.")
    print("To update the defaults, edit the template user and re-run this script.")
