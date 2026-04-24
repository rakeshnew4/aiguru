import asyncio
import logging

from .config import YOUTUBE_API_KEY, YOUTUBE_SA_JSON

logger = logging.getLogger(__name__)


def _build_youtube_service():
    """Build YouTube API service client.

    Priority:
    1. YOUTUBE_API_KEY → simple API-key auth
    2. YOUTUBE_SA_JSON (google_tts_serviceaccount.json) → service-account OAuth2
    Returns None when neither is configured.
    """
    from googleapiclient.discovery import build

    if YOUTUBE_API_KEY:
        return build("youtube", "v3", developerKey=YOUTUBE_API_KEY)

    if YOUTUBE_SA_JSON:
        from google.oauth2 import service_account
        creds = service_account.Credentials.from_service_account_file(
            YOUTUBE_SA_JSON,
            scopes=["https://www.googleapis.com/auth/youtube.readonly"],
        )
        logger.info("[yt_extractor] Using service account for YouTube API: %s", YOUTUBE_SA_JSON)
        return build("youtube", "v3", credentials=creds)

    logger.debug("[yt_extractor] No YouTube credentials configured — skipping search")
    return None


def _search_sync(query: str, max_results: int) -> list[dict]:
    youtube = _build_youtube_service()
    if youtube is None:
        return []

    try:
        request = youtube.search().list(
            q=query,
            part="snippet",
            type="video",
            maxResults=max_results,
            videoCaption="closedCaption",
            relevanceLanguage="en",
            order="relevance",
        )
        data = request.execute()
        videos = [
            {
                "video_id": item["id"]["videoId"],
                "title": item["snippet"]["title"],
                "channel": item["snippet"]["channelTitle"],
            }
            for item in data.get("items", [])
            if item.get("id", {}).get("videoId")
        ]
        logger.info("[yt_extractor] YouTube search '%s' → %d videos", query[:60], len(videos))
        return videos
    except Exception as exc:
        logger.warning("[yt_extractor] YouTube search API call failed: %s", exc)
        return []


async def search_videos(query: str, max_results: int = 3) -> list[dict]:
    """Search YouTube Data API v3 for videos with captions matching query.

    Returns list of {video_id, title, channel}.
    Returns [] when credentials are missing or on any error.
    """
    loop = asyncio.get_event_loop()
    try:
        return await loop.run_in_executor(None, _search_sync, query, max_results)
    except Exception as exc:
        logger.warning("[yt_extractor] search_videos failed: %s", exc)
        return []
