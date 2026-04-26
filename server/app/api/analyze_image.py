"""
Image Analysis API – server-side vision analysis replacing the Android Groq call.

POST /analyze-image
  Request : { image_base64, subject, chapter, page_number?, source_type? }
  Response: { transcript, paragraphs_json, diagrams_json, key_terms }

Caches results in Redis (keyed by MD5 of the first 512 chars of image_base64)
so reopening the same PDF page or re-uploading the same photo hits cache.
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.core.logger import get_logger
from app.core.auth import require_auth, AuthUser
from app.services.llm_service import generate_response
from app.services import cache_service

logger = get_logger(__name__)
router = APIRouter(prefix="", tags=["analyze"])


# ── Request / Response ────────────────────────────────────────────────────────

class AnalyzeImageRequest(BaseModel):
    image_base64: str
    subject: str = "General"
    chapter: str = "Study Session"
    page_number: int = 0
    source_type: str = "image"          # image | camera | pdf


class AnalyzeImageResponse(BaseModel):
    transcript: str = ""
    paragraphs_json: str = "[]"
    diagrams_json: str = "[]"
    key_terms: List[str] = Field(default_factory=list)
    cached: bool = False                # True when result came from Redis


# ── Prompt ────────────────────────────────────────────────────────────────────

_SYSTEM = (
    "You are an expert educational content extractor. "
    "Analyze this image of a textbook or classroom material. "
    "Return ONLY a valid JSON object — no markdown fences, no explanation."
)

_USER = """\
Analyze this educational page image in detail. Return a JSON object with EXACTLY this structure:

{
  "transcript": "Full verbatim text extracted from the page — every word, equation, caption",
  "paragraphs": [
    {
      "number": 1,
      "text": "Exact text of this paragraph",
      "summary": "One-sentence summary of what this paragraph explains"
    }
  ],
  "diagrams": [
    {
      "heading": "Figure label/title visible in the image, e.g. 'Figure 2.1: Mitosis'",
      "context": "Which topic or concept this diagram relates to",
      "description": "All visible elements — labels, arrows, components, colours, numbers",
      "depiction": "What the diagram is illustrating, proving, or teaching the student",
      "position": "Where on the page — e.g. top-right, center, bottom-left",
      "labelled_parts": ["Label A", "Label B"]
    }
  ],
  "key_terms": ["term1", "term2", "term3"]
}

Rules:
- Include ALL text, ALL diagrams/figures/tables visible in the image.
- If there are no diagrams, set "diagrams" to [].
- Return ONLY the JSON — no extra text before or after."""


# ── Helpers ───────────────────────────────────────────────────────────────────

def _cache_key(image_base64: str) -> str:
    """Collision-resistant SHA-256 key: length + first 512 chars + last 256 chars."""
    head = image_base64[:512]
    tail = image_base64[-256:] if len(image_base64) > 512 else ""
    fingerprint = f"{len(image_base64)}:{head}:{tail}".encode()
    return hashlib.sha256(fingerprint).hexdigest()


def _extract_json(text: str) -> Dict[str, Any]:
    text = text.strip()
    # Strip markdown fences if present
    text = re.sub(r"```(?:json)?", "", text).rstrip("`").strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # Try to find any JSON-like structure
        match = re.search(r"\{.*\}", text, re.DOTALL)
        if match:
            return json.loads(match.group(0))
        raise ValueError(f"No valid JSON in LLM output: {text[:200]}")


def _to_response(data: Dict[str, Any], cached: bool = False) -> AnalyzeImageResponse:
    return AnalyzeImageResponse(
        transcript=data.get("transcript", ""),
        paragraphs_json=json.dumps(data.get("paragraphs", []), ensure_ascii=False),
        diagrams_json=json.dumps(data.get("diagrams", []), ensure_ascii=False),
        key_terms=data.get("key_terms", []),
        cached=cached,
    )


# ── Endpoint ──────────────────────────────────────────────────────────────────

@router.post("/analyze-image", response_model=AnalyzeImageResponse)
async def analyze_image(req: AnalyzeImageRequest, auth: AuthUser = Depends(require_auth)) -> AnalyzeImageResponse:
    """
    Analyse an educational image with the vision LLM and return structured
    content (transcript, paragraphs, diagrams, key terms).

    Camera images are NOT cached (real-time capture, likely unique).
    Gallery / PDF images ARE cached for 7 days.
    """
    use_cache = req.source_type != "camera"
    cache_key = _cache_key(req.image_base64) if use_cache else None

    # ── Cache read ────────────────────────────────────────────────────────────
    if cache_key:
        cached = cache_service.get_cache(page_id="img_analysis", question=cache_key)
        if cached:
            logger.info("analyze-image cache HIT (key=%s)", cache_key)
            return _to_response(cached, cached=True)

    # ── Build prompt with subject / chapter context ───────────────────────────
    system_prompt = f"{_SYSTEM}\nSubject: {req.subject}. Chapter: {req.chapter}."
    full_prompt = system_prompt + "\n\n" + _USER

    # Wrap base64 as a data-URI for the LLM service
    image_uri = (
        req.image_base64
        if req.image_base64.startswith("data:")
        else f"data:image/jpeg;base64,{req.image_base64}"
    )

    # ── LLM call (Gemini vision via "power" tier, fallback to "cheaper") ──────
    last_exc: Exception | None = None
    for tier in ("power", "cheaper"):
        try:
            result = generate_response(full_prompt, [image_uri], tier=tier, call_name="image_analyze")
            raw_text: str = result.get("text", "")
            if not raw_text.strip():
                raise ValueError("LLM returned empty response")

            data = _extract_json(raw_text)

            # ── Cache write ───────────────────────────────────────────────────
            if cache_key:
                try:
                    cache_service.set_cache(
                        page_id="img_analysis",
                        question=cache_key,
                        value=data,
                    )
                except Exception as ce:
                    logger.warning("Cache write failed (non-fatal): %s", ce)

            logger.info(
                "analyze-image OK | tier=%s | subject=%s | chapter=%s | transcript=%d chars",
                tier, req.subject, req.chapter, len(data.get("transcript", "")),
            )
            return _to_response(data)

        except Exception as exc:
            logger.warning("analyze-image tier=%s failed: %s", tier, exc)
            last_exc = exc

    logger.error("analyze-image all tiers failed: %s", last_exc)
    raise HTTPException(status_code=502, detail=f"Image analysis failed: {last_exc}")
