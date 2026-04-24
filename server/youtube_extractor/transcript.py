import asyncio
import logging

logger = logging.getLogger(__name__)


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
    from youtube_transcript_api import (
        YouTubeTranscriptApi,
        TranscriptsDisabled,
        NoTranscriptFound,
    )
    try:
        return YouTubeTranscriptApi.get_transcript(video_id, languages=["en", "en-US", "en-GB"])
    except (TranscriptsDisabled, NoTranscriptFound):
        return []
