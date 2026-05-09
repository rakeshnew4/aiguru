#!/usr/bin/env python3
"""
seed_app_config.py
------------------
Seeds the Firestore app_config collection with server-controlled settings.

Documents written:
  app_config/referral_settings  →  { bonus_per_day: int, bonus_days: int }

  bonus_per_day  — extra BB sessions/day granted to both the referrer and claimant
  bonus_days     — how long the bonus stays active (from claim date)

Safe to re-run — uses set/merge, so manual tweaks in the console are preserved
if you only change one field.

Usage:
    python seed_app_config.py
    python seed_app_config.py --bonus-per-day 5 --bonus-days 60
"""

import argparse
import os
import sys
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, firestore

# ── Credentials ───────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
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

# ── Args ──────────────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="Seed app_config in Firestore")
parser.add_argument("--bonus-per-day", type=int, default=3,
                    help="Extra BB sessions/day for both referrer and claimant (default: 3)")
parser.add_argument("--bonus-days",    type=int, default=30,
                    help="Duration of the bonus in days (default: 30)")
args = parser.parse_args()

# ── Referral settings ─────────────────────────────────────────────────────────
referral_cfg = {
    "bonus_per_day": args.bonus_per_day,
    "bonus_days":    args.bonus_days,
}

print(f"\n{'='*52}")
print("  Seeding app_config/referral_settings")
print(f"{'='*52}")
print(f"  bonus_per_day : {referral_cfg['bonus_per_day']} extra BB sessions/day")
print(f"  bonus_days    : {referral_cfg['bonus_days']} days from claim date")
print()

ref = db.collection("app_config").document("referral_settings")
ref.set(referral_cfg, merge=True)
print("✅  app_config/referral_settings written successfully")

# ── Print current value ───────────────────────────────────────────────────────
snap = ref.get()
print(f"\nCurrent Firestore value:\n  {snap.to_dict()}")
print()
print("To change the bonus later (no deploy needed):")
print("  python seed_app_config.py --bonus-per-day 5 --bonus-days 60")
print()
