"""
api/tts.py — Server-side Text-to-Speech endpoint.

The Android app calls POST /api/tts/synthesize with the text + language.
This server holds the API keys; the app never sees them.

Supported backends (tried in order, first success wins):
  1. Google Cloud TTS  — best multilingual quality (Indian languages supported)
  2. ElevenLabs        — best English/Hindi quality (if ELEVENLABS_API_KEY set)
  3. OpenAI TTS        — fast, good English (if OPENAI_TTS_API_KEY set)
  4. 503               — app falls back to Android TTS

Response: raw MP3 bytes (Content-Type: audio/mpeg)
"""

import base64
import os
from typing import Optional

import httpx
from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel
from google.cloud import texttospeech

from app.core.auth import require_auth, AuthUser
from app.core.config import settings
from app.core.logger import get_logger

logger = get_logger(__name__)
router = APIRouter(prefix="/api/tts", tags=["tts"])


# ── Request model ─────────────────────────────────────────────────────────────

class TtsSynthesizeRequest(BaseModel):
    text: str
    language_code: str = "en-US"    # BCP-47 e.g. "hi-IN", "ta-IN", "en-US"
    voice_name: str = ""            # Optional: specific Google voice name
    speaking_rate: float = 1.0      # 0.25 – 4.0


# ── Voice selection ───────────────────────────────────────────────────────────

_GOOGLE_VOICE_MAP = {
    "hi":       "hi-IN-Neural2-A",
    "en-in":    "en-IN-Neural2-A",
    "en":       "en-US-Neural2-F",
    "ta":       "ta-IN-Neural2-A",
    "te":       "te-IN-Standard-A",
    "kn":       "kn-IN-Wavenet-A",
    "ml":       "ml-IN-Wavenet-A",
    "mr":       "mr-IN-Wavenet-A",
    "bn":       "bn-IN-Wavenet-A",
    "gu":       "gu-IN-Wavenet-A",
    "pa":       "pa-Guru-IN-Wavenet-A",
}

def _google_voice(language_code: str, voice_name: str) -> dict:
    if voice_name:
        return {"languageCode": language_code, "name": voice_name, "ssmlGender": "NEUTRAL"}
    lang_lc = language_code.lower()
    name = (_GOOGLE_VOICE_MAP.get(lang_lc) or
            _GOOGLE_VOICE_MAP.get(lang_lc.split("-")[0]) or "")
    cfg: dict = {"languageCode": language_code, "ssmlGender": "NEUTRAL"}
    if name:
        cfg["name"] = name
    return cfg


# ── Provider functions ────────────────────────────────────────────────────────

async def _google_tts(text: str, language_code: str, voice_name: str,
                      speaking_rate: float) -> Optional[bytes]:
    """
    Google Cloud TTS using OAuth2 Service Account authentication.
    Expects GOOGLE_APPLICATION_CREDENTIALS env var pointing to service account JSON.
    """
    if not os.getenv("GOOGLE_APPLICATION_CREDENTIALS"):
        logger.warning("GOOGLE_APPLICATION_CREDENTIALS not set (service account JSON path)")
        return None
    
    try:
        client = texttospeech.TextToSpeechClient()
        
        voice_cfg = _google_voice(language_code, voice_name)
        voice = texttospeech.VoiceSelectionParams(
            language_code=voice_cfg["languageCode"],
            name=voice_cfg.get("name", ""),
            # ssml_gender=texttospeech.SsmlVoiceGender.NEUTRAL
        )
        
        audio_config = texttospeech.AudioConfig(
            audio_encoding=texttospeech.AudioEncoding.MP3,
            speaking_rate=speaking_rate
        )
        
        synthesis_input = texttospeech.SynthesisInput(text=text)
        
        response = client.synthesize_speech(
            input=synthesis_input,
            voice=voice,
            audio_config=audio_config
        )
        
        logger.info(f"Google Cloud TTS OK: lang={language_code} chars={len(text)} bytes={len(response.audio_content)}")
        return response.audio_content
        
    except Exception as e:
        logger.error(f"Google Cloud TTS error: {e}")
        return None


async def _elevenlabs_tts(text: str) -> Optional[bytes]:
    api_key = settings.ELEVENLABS_API_KEY
    if not api_key:
        return None
    voice_id = "21m00Tcm4TlvDq8ikWAM"   # Rachel — multilingual
    url = f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}"
    payload = {
        "text": text,
        "model_id": "eleven_multilingual_v2",
        "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
    }
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            resp = await client.post(url, json=payload, headers={
                "xi-api-key": api_key, "Accept": "audio/mpeg",
            })
        if resp.status_code == 200:
            logger.info(f"ElevenLabs TTS OK: chars={len(text)}")
            return resp.content
        logger.warning(f"ElevenLabs TTS HTTP {resp.status_code}: {resp.text[:200]}")
    except Exception as e:
        logger.error(f"ElevenLabs TTS error: {e}")
    return None


async def _openai_tts(text: str) -> Optional[bytes]:
    api_key = settings.OPENAI_TTS_API_KEY
    if not api_key:
        return None
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(
                "https://api.openai.com/v1/audio/speech",
                json={"model": "tts-1", "input": text, "voice": "nova", "response_format": "mp3"},
                headers={"Authorization": f"Bearer {api_key}"},
            )
        if resp.status_code == 200:
            logger.info(f"OpenAI TTS OK: chars={len(text)}")
            return resp.content
        logger.warning(f"OpenAI TTS HTTP {resp.status_code}: {resp.text[:200]}")
    except Exception as e:
        logger.error(f"OpenAI TTS error: {e}")
    return None


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.post("/synthesize")
async def synthesize(
    req: TtsSynthesizeRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Synthesize speech from text. Returns raw MP3 bytes (audio/mpeg).
    Tries: Google TTS → ElevenLabs → OpenAI TTS.
    Returns 503 if all providers fail (Android app falls back to built-in TTS).
    """
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text cannot be empty")
    if len(text) > 5000:
        raise HTTPException(status_code=400, detail="text too long (max 5000 chars)")

    mp3 = await _google_tts(text, req.language_code, req.voice_name, req.speaking_rate)
    if mp3 is None:
        mp3 = await _elevenlabs_tts(text)
    if mp3 is None:
        mp3 = await _openai_tts(text)

    if mp3 is None:
        logger.error(f"All TTS providers failed uid={auth.uid} lang={req.language_code}")
        raise HTTPException(status_code=503, detail="TTS unavailable")

    return Response(content=mp3, media_type="audio/mpeg",
                    headers={"X-TTS-Bytes": str(len(mp3))})


@router.get("/health")
async def tts_health():
    """Returns which TTS providers are configured on this server."""
    return {
        "google":      bool(os.getenv("GOOGLE_APPLICATION_CREDENTIALS")),
        "elevenlabs":  bool(settings.ELEVENLABS_API_KEY),
        "openai":      bool(settings.OPENAI_TTS_API_KEY),
    }

