"""
Chapter Index Service — Lazy + Warm Hybrid embedding pipeline.

Flow:
  1. is_indexed(chapter_id)       → Redis fast-path → ES count fallback
  2. index_chapter(...)           → chunk → embed → ES bulk upsert → Redis flag
  3. retrieve_context(...)        → ES knn search → ranked text chunks
  4. track_usage(chapter_id)      → Redis counter (drives background warming)

Reuses the same Vertex AI embedding model (text-embedding-005, 768 dims)
and ES cluster as the YouTube extractor — just a different index.
"""

from __future__ import annotations

import asyncio
import hashlib
import re
from functools import partial
from typing import Optional

import redis as sync_redis
from elasticsearch import AsyncElasticsearch

from app.core.config import settings
from app.core.logger import get_logger

_redis_client: sync_redis.Redis | None = None


def _get_redis() -> sync_redis.Redis:
    global _redis_client
    if _redis_client is None:
        _redis_client = sync_redis.Redis.from_url(settings.REDIS_URL, decode_responses=True)
    return _redis_client

logger = get_logger(__name__)

# ── Constants ─────────────────────────────────────────────────────────────────

ES_INDEX = "chapter_segments"
EMBED_DIMS = 768
CHUNK_SIZE_WORDS = 280          # target words per chunk
CHUNK_OVERLAP_WORDS = 40        # overlap between consecutive chunks
MIN_CHUNK_WORDS = 30            # discard tiny fragments
TOP_K_CHUNKS = 6                # chunks returned per retrieval query
SCORE_THRESHOLD = 0.30          # min cosine similarity to include a chunk
USAGE_WARM_THRESHOLD = 3        # background-index after this many requests

# Redis key prefixes
_KEY_INDEXED = "ch_indexed:"    # ch_indexed:{chapter_id}  → "1"
_KEY_USAGE   = "ch_usage:"      # ch_usage:{chapter_id}    → int count

# ── ES client (lazy singleton) ────────────────────────────────────────────────

_es: Optional[AsyncElasticsearch] = None


def _get_es() -> AsyncElasticsearch:
    global _es
    if _es is None:
        _es = AsyncElasticsearch([settings.ES_HOST])
    return _es


async def close_es() -> None:
    global _es
    if _es is not None:
        await _es.close()
        _es = None


# ── Vertex AI embedding (shared with YT extractor) ───────────────────────────

_vertex_model = None


def _get_vertex_model():
    global _vertex_model
    if _vertex_model is None:
        import vertexai
        from vertexai.language_models import TextEmbeddingModel
        from google.oauth2 import service_account
        import os

        sa_path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "google_tts_serviceaccount.json",
        )
        creds = service_account.Credentials.from_service_account_file(
            sa_path,
            scopes=["https://www.googleapis.com/auth/cloud-platform"],
        )
        vertexai.init(project="ai-app-8ebd0", location="us-central1", credentials=creds)
        _vertex_model = TextEmbeddingModel.from_pretrained("text-embedding-005")
    return _vertex_model


def _embed_batch_sync(texts: list[str]) -> list[list[float]]:
    model = _get_vertex_model()
    return [e.values for e in model.get_embeddings(texts)]


async def _embed_texts(texts: list[str]) -> list[list[float]]:
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _embed_batch_sync, texts)


# ── ES index bootstrap ────────────────────────────────────────────────────────

async def _ensure_index() -> None:
    es = _get_es()
    try:
        if await es.indices.exists(index=ES_INDEX):
            return
    except Exception:
        pass
    try:
        await es.indices.create(
            index=ES_INDEX,
            mappings={
                "properties": {
                    "chapter_id":    {"type": "keyword"},
                    "chapter_title": {"type": "text"},
                    "subject":       {"type": "keyword"},
                    "chunk_index":   {"type": "integer"},
                    "chunk_text":    {"type": "text"},
                    "embedding": {
                        "type": "dense_vector",
                        "dims": EMBED_DIMS,
                        "index": True,
                        "similarity": "cosine",
                    },
                }
            },
        )
        logger.info("[chapter_index] Created ES index '%s'", ES_INDEX)
    except Exception as exc:
        logger.debug("[chapter_index] _ensure_index: %s", exc)


# ── Deduplication ─────────────────────────────────────────────────────────────

def _redis_indexed_key(chapter_id: str) -> str:
    return _KEY_INDEXED + chapter_id


async def is_indexed(chapter_id: str) -> bool:
    """Fast Redis check; falls back to ES count if Redis misses."""
    try:
        r = _get_redis()
        if r.get(_redis_indexed_key(chapter_id)):
            return True
    except sync_redis.RedisError:
        pass

    # ES fallback
    try:
        es = _get_es()
        result = await es.count(
            index=ES_INDEX,
            query={"term": {"chapter_id": chapter_id}},
        )
        indexed = result["count"] > 0
        if indexed:
            _set_redis_indexed(chapter_id)
        return indexed
    except Exception:
        return False


def _set_redis_indexed(chapter_id: str) -> None:
    try:
        _get_redis().set(_redis_indexed_key(chapter_id), "1", ex=60 * 60 * 24 * 90)
    except sync_redis.RedisError:
        pass


# ── Usage tracking (drives background warming) ────────────────────────────────

def track_usage(chapter_id: str) -> int:
    """Increment request counter. Returns new count."""
    try:
        r = _get_redis()
        count = r.incr(_KEY_USAGE + chapter_id)
        r.expire(_KEY_USAGE + chapter_id, 60 * 60 * 24 * 30)
        return int(count)
    except sync_redis.RedisError:
        return 0


# ── Smart chunking ────────────────────────────────────────────────────────────

def _is_content_rich(text: str) -> bool:
    """Keep chunks that look like definitions, concepts, or examples."""
    markers = (
        "definition", "means", "is called", "refers to",
        "example", "for instance", "note that", "important",
        "therefore", "because", "explain", "describe",
        "process", "property", "principle", "law", "theory",
    )
    lower = text.lower()
    return any(m in lower for m in markers) or len(text.split()) >= MIN_CHUNK_WORDS + 20


def _chunk_text(text: str) -> list[str]:
    """
    Paragraph-aware chunking.  Splits on blank lines first, then merges
    small paragraphs into ~CHUNK_SIZE_WORDS windows with overlap.
    Filters out non-content chunks to reduce embedding cost by ~50-70 %.
    """
    # Normalise whitespace
    text = re.sub(r"\r\n|\r", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)

    # Split into paragraphs
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]

    chunks: list[str] = []
    current_words: list[str] = []

    def _flush():
        joined = " ".join(current_words).strip()
        if len(joined.split()) >= MIN_CHUNK_WORDS and _is_content_rich(joined):
            chunks.append(joined)

    for para in paragraphs:
        words = para.split()
        if len(current_words) + len(words) > CHUNK_SIZE_WORDS:
            _flush()
            # Start next chunk with overlap from tail of previous
            current_words = current_words[-CHUNK_OVERLAP_WORDS:] + words
        else:
            current_words.extend(words)

    _flush()
    return chunks


# ── Indexing ──────────────────────────────────────────────────────────────────

async def index_chapter(
    chapter_id: str,
    chapter_title: str,
    subject: str,
    text: str,
) -> bool:
    """
    Chunk, embed, and bulk-upsert chapter text into ES.
    Idempotent: skips if chapter_id already indexed (dedup via ES doc ID prefix).
    Returns True on success.
    """
    if not text.strip():
        logger.warning("[chapter_index] Empty text for chapter %s — skipping", chapter_id)
        return False

    if await is_indexed(chapter_id):
        logger.info("[chapter_index] Chapter %s already indexed — skipping", chapter_id)
        return True

    try:
        await _ensure_index()
        chunks = _chunk_text(text)
        if not chunks:
            logger.warning("[chapter_index] No valid chunks for chapter %s", chapter_id)
            return False

        logger.info("[chapter_index] Embedding %d chunks for chapter %s", len(chunks), chapter_id)
        embeddings = await _embed_texts(chunks)

        es = _get_es()
        operations: list[dict] = []
        for i, (chunk, emb) in enumerate(zip(chunks, embeddings)):
            doc_id = f"{chapter_id}_{i}"
            operations.append({"index": {"_index": ES_INDEX, "_id": doc_id}})
            operations.append({
                "chapter_id":    chapter_id,
                "chapter_title": chapter_title,
                "subject":       subject,
                "chunk_index":   i,
                "chunk_text":    chunk,
                "embedding":     emb,
            })

        await es.bulk(operations=operations, refresh="wait_for")
        _set_redis_indexed(chapter_id)
        logger.info("[chapter_index] Indexed %d chunks for chapter %s", len(chunks), chapter_id)
        return True

    except Exception as exc:
        logger.warning("[chapter_index] index_chapter failed for %s: %s", chapter_id, exc)
        return False


# ── Retrieval ─────────────────────────────────────────────────────────────────

async def retrieve_context(chapter_id: str, query: str, top_k: int = TOP_K_CHUNKS) -> str:
    """
    kNN search in ES for chunks relevant to *query* within *chapter_id*.
    Returns concatenated chunk text ready to inject into the LLM prompt.
    Returns "" if ES unavailable or chapter not indexed.
    """
    try:
        query_vec = await _embed_texts([query])
        es = _get_es()
        resp = await es.search(
            index=ES_INDEX,
            knn={
                "field": "embedding",
                "query_vector": query_vec[0],
                "k": top_k,
                "num_candidates": top_k * 5,
                "filter": {"term": {"chapter_id": chapter_id}},
            },
            size=top_k,
            _source=["chunk_text", "chunk_index"],
        )
        hits = resp["hits"]["hits"]
        # Filter by score threshold and sort by chunk position for coherence
        relevant = [
            h for h in hits
            if h.get("_score", 0) >= SCORE_THRESHOLD
        ]
        relevant.sort(key=lambda h: h["_source"].get("chunk_index", 0))
        chunks = [h["_source"]["chunk_text"] for h in relevant]
        if not chunks:
            return ""
        return "\n\n".join(chunks)
    except Exception as exc:
        logger.warning("[chapter_index] retrieve_context failed for %s: %s", chapter_id, exc)
        return ""
