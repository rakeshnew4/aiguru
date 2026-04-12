"""
api/users.py — User registration endpoint.

POST /users/register
  Idempotent: safe to call on every login.
  Creates users_table/{uid} with free-plan defaults if it doesn't exist yet.
  Also creates a LiteLLM API key for per-user usage tracking if LiteLLM is enabled.
"""

from __future__ import annotations

import asyncio
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.services import user_service, litellm_service
from app.core.logger import get_logger
from app.core.config import settings
from app.core.auth import require_auth, AuthUser

logger = get_logger(__name__)
router = APIRouter(prefix="/users", tags=["users"])


class RegisterRequest(BaseModel):
    userId: str
    name: Optional[str] = ""
    email: Optional[str] = ""
    grade: Optional[str] = ""
    schoolId: Optional[str] = ""
    schoolName: Optional[str] = ""


class RegisterResponse(BaseModel):
    success: bool
    message: str
    litellm_key: Optional[str] = None  # New user's LiteLLM API key


@router.post("/register", response_model=RegisterResponse)
async def register_user(req: RegisterRequest, auth: AuthUser = Depends(require_auth)):
    """
    Create users_table/{uid} on first sign-up + LiteLLM API key for usage tracking.

    Safe to call on every login — if the Firestore document already exists this is a no-op.
    The Android app should call this once after Firebase Auth sign-in completes.
    
    On success, returns the user's LiteLLM API key (if LiteLLM is enabled).
    Android app should store this key and pass it in chat requests for usage tracking.
    """
    if not req.userId or req.userId == "guest_user":
        raise HTTPException(status_code=400, detail="Valid userId is required")

    # The authenticated token UID must match the userId being registered.
    # This prevents one user from creating/overwriting another user's document.
    # Skip this check in development mode (AUTH_REQUIRED=False)
    if settings.AUTH_REQUIRED and auth.uid != req.userId:
        raise HTTPException(
            status_code=403,
            detail="Token UID does not match the userId in the request.",
        )

    # Create user in Firestore (sync via executor)
    loop = asyncio.get_event_loop()
    try:
        await loop.run_in_executor(
            None,
            user_service.create_user_if_missing,
            req.userId,
            req.name or "",
            req.email or "",
            req.grade or "",
            req.schoolId or "",
            req.schoolName or "",
        )
    except Exception as exc:
        logger.error("register_user: failed to create Firestore user uid=%s: %s", req.userId, exc)
        raise HTTPException(status_code=500, detail="Failed to register user in Firestore")

    # Create LiteLLM API key if enabled
    litellm_key = None
    if settings.USE_LITELLM_PROXY:
        try:
            litellm_key = await litellm_service.create_user_api_key(
                req.userId,
                {"name": req.name, "email": req.email, "grade": req.grade}
            )
            if litellm_key:
                logger.info("register_user: created LiteLLM key for uid=%s", req.userId)
            else:
                logger.warning("register_user: failed to create LiteLLM key for uid=%s", req.userId)
        except Exception as exc:
            logger.error("register_user: LiteLLM key creation failed for uid=%s: %s", req.userId, exc)
            # Continue gracefully — Firestore user still created

    # Log registration / login event (fire-and-forget)
    loop.run_in_executor(
        None,
        user_service.log_activity,
        "register",
        req.userId,
        req.name or "",
        req.email or "",
        {"grade": req.grade or "", "school": req.schoolName or ""},
    )

    return RegisterResponse(
        success=True,
        message="User registered",
        litellm_key=litellm_key
    )
