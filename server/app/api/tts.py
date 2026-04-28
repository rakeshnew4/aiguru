import base64
import os
import re
from typing import Optional

import httpx
from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel
from google.cloud import texttospeech

from app.core.auth import require_auth, AuthUser
from app.core.config import settings
from app.core.logger import get_logger
from app.services import user_service

logger = get_logger(__name__)
router = APIRouter(prefix="/api/tts", tags=["tts"])


# ================================
# 🧠 Request model
# ================================

class TtsSynthesizeRequest(BaseModel):
    text: str
    language_code: str = "en-US"
    voice_name: str = ""
    speaking_rate: float = 1.0
    user_plan: str = "free"
    tts_engine: str = "google"   # google | gemini | android


# ================================
# 🎯 Voice maps
# ================================

CHEAP_VOICES = {
    "hi": "hi-IN-Standard-A",
    "en": "en-IN-Standard-A",
    "en-in": "en-IN-Neural2-A",
    "ta": "ta-IN-Standard-A",
    "te": "te-IN-Standard-A",
    "kn": "kn-IN-Standard-A",
    "ml": "ml-IN-Standard-A",
    "mr": "mr-IN-Standard-A",
    "bn": "bn-IN-Standard-A",
    "gu": "gu-IN-Standard-A",
    "pa": "pa-Guru-IN-Standard-A",
}

PREMIUM_VOICES = {
    "hi": "hi-IN-Neural2-A",
    "en": "en-US-Neural2-F",
    "en-in": "en-IN-Neural2-A",
    "ta": "ta-IN-Neural2-A",

    # fallback (no Neural2)
    "te": "te-IN-Wavenet-A",
    "kn": "kn-IN-Wavenet-A",
    "ml": "ml-IN-Wavenet-A",
    "mr": "mr-IN-Wavenet-A",
    "bn": "bn-IN-Wavenet-A",
    "gu": "gu-IN-Wavenet-A",
    "pa": "pa-Guru-IN-Wavenet-A",
}


def _select_voice(language_code: str, voice_name: str, user_plan: str):
    if voice_name:
        return {
            "languageCode": language_code,
            "name": voice_name,
            "ssmlGender": "NEUTRAL",
        }

    lang = language_code.lower()
    lang_key = lang.split("-")[0]

    voice_map = PREMIUM_VOICES if user_plan == "premium" else CHEAP_VOICES

    name = (
        voice_map.get(lang)
        or voice_map.get(lang_key)
        or voice_map.get("en")
    )

    return {
        "languageCode": language_code,
        "name": name,
        "ssmlGender": "NEUTRAL",
    }


def _select_speaking_rate(user_plan: str, text: str):
    # 🔥 Smart tuning
    return 1.0


# ================================
# 🔊 Google TTS
# ================================

async def _google_tts(text: str, language_code: str, voice_name: str,
                      speaking_rate: float, user_plan: str) -> Optional[bytes]:

    if not os.getenv("GOOGLE_APPLICATION_CREDENTIALS"):
        return None

    try:
        client = texttospeech.TextToSpeechClient()

        voice_cfg = _select_voice(language_code, voice_name, user_plan)
        print(voice_cfg, user_plan)
        voice = texttospeech.VoiceSelectionParams(
            language_code=voice_cfg["languageCode"],
            name=voice_cfg.get("name"),
        )

        audio_config = texttospeech.AudioConfig(
            audio_encoding=texttospeech.AudioEncoding.MP3,
            speaking_rate=speaking_rate,
        )

        synthesis_input = texttospeech.SynthesisInput(text=text)

        response = client.synthesize_speech(
            input=synthesis_input,
            voice=voice,
            audio_config=audio_config
        )

        return response.audio_content

    except Exception as e:
        logger.error(f"Google TTS error: {e}")
        return None


# ================================
# 🔊 ElevenLabs
# ================================

async def _elevenlabs_tts(text: str) -> Optional[bytes]:
    api_key = settings.ELEVENLABS_API_KEY
    if not api_key:
        return None

    url = "https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM"

    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            resp = await client.post(
                url,
                json={
                    "text": text,
                    "model_id": "eleven_multilingual_v2",
                },
                headers={"xi-api-key": api_key}
            )
        if resp.status_code == 200:
            return resp.content
    except Exception:
        pass

    return None


# ================================
# 🔊 OpenAI TTS
# ================================

async def _openai_tts(text: str) -> Optional[bytes]:
    api_key = settings.OPENAI_TTS_API_KEY
    if not api_key:
        return None

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(
                "https://api.openai.com/v1/audio/speech",
                json={"model": "tts-1", "input": text, "voice": "nova"},
                headers={"Authorization": f"Bearer {api_key}"}
            )
        if resp.status_code == 200:
            return resp.content
    except Exception:
        pass

    return None


# ================================
# 🧹 Clean text
# ================================

def _clean_for_tts(text: str) -> str:
    text = re.sub(r'\$\$([\s\S]+?)\$\$', lambda m: m.group(1).strip(), text)
    text = re.sub(r'\$([^\$\n]+?)\$', lambda m: m.group(1).strip(), text)
    return text.replace('$', '')


# ================================
# 🚀 Endpoint
# ================================

@router.post("/synthesize")
async def synthesize(
    req: TtsSynthesizeRequest,
    auth: AuthUser = Depends(require_auth),
):

    text = req.text.strip()

    if not text:
        raise HTTPException(400, "text cannot be empty")

    if len(text) > 5000:
        raise HTTPException(400, "text too long")

    # Android engine: no server audio needed — client uses device TTS
    if req.tts_engine == "android":
        return Response(content=b"", status_code=204)

    text = _clean_for_tts(text)

    speaking_rate = _select_speaking_rate(req.user_plan, text)

    # Determine if this is a non-English language
    is_english = req.language_code.lower().startswith("en")

    mp3 = await _google_tts(
        text,
        req.language_code,
        req.voice_name,
        speaking_rate,
        req.user_plan
    )

    # For non-English languages: ElevenLabs and OpenAI do not support Indian regional
    # languages — they would speak with an English accent. Only fall through for English.
    if mp3 is None and is_english:
        mp3 = await _elevenlabs_tts(text)

    if mp3 is None and is_english:
        mp3 = await _openai_tts(text)

    if mp3 is None:
        lang = req.language_code
        raise HTTPException(503, f"TTS unavailable for language '{lang}'")

    # track usage
    import asyncio
    asyncio.get_event_loop().run_in_executor(
        None, user_service.record_tts_chars, auth.uid, len(text)
    )

    return Response(
        content=mp3,
        media_type="audio/mpeg",
        headers={
            "X-TTS-Chars": str(len(text)),
            "X-TTS-Plan": req.user_plan
        }
    )