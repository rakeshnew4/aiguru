import logging
import httpx
from .config import YOUTUBE_API_KEY

logger = logging.getLogger(__name__)

_YT_SEARCH_URL = "https://www.googleapis.com/youtube/v3/search"


async def search_videos(query: str, max_results: int = 3) -> list[dict]:
    """Search YouTube Data API v3 for videos with captions matching query.

    Returns list of {video_id, title, channel}.
    Returns [] when API key is missing or on any error.
    """
    if not YOUTUBE_API_KEY:
        logger.debug("[yt_extractor] YOUTUBE_API_KEY not set — skipping search")
        return []

    params = {
        "key": YOUTUBE_API_KEY,
        "q": query,
        "part": "snippet",
        "type": "video",
        "maxResults": max_results,
        "videoCaption": "closedCaption",
        "relevanceLanguage": "en",
        "order": "relevance",
    }
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(_YT_SEARCH_URL, params=params)
            resp.raise_for_status()
            data = resp.json()
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
        logger.warning("[yt_extractor] YouTube search failed: %s", exc)
        return []
