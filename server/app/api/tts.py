# Google TTS removed — all speech synthesis is handled by the Android TextToSpeech engine.
from fastapi import APIRouter
from app.core.logger import get_logger

logger = get_logger(__name__)
router = APIRouter(prefix="", tags=["tts"])

