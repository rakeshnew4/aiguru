import asyncio
import base64

import httpx
from fastapi import APIRouter, HTTPException, Header
from fastapi.responses import Response
from pydantic import BaseModel
from typing import Optional

from app.core.config import settings
from app.core.logger import get_logger

logger = get_logger(__name__)
router = APIRouter(prefix="", tags=["tts"])

# ── Google Cloud Text-to-Speech ───────────────────────────────────────────────
# Dedicated TTS service — NOT a generative model.
# Latency: ~1-3 s per request (vs 15-25 s for Gemini generative TTS).
# Returns WAV (RIFF/LINEAR16) directly — no PCM conversion needed.
# Requires: Google Cloud project → APIs & Services → enable "Cloud Text-to-Speech API"
# API key: set GOOGLE_TTS_API_KEY in .env, or reuse GEMINI_API_KEY if same GCP project.

_GCP_TTS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
_SAMPLE_RATE  = 24000

# BCP-47 tag → Google Cloud TTS WaveNet voice
_LANG_VOICE: dict[str, str] = {
    "en-US": "en-US-Wavenet-F",
    "en-GB": "en-GB-Wavenet-C",
    "hi-IN": "hi-IN-Wavenet-A",
    "bn-IN": "bn-IN-Wavenet-A",
    "te-IN": "te-IN-Standard-A",
    "ta-IN": "ta-IN-Wavenet-A",
    "mr-IN": "mr-IN-Wavenet-A",
    "kn-IN": "kn-IN-Wavenet-A",
    "gu-IN": "gu-IN-Standard-A",
}
_DEFAULT_VOICE = "en-US-Wavenet-F"

_PREMIUM_PLANS = {"premium", "pro", "school", "basic_plus"}

# Cloud TTS is fast, so higher concurrency is safe
_batch_semaphore = asyncio.Semaphore(10)


def _tts_api_key() -> str:
    # Use dedicated TTS key if configured, otherwise fall back to GEMINI_API_KEY
    return settings.GOOGLE_TTS_API_KEY or settings.GEMINI_API_KEY


async def _cloud_tts_wav(text: str, language_tag: str, voice_name: str | None = None) -> bytes | None:
    """
    Calls Google Cloud Text-to-Speech.
    Returns WAV bytes (RIFF/LINEAR16, 24 kHz, mono) already including the WAV header —
    ready for Android play() without any conversion.
    Returns None on failure.
    """
    lang  = language_tag or "en-US"
    voice = voice_name or _LANG_VOICE.get(lang, _DEFAULT_VOICE)
    payload = {
        "input": {"text": text},
        "voice": {"languageCode": lang, "name": voice},
        "audioConfig": {"audioEncoding": "LINEAR16", "sampleRateHertz": _SAMPLE_RATE},
    }
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{_GCP_TTS_URL}?key={_tts_api_key()}",
                json=payload,
            )
            if resp.status_code != 200:
                logger.warning("Cloud TTS %d: %.300s", resp.status_code, resp.text[:300])
                return None
            b64 = resp.json().get("audioContent", "")
            return base64.b64decode(b64) if b64 else None
    except Exception as exc:
        logger.error("Cloud TTS error: %s", exc)
        return None


class TtsRequest(BaseModel):
    text: str
    user_plan: Optional[str] = "free"
    voice_id: Optional[str] = None        # None → auto-select from _LANG_VOICE
    language_tag: Optional[str] = "en-US"
    model_id: Optional[str] = None        # unused — kept for API compat


class TtsResponse(BaseModel):
    # Base64-encoded WAV (RIFF/LINEAR16, 24 kHz, mono)
    audio_base64: str


# ── /tts ─────────────────────────────────────────────────────────────────────

@router.post("/tts", response_model=TtsResponse)
async def synthesize_speech(
    req: TtsRequest,
    authorization: Optional[str] = Header(None),
) -> TtsResponse:
    plan = (req.user_plan or "free").lower().strip()
    if plan not in _PREMIUM_PLANS:
        raise HTTPException(status_code=403, detail="AI voice requires a premium plan.")

    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text must not be empty.")
    if len(text) > 5000:
        text = text[:5000]

    wav = await _cloud_tts_wav(text, req.language_tag or "en-US", req.voice_id)
    if not wav:
        raise HTTPException(status_code=502, detail="TTS provider returned no audio.")

    logger.info("TTS ok — %d chars, plan=%s lang=%s", len(text), plan, req.language_tag)
    return TtsResponse(audio_base64=base64.b64encode(wav).decode())


# ── /tts/stream ───────────────────────────────────────────────────────────────

@router.post("/tts/stream")
async def synthesize_speech_stream(
    req: TtsRequest,
    authorization: Optional[str] = Header(None),
):
    """Cloud TTS is fast (1-3 s) so streaming is unnecessary — returns WAV bytes directly."""
    plan = (req.user_plan or "free").lower().strip()
    if plan not in _PREMIUM_PLANS:
        raise HTTPException(status_code=403, detail="AI voice requires a premium plan.")

    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text must not be empty.")
    if len(text) > 5000:
        text = text[:5000]

    wav = await _cloud_tts_wav(text, req.language_tag or "en-US", req.voice_id)
    if not wav:
        raise HTTPException(status_code=502, detail="TTS provider returned no audio.")

    return Response(content=wav, media_type="audio/wav")


# ── /tts/batch ────────────────────────────────────────────────────────────────

class TtsBatchItem(BaseModel):
    text: str
    language_tag: Optional[str] = "en-US"
    voice_id: Optional[str] = None


class TtsBatchRequest(BaseModel):
    items: list[TtsBatchItem]
    user_plan: Optional[str] = "free"
    model_id: Optional[str] = None


class TtsBatchResponse(BaseModel):
    # Base64-encoded WAV per item. Empty string = that item failed.
    audios: list[str]


@router.post("/tts/batch", response_model=TtsBatchResponse)
async def synthesize_speech_batch(
    req: TtsBatchRequest,
    authorization: Optional[str] = Header(None),
) -> TtsBatchResponse:
    """
    Fetches WAV audio for every lesson frame in one round-trip.
    asyncio.gather with semaphore(10) runs up to 10 Cloud TTS calls in parallel.
    Total time ≈ slowest single frame (~1-3 s) rather than sum of all frames.
    """
    plan = (req.user_plan or "free").lower().strip()
    if plan not in _PREMIUM_PLANS:
        raise HTTPException(status_code=403, detail="AI voice requires a premium plan.")

    async def fetch_one(item: TtsBatchItem) -> str:
        async with _batch_semaphore:
            text = (item.text or "").strip()
            if not text:
                return ""
            if len(text) > 5000:
                text = text[:5000]
            wav = await _cloud_tts_wav(text, item.language_tag or "en-US", item.voice_id)
            return base64.b64encode(wav).decode() if wav else ""

    audios = await asyncio.gather(*[fetch_one(item) for item in req.items])
    logger.info("TTS batch ok — %d items, plan=%s", len(req.items), plan)
    return TtsBatchResponse(audios=list(audios))
