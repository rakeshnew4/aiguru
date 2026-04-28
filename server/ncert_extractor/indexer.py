"""
ncert_extractor/indexer.py

Helpers used by the one-time seeding script (seed_ncert_es.py):
  - get_es()          — lazy AsyncElasticsearch client
  - ensure_index()    — create index with kNN mapping if missing
  - embed_texts()     — batch Vertex AI embeddings (same model as yt_extractor)
  - chunk_text()      — split chapter text into overlapping chunks
"""
from __future__ import annotations

import asyncio
import logging
from typing import List

from elasticsearch import AsyncElasticsearch

from .config import (
    ES_HOST, ES_INDEX, EMBED_DIMS,
    EMBED_MODEL_NAME, VERTEX_SA_JSON, VERTEX_PROJECT, VERTEX_LOCATION,
    CHUNK_SIZE, CHUNK_OVERLAP,
)

logger = logging.getLogger(__name__)

# ── ES client ─────────────────────────────────────────────────────────────────
_es: AsyncElasticsearch | None = None


def get_es() -> AsyncElasticsearch:
    global _es
    if _es is None:
        _es = AsyncElasticsearch([ES_HOST])
    return _es


async def close_es() -> None:
    global _es
    if _es is not None:
        await _es.close()
        _es = None


# ── Index management ──────────────────────────────────────────────────────────
MAPPING = {
    "mappings": {
        "properties": {
            "chapter_id":     {"type": "keyword"},
            "subject_id":     {"type": "keyword"},
            "grade":          {"type": "keyword"},
            "chapter_title":  {"type": "text"},
            "chunk_index":    {"type": "integer"},
            "chunk_text":     {"type": "text"},
            "embedding": {
                "type":       "dense_vector",
                "dims":       EMBED_DIMS,
                "index":      True,
                "similarity": "cosine",
            },
        }
    },
    "settings": {
        "number_of_shards":   1,
        "number_of_replicas": 0,
    },
}


async def ensure_index() -> None:
    es = get_es()
    exists = await es.indices.exists(index=ES_INDEX)
    if not exists:
        await es.indices.create(index=ES_INDEX, body=MAPPING)
        logger.info("ncert_extractor: created ES index '%s'", ES_INDEX)
    else:
        logger.info("ncert_extractor: ES index '%s' already exists", ES_INDEX)


# ── Vertex AI embeddings ──────────────────────────────────────────────────────
_vertex_model = None


def _get_vertex_model():
    global _vertex_model
    if _vertex_model is None:
        import vertexai
        from vertexai.language_models import TextEmbeddingModel
        from google.oauth2 import service_account
        creds = service_account.Credentials.from_service_account_file(
            VERTEX_SA_JSON,
            scopes=["https://www.googleapis.com/auth/cloud-platform"],
        )
        vertexai.init(project=VERTEX_PROJECT, location=VERTEX_LOCATION, credentials=creds)
        _vertex_model = TextEmbeddingModel.from_pretrained(EMBED_MODEL_NAME)
    return _vertex_model


def _embed_batch_sync(texts: List[str]) -> List[List[float]]:
    model = _get_vertex_model()
    results = model.get_embeddings(texts)
    return [r.values for r in results]


async def embed_texts(texts: List[str]) -> List[List[float]]:
    """Embed a batch of texts via Vertex AI (runs in executor to avoid blocking)."""
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _embed_batch_sync, texts)


# ── Text chunking ─────────────────────────────────────────────────────────────
def chunk_text(text: str) -> List[str]:
    """
    Split text into overlapping char-level chunks.
    Attempts to split on paragraph/sentence boundaries within each window.
    """
    text = text.strip()
    if not text:
        return []
    chunks: List[str] = []
    start = 0
    while start < len(text):
        end = min(start + CHUNK_SIZE, len(text))
        # Try to break at a sentence/paragraph boundary inside the window
        if end < len(text):
            # Look backwards for a newline or period within last 200 chars of chunk
            boundary = max(start, end - 200)
            nl  = text.rfind("\n", boundary, end)
            dot = text.rfind(". ", boundary, end)
            snap = max(nl, dot)
            if snap > start:
                end = snap + 1
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        start = end - CHUNK_OVERLAP
        if start >= len(text):
            break
    return chunks
