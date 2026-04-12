"""
core/auth.py — FastAPI dependency for Firebase ID-token authentication.

Usage (in any router):

    from app.core.auth import require_auth, AuthUser

    @router.post("/some-endpoint")
    async def my_endpoint(req: MyRequest, auth: AuthUser = Depends(require_auth)):
        # auth.uid  → Firebase UID of the authenticated user
        # auth.email → email address (may be empty for anonymous accounts)
        ...

The client must send:
    Authorization: Bearer <firebase-id-token>

The token is validated by the Firebase Admin SDK using Google's public keys.
Expired, revoked, or tampered tokens result in a 401 Unauthorized response.

Webhook endpoints that use their own HMAC signatures (e.g. Razorpay) should NOT
use this dependency — they have their own verification logic.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.firebase_auth import verify_firebase_token
import firebase_admin.auth as fb_auth

logger = logging.getLogger(__name__)

# Extracts the Bearer token from the Authorization header.
# auto_error=False lets us return a custom 401 message instead of FastAPI's generic one.
_bearer_scheme = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class AuthUser:
    """Represents an authenticated Firebase user attached to a request."""
    uid: str
    email: str = ""


async def require_auth(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> AuthUser:
    """
    FastAPI dependency.  Verifies the Firebase ID token in the Authorization header.

    Returns an AuthUser on success.
    Raises HTTP 401 on any auth failure (missing header, expired token, bad signature, etc.).
    """
    if credentials is None or not credentials.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authorization header missing. Include 'Authorization: Bearer <firebase-id-token>'.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    token = credentials.credentials
    try:
        decoded = verify_firebase_token(token)
    except fb_auth.ExpiredIdTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Firebase ID token has expired. Please sign in again.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    except fb_auth.RevokedIdTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Firebase ID token has been revoked.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    except (fb_auth.InvalidIdTokenError, ValueError) as exc:
        logger.warning("Invalid token rejected: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid Firebase ID token.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    except Exception as exc:
        # Unexpected error (network issue reaching Google JWKS, etc.)
        logger.error("Token verification error: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token verification failed. Please try again.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return AuthUser(
        uid=decoded["uid"],
        email=decoded.get("email", ""),
    )
