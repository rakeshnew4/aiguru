"""
core/firebase_auth.py — Singleton Firebase Admin initialization + ID-token verification.

All server modules that need Firebase (auth or Firestore) should import from here
instead of calling firebase_admin.initialize_app() themselves, to guarantee that
the SDK is only initialised once even when multiple workers share the same process.
"""

from __future__ import annotations

import logging
import os
from typing import Any, Dict

import firebase_admin
from firebase_admin import auth as fb_auth
from firebase_admin import credentials, firestore

from app.core.config import settings

logger = logging.getLogger(__name__)

# ── Singleton initialisation ──────────────────────────────────────────────────

_initialized = False
_db: Any = None  # google.cloud.firestore.Client | None


def _init() -> None:
    """Initialise Firebase Admin SDK once; safe to call multiple times."""
    global _initialized, _db
    if _initialized:
        return

    sa_path = (
        settings.FIREBASE_SERVICE_ACCOUNT
        or os.path.join(os.path.dirname(__file__), "..", "firebase_serviceaccount.json")
    )

    try:
        if not firebase_admin._apps:
            cred = credentials.Certificate(sa_path)
            firebase_admin.initialize_app(cred)
        _db = firestore.client()
        _initialized = True
        logger.info("Firebase Admin SDK initialized (service-account: %s)", sa_path)
    except Exception as exc:
        logger.error("Firebase Admin SDK initialization FAILED: %s", exc)
        # Keep _initialized=False so every request fails fast with a clear error.


_init()


# ── Public helpers ────────────────────────────────────────────────────────────

def get_firestore_db():
    """
    Return the Firestore client.  Raises RuntimeError if Firebase is not configured.
    """
    if _db is None:
        raise RuntimeError("Firestore not available — check FIREBASE_SERVICE_ACCOUNT setting.")
    return _db


def verify_firebase_token(token: str) -> Dict[str, Any]:
    """
    Verify a Firebase ID token and return the decoded claims dict.

    The claims dict always contains at least:
      • "uid"   — Firebase Auth UID (string)
      • "email" — verified email, if the provider supplies one (may be absent)
      • "iss"   — issuer
      • "exp"   — expiry timestamp

    Raises:
        ValueError  — token is empty or obviously malformed
        firebase_admin.auth.ExpiredIdTokenError — token has expired
        firebase_admin.auth.RevokedIdTokenError — token has been revoked
        firebase_admin.auth.InvalidIdTokenError — signature / format invalid
    """
    if not token or not token.strip():
        raise ValueError("Authorization token is empty.")

    # check_revoked=True makes an extra network call to Firebase to confirm the
    # token has not been revoked (e.g. after a forced sign-out / password reset).
    # This is the most secure mode; the minor latency is acceptable for API calls.
    decoded = fb_auth.verify_id_token(token.strip(), check_revoked=True)
    return decoded
