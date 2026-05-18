"""
Security API

POST /security/verify_integrity  — verifies a Play Integrity token sent by the app.

Flow:
  1. App generates a random nonce and requests an integrity token from Google Play.
  2. App POSTs the token + nonce here.
  3. Server decodes it via Google Play Integrity API and logs the verdict.
  4. Currently non-blocking for the user — verdict is logged and stored for audit.
     To enforce blocking, raise HTTP 403 when verdict fails.

Required env var / Firestore admin_config:
  PLAY_INTEGRITY_API_KEY  — Google Cloud API key with Play Integrity API enabled.
  Set it in server environment or add play_integrity_api_key to admin_config/global.
"""

from __future__ import annotations

import os
import logging
from typing import Optional

import httpx
from fastapi import APIRouter, Request
from pydantic import BaseModel

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/security", tags=["security"])

PACKAGE_NAME = "com.aiguruapp.student"
INTEGRITY_DECODE_URL = (
    "https://playintegrity.googleapis.com/v1/{package}:decodeIntegrityToken"
)


class IntegrityRequest(BaseModel):
    token: str
    nonce: str


class IntegrityVerdict(BaseModel):
    passed: bool
    meets_basic_integrity: bool
    meets_device_integrity: bool
    meets_strong_integrity: bool
    verdict_raw: Optional[dict] = None


@router.post("/verify_integrity", response_model=IntegrityVerdict)
async def verify_integrity(body: IntegrityRequest, request: Request):
    api_key = os.getenv("PLAY_INTEGRITY_API_KEY", "")
    if not api_key:
        # Key not configured — pass through silently (don't block users)
        logger.warning("PLAY_INTEGRITY_API_KEY not set; skipping server-side check")
        return IntegrityVerdict(
            passed=True,
            meets_basic_integrity=True,
            meets_device_integrity=True,
            meets_strong_integrity=False,
        )

    url = INTEGRITY_DECODE_URL.format(package=PACKAGE_NAME)
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                url,
                params={"key": api_key},
                json={"integrity_token": body.token},
            )
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:
        logger.error("Play Integrity API call failed: %s", e)
        # Don't block the user on a network error
        return IntegrityVerdict(
            passed=True,
            meets_basic_integrity=False,
            meets_device_integrity=False,
            meets_strong_integrity=False,
        )

    payload = data.get("tokenPayloadExternal", {})
    verdicts: list[str] = (
        payload.get("deviceIntegrity", {}).get("deviceRecognitionVerdict", [])
    )

    meets_basic  = "MEETS_BASIC_INTEGRITY"  in verdicts
    meets_device = "MEETS_DEVICE_INTEGRITY" in verdicts
    meets_strong = "MEETS_STRONG_INTEGRITY" in verdicts
    passed       = meets_basic  # minimum acceptable bar

    ip = request.client.host if request.client else "unknown"
    logger.info(
        "Integrity check | ip=%s basic=%s device=%s strong=%s verdicts=%s",
        ip, meets_basic, meets_device, meets_strong, verdicts,
    )

    if not passed:
        logger.warning(
            "Integrity FAILED | ip=%s token_nonce=%s verdicts=%s",
            ip, body.nonce[:8], verdicts,
        )

    return IntegrityVerdict(
        passed=passed,
        meets_basic_integrity=meets_basic,
        meets_device_integrity=meets_device,
        meets_strong_integrity=meets_strong,
        verdict_raw=payload,
    )
