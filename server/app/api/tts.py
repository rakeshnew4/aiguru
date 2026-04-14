"""
api/tts.py — Server-side Text-to-Speech endpoint.

The Android app calls POST /api/tts/synthesize with the text + language.
This server holds the API keys; the app never sees them.

Supported backends (selected by tts_engine request field):
  gemini  → Gemini 2.5 Flash TTS  (premium, natural voice — concept/memory frames)
  google  → Google Cloud TTS       (neural quality, cost-efficient — summary frames)
  android → 204 No Content         (client uses its own TTS — quiz/instant frames)

  When tts_engine is not supplied the legacy fallback chain is used:
    Google Cloud TTS → ElevenLabs → OpenAI TTS → 503

Response: raw MP3 bytes (Content-Type: audio/mpeg)
          or 204 No Content when tts_engine == "android"
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
    # Hybrid voice engine fields (set per-frame by the LLM):
    #   android → client uses its own TTS (server returns 204)
    #   gemini  → Gemini 2.5 Flash TTS (premium, natural voice)
    #   google  → Google Cloud TTS (neural, cost-efficient)
    #   ""      → legacy fallback chain (google → elevenlabs → openai)
    tts_engine: str = ""            # android | gemini | google | "" (legacy)
    voice_role: str = "teacher"     # teacher | assistant | quiz | feedback (informational)


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
            ssml_gender=texttospeech.SsmlVoiceGender.NEUTRAL
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


async def _gemini_tts(text: str, language_code: str) -> Optional[bytes]:
    """
    Gemini 2.5 Flash TTS — premium natural voice.

    Uses the Gemini API with responseModalities=AUDIO.
    Returns raw MP3 bytes or None on failure.
    Voice is selected based on language_code for best Indian-language quality.
    """
    api_key = settings.GEMINI_API_KEY
    if not api_key:
        logger.warning("GEMINI_API_KEY not set — cannot use Gemini TTS")
        return None

    # Pick a voice that suits the language / role (Gemini voices are language-agnostic
    # but some sound better for certain scripts)
    voice_name = "Aoede"   # warm, natural — good for Indian-language speech

    url = (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        f"gemini-2.5-flash-preview-tts:generateContent?key={api_key}"
    )
    payload = {
        "contents": [{"parts": [{"text": text}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
                "voiceConfig": {
                    "prebuiltVoiceConfig": {"voiceName": voice_name}
                }
            },
        },
    }
    try:
        async with httpx.AsyncClient(timeout=25.0) as client:
            resp = await client.post(url, json=payload)
        if resp.status_code == 200:
            data = resp.json()
            b64 = (
                data["candidates"][0]["content"]["parts"][0]
                ["inlineData"]["data"]
            )
            import base64 as _b64
            raw_pcm = _b64.b64decode(b64)
            # Gemini returns raw 24 kHz 16-bit PCM — wrap in a minimal WAV so
            # MediaPlayer can play it without re-encoding.
            mp3_bytes = _pcm_to_wav(raw_pcm, sample_rate=24000)
            logger.info(f"Gemini TTS OK: lang={language_code} chars={len(text)} wav_bytes={len(mp3_bytes)}")
            return mp3_bytes
        logger.warning(f"Gemini TTS HTTP {resp.status_code}: {resp.text[:300]}")
    except Exception as e:
        logger.error(f"Gemini TTS error: {e}")
    return None


def _pcm_to_wav(pcm_data: bytes, sample_rate: int = 24000, num_channels: int = 1, bits_per_sample: int = 16) -> bytes:
    """Wrap raw PCM bytes in a WAV container (no external library required)."""
    import struct
    data_size     = len(pcm_data)
    byte_rate     = sample_rate * num_channels * bits_per_sample // 8
    block_align   = num_channels * bits_per_sample // 8
    header = struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF",
        36 + data_size,
        b"WAVE",
        b"fmt ",
        16,                # PCM chunk size
        1,                 # PCM format
        num_channels,
        sample_rate,
        byte_rate,
        block_align,
        bits_per_sample,
        b"data",
        data_size,
    )
    return header + pcm_data


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.post("/synthesize")
async def synthesize(
    req: TtsSynthesizeRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Synthesize speech from text.

    Engine routing (per tts_engine field):
      android → 204 No Content  (client uses its own on-device TTS — zero network cost)
      gemini  → Gemini 2.5 Flash TTS, fallback to Google Cloud TTS
      google  → Google Cloud TTS, fallback to ElevenLabs → OpenAI TTS
      ""  (legacy) → Google → ElevenLabs → OpenAI

    Returns raw WAV/MP3 bytes (audio/mpeg) or 204 for android engine.
    Returns 503 if all providers fail (Android app falls back to built-in TTS).
    """
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text cannot be empty")
    if len(text) > 5000:
        raise HTTPException(status_code=400, detail="text too long (max 5000 chars)")

    engine = req.tts_engine.lower().strip()

    # Android engine: client handles it — no audio generation needed
    if engine == "android":
        return Response(status_code=204)

    audio: Optional[bytes] = None

    if engine == "gemini":
        audio = await _gemini_tts(text, req.language_code)
        if audio is None:
            # Graceful fallback: Gemini down → Google Cloud TTS
            logger.warning(f"Gemini TTS failed uid={auth.uid}, falling back to Google TTS")
            audio = await _google_tts(text, req.language_code, req.voice_name, req.speaking_rate)

    elif engine == "google":
        audio = await _google_tts(text, req.language_code, req.voice_name, req.speaking_rate)
        if audio is None:
            audio = await _elevenlabs_tts(text)
        if audio is None:
            audio = await _openai_tts(text)

    else:
        # Legacy / unrecognised engine: original priority chain
        audio = await _google_tts(text, req.language_code, req.voice_name, req.speaking_rate)
        if audio is None:
            audio = await _elevenlabs_tts(text)
        if audio is None:
            audio = await _openai_tts(text)

    if audio is None:
        logger.error(
            f"All TTS providers failed uid={auth.uid} engine={engine} lang={req.language_code}"
        )
        raise HTTPException(status_code=503, detail="TTS unavailable")

    return Response(
        content=audio,
        media_type="audio/mpeg",
        headers={
            "X-TTS-Bytes":  str(len(audio)),
            "X-TTS-Engine": engine or "legacy",
            "X-Voice-Role": req.voice_role,
        },
    )


@router.get("/health")
async def tts_health():
    """Returns which TTS providers are configured on this server."""
    return {
        "gemini":      bool(settings.GEMINI_API_KEY),
        "google":      bool(os.getenv("GOOGLE_APPLICATION_CREDENTIALS")),
        "elevenlabs":  bool(settings.ELEVENLABS_API_KEY),
        "openai":      bool(settings.OPENAI_TTS_API_KEY),
    }

