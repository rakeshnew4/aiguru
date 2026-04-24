import os
from app.core.config import settings

YOUTUBE_API_KEY: str = getattr(settings, "YOUTUBE_API_KEY", "")
ES_HOST: str = getattr(settings, "ES_HOST", "http://localhost:9200")
ES_INDEX: str = "yt_video_segments"
EMBED_MODEL_NAME: str = "all-MiniLM-L6-v2"
CHUNK_WINDOW_SEC: int = 30
CHUNK_OVERLAP_SEC: int = 10
SCORE_THRESHOLD: float = float(getattr(settings, "YT_SCORE_THRESHOLD", 0.65))
ENRICHMENT_TIMEOUT: float = float(getattr(settings, "YT_ENRICHMENT_TIMEOUT", 2.5))
