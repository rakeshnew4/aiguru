#!/usr/bin/env python3
"""
seed_referral_codes.py
----------------------
Pre-registers a referral code in Firestore for every existing user.

The code is generated with the EXACT same algorithm as ReferralManager.kt,
including Kotlin Long (64-bit signed) overflow behaviour via ctypes.c_int64.

Collection written:
  referralCodes/{code} → { ownerUserId: string, ownerName: string }

Safe to re-run — uses set/merge, skips users that already have a code doc.

    python seed_referral_codes.py
"""

import ctypes
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
    str(SCRIPT_DIR / "firebase_serviceaccount.json"),
)
if not os.path.exists(SA_PATH):
    print(f"ERROR: Service-account file not found:\n  {SA_PATH}")
    sys.exit(1)

cred = credentials.Certificate(SA_PATH)
firebase_admin.initialize_app(cred)
db = firestore.client()

# ── Code generation (mirrors ReferralManager.kt exactly) ─────────────────────
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # 32 unambiguous chars


def _to_long(n: int) -> int:
    """Replicate Kotlin Long (signed 64-bit) overflow."""
    return ctypes.c_int64(n).value


def generate_code(user_id: str) -> str:
    """Deterministic 8-char code for user_id — identical output to Kotlin."""
    h = _to_long(5381)
    for c in user_id:
        h = _to_long(h * 33 + ord(c))
    if h < 0:
        h = -h

    result = []
    for i in range(8):
        result.append(ALPHABET[h % len(ALPHABET)])
        h //= len(ALPHABET)
        h = _to_long(h * 31 + i)
        if h < 0:
            h = -h

    return "".join(result)


# ── Main ──────────────────────────────────────────────────────────────────────
def run():
    users_ref   = db.collection("users")
    codes_ref   = db.collection("referralCodes")

    user_docs = list(users_ref.stream())
    print(f"Found {len(user_docs)} user(s).\n")

    # Fetch all existing code docs once to avoid individual reads
    existing_codes = {snap.id for snap in codes_ref.stream()}
    print(f"Existing referral codes: {len(existing_codes)}\n")

    batch       = db.batch()
    batch_count = 0
    created     = 0
    skipped     = 0

    for doc in user_docs:
        uid   = doc.id
        data  = doc.to_dict() or {}
        name  = data.get("name") or data.get("displayName") or ""
        code  = generate_code(uid)

        if code in existing_codes:
            print(f"  –  users/{uid}  code={code}  (already exists, skip)")
            skipped += 1
            continue

        code_doc_ref = codes_ref.document(code)
        batch.set(code_doc_ref, {"ownerUserId": uid, "ownerName": name}, merge=True)
        batch_count += 1
        created += 1
        print(f"  +  users/{uid}  name='{name}'  code={code}")

        if batch_count >= 400:
            batch.commit()
            batch = db.batch()
            batch_count = 0

    if batch_count > 0:
        batch.commit()

    print(f"\n✅  Done — created {created} new code(s), skipped {skipped} existing.")


if __name__ == "__main__":
    run()
