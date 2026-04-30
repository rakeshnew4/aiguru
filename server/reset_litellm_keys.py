#!/usr/bin/env python3
"""
reset_litellm_keys.py
---------------------
Deletes all existing per-user LiteLLM keys from:
  1. LiteLLM proxy (DELETE /key/delete)
  2. Firestore users_table (clears the litellm_key field)

After running this, the next LLM request each user makes will trigger
get_or_create_litellm_key() in llm_service.py, which auto-creates a fresh
key with access to ALL proxy models (no models restriction).

Usage:
    cd aiguru/server
    python reset_litellm_keys.py

    # Dry-run (show what would happen, make no changes):
    python reset_litellm_keys.py --dry-run
"""

import os
import sys
import argparse
from pathlib import Path

import httpx
import firebase_admin
from firebase_admin import credentials, firestore

# ── Config ────────────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent

SA_PATH = os.environ.get(
    "FIREBASE_SERVICE_ACCOUNT",
    str(SCRIPT_DIR / "firebase_serviceaccount.json"),
)

LITELLM_PROXY_URL = os.environ.get("LITELLM_PROXY_URL", "http://localhost:8005")
LITELLM_MASTER_KEY = os.environ.get("LITELLM_MASTER_KEY", "")

# Fall back to reading from app config if env vars not set
if not LITELLM_MASTER_KEY:
    try:
        sys.path.insert(0, str(SCRIPT_DIR))
        from app.core.config import settings
        LITELLM_PROXY_URL = settings.LITELLM_PROXY_URL
        LITELLM_MASTER_KEY = settings.LITELLM_MASTER_KEY
        print(f"Loaded LiteLLM config from app settings: {LITELLM_PROXY_URL}")
    except Exception as e:
        print(f"WARNING: Could not load app settings ({e}). "
              "Set LITELLM_PROXY_URL and LITELLM_MASTER_KEY env vars.")
        sys.exit(1)


def delete_key_from_litellm(key: str, dry_run: bool) -> bool:
    """Delete a key from the LiteLLM proxy. Returns True on success."""
    if dry_run:
        print(f"  [DRY-RUN] Would DELETE key from LiteLLM: {key[:20]}...")
        return True
    try:
        resp = httpx.post(
            f"{LITELLM_PROXY_URL}/key/delete",
            headers={
                "Authorization": f"Bearer {LITELLM_MASTER_KEY}",
                "Content-Type": "application/json",
            },
            json={"keys": [key]},
            timeout=10.0,
        )
        if resp.status_code == 200:
            return True
        else:
            print(f"  WARNING: LiteLLM returned {resp.status_code} for key {key[:20]}...: {resp.text[:100]}")
            return False
    except Exception as e:
        print(f"  WARNING: Failed to delete key from LiteLLM ({e})")
        return False


def main():
    parser = argparse.ArgumentParser(description="Reset all user LiteLLM keys")
    parser.add_argument("--dry-run", action="store_true", help="Show what would happen without making changes")
    args = parser.parse_args()

    dry_run = args.dry_run
    if dry_run:
        print("=== DRY-RUN MODE — no changes will be made ===\n")

    # ── Init Firestore ────────────────────────────────────────────────────────
    if not os.path.exists(SA_PATH):
        print(f"ERROR: Service-account file not found at:\n  {SA_PATH}")
        sys.exit(1)

    cred = credentials.Certificate(SA_PATH)
    firebase_admin.initialize_app(cred)
    db = firestore.client()

    # ── Fetch all users with a litellm_key ────────────────────────────────────
    print("Fetching users_table from Firestore...")
    users_ref = db.collection("users_table").stream()

    users_with_key = []
    for doc in users_ref:
        data = doc.to_dict() or {}
        key = data.get("litellm_key")
        if key:
            users_with_key.append((doc.id, key))

    print(f"Found {len(users_with_key)} users with existing LiteLLM keys.\n")

    if not users_with_key:
        print("Nothing to do.")
        return

    # ── Delete keys ───────────────────────────────────────────────────────────
    litellm_ok = 0
    litellm_fail = 0
    firestore_ok = 0

    for uid, key in users_with_key:
        print(f"User {uid[:12]}...")

        # 1. Delete from LiteLLM proxy
        ok = delete_key_from_litellm(key, dry_run)
        if ok:
            litellm_ok += 1
        else:
            litellm_fail += 1

        # 2. Clear litellm_key field from Firestore
        if not dry_run:
            try:
                db.collection("users_table").document(uid).update({
                    "litellm_key": firestore.DELETE_FIELD
                })
                firestore_ok += 1
                print(f"  ✓ Firestore field cleared")
            except Exception as e:
                print(f"  WARNING: Firestore update failed for {uid}: {e}")
        else:
            print(f"  [DRY-RUN] Would clear litellm_key from Firestore for {uid[:12]}")
            firestore_ok += 1

    # ── Summary ───────────────────────────────────────────────────────────────
    print(f"\n{'='*50}")
    print(f"{'DRY-RUN SUMMARY' if dry_run else 'DONE'}")
    print(f"  LiteLLM keys deleted:     {litellm_ok}/{len(users_with_key)}")
    print(f"  LiteLLM delete failures:  {litellm_fail}")
    print(f"  Firestore fields cleared: {firestore_ok}/{len(users_with_key)}")
    if not dry_run:
        print(f"\nNext LLM request from each user will auto-create a new key")
        print(f"with access to ALL proxy models.")


if __name__ == "__main__":
    main()
