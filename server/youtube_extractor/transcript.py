import asyncio
from app.core.logger import get_logger

logger = get_logger(__name__)
_LANGUAGES = ["en", "en-US", "en-GB"]


async def fetch_transcript(video_id: str) -> list[dict]:
    """Fetch YouTube transcript with timestamps.

    Returns list of {text, start, duration} or [] when unavailable.
    Uses youtube-transcript-api (sync) via executor to stay non-blocking.
    """
    loop = asyncio.get_event_loop()
    try:
        transcript = await loop.run_in_executor(None, _fetch_sync, video_id)
        logger.debug("[yt_extractor] Transcript for %s: %d segments", video_id, len(transcript))
        return transcript
    except Exception as exc:
        logger.debug("[yt_extractor] Transcript unavailable for %s: %s", video_id, exc)
        return []


def _fetch_sync(video_id: str) -> list[dict]:
    from youtube_transcript_api import YouTubeTranscriptApi
    try:
        api = YouTubeTranscriptApi()

        if hasattr(api, "fetch"):
            raw = api.fetch(video_id, languages=_LANGUAGES)
            if hasattr(raw, "to_raw_data"):
                raw = raw.to_raw_data()
        else:
            raw = YouTubeTranscriptApi.get_transcript(video_id, languages=_LANGUAGES)

        normalized: list[dict] = []
        for item in raw:
            if isinstance(item, dict):
                normalized.append({
                    "text": item.get("text", ""),
                    "start": item.get("start", 0.0),
                    "duration": item.get("duration", 0.0),
                })
            else:
                normalized.append({
                    "text": getattr(item, "text", ""),
                    "start": getattr(item, "start", 0.0),
                    "duration": getattr(item, "duration", 0.0),
                })
        return normalized
    except Exception as exc:
        logger.debug("[yt_extractor] Transcript fetch failed for %s: %s", video_id, exc)
        return []
