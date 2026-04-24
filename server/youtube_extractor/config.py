import os
from app.core.config import settings

YOUTUBE_API_KEY: str = getattr(settings, "YOUTUBE_API_KEY", "")
# Service account JSON for YouTube Data API v3 + Vertex AI (google_tts_serviceaccount.json = vertex-express@ai-app-8ebd0)
_SERVER_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Prefer google_service_youtube.json (root), fall back to server/google_tts_serviceaccount.json
# _yt_root = os.path.join(os.path.dirname(_SERVER_DIR), "google_service_youtube.json")
_yt_root=""
_yt_server = os.path.join(_SERVER_DIR, "google_tts_serviceaccount.json")
YOUTUBE_SA_JSON: str = _yt_server
ES_HOST: str = getattr(settings, "ES_HOST", "http://localhost:9200")
ES_INDEX: str = "yt_video_segments"
GEMINI_API_KEY: str = getattr(settings, "GEMINI_API_KEY", "")
VERTEX_SA_JSON: str = YOUTUBE_SA_JSON  # same service account handles both YouTube + Vertex AI
VERTEX_PROJECT: str = "ai-app-8ebd0"
VERTEX_LOCATION: str = getattr(settings, "VERTEX_LOCATION", "us-central1")
EMBED_MODEL_NAME: str = getattr(settings, "YT_EMBED_MODEL_NAME", "text-embedding-005")
EMBED_DIMS: int = 768
CHUNK_WINDOW_SEC: int = 30
CHUNK_OVERLAP_SEC: int = 10
SCORE_THRESHOLD: float = float(getattr(settings, "YT_SCORE_THRESHOLD", 0.2))
ENRICHMENT_TIMEOUT: float = float(getattr(settings, "YT_ENRICHMENT_TIMEOUT", 2.5))
