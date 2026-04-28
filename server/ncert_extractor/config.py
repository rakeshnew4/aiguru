"""
ncert_extractor/config.py — shared constants for NCERT ES pipeline.
Mirrors youtube_extractor/config.py style.
"""
import os
from app.core.config import settings

# ── Elasticsearch ─────────────────────────────────────────────────────────────
ES_HOST: str  = getattr(settings, "ES_HOST", "http://localhost:9200")
ES_INDEX: str = "ncert_chunks"          # separate index from yt_video_segments

# ── Vertex AI embeddings (same service-account as YouTube extractor) ──────────
_SERVER_DIR   = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERTEX_SA_JSON: str   = os.path.join(_SERVER_DIR, "google_tts_serviceaccount.json")
VERTEX_PROJECT: str   = "ai-app-8ebd0"
VERTEX_LOCATION: str  = getattr(settings, "VERTEX_LOCATION", "us-central1")
EMBED_MODEL_NAME: str = getattr(settings, "YT_EMBED_MODEL_NAME", "text-embedding-005")
EMBED_DIMS: int       = 768

# ── Chunking ──────────────────────────────────────────────────────────────────
CHUNK_SIZE: int    = 1200   # characters per chunk (char-level fallback)
CHUNK_OVERLAP: int = 150    # overlap between consecutive chunks
PAGES_PER_CHUNK: int = 2    # PDF pages grouped into one ES doc

# ── Retrieval ─────────────────────────────────────────────────────────────────
RETRIEVAL_K: int        = 4     # chunks to retrieve per query
SCORE_THRESHOLD: float  = 0.20  # min cosine similarity
MAX_CONTEXT_CHARS: int  = 2400  # cap on total context injected into system prompt
