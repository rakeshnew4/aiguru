import asyncio
import logging
from elasticsearch import AsyncElasticsearch, NotFoundError
from .config import ES_HOST, ES_INDEX, EMBED_MODEL_NAME, CHUNK_WINDOW_SEC, CHUNK_OVERLAP_SEC

logger = logging.getLogger(__name__)

_model = None
_es: AsyncElasticsearch | None = None


def _load_model_sync():
    global _model
    if _model is None:
        from sentence_transformers import SentenceTransformer
        _model = SentenceTransformer(EMBED_MODEL_NAME)
        logger.info("[yt_extractor] Sentence-transformers model '%s' loaded", EMBED_MODEL_NAME)
    return _model


def get_es() -> AsyncElasticsearch:
    global _es
    if _es is None:
        _es = AsyncElasticsearch([ES_HOST])
    return _es


def chunk_transcript(transcript: list[dict]) -> list[dict]:
    """Split transcript into overlapping 30-second windows."""
    if not transcript:
        return []
    chunks = []
    i = 0
    while i < len(transcript):
        window_start = transcript[i]["start"]
        window_end = window_start
        window_words: list[str] = []
        j = i
        while j < len(transcript):
            entry = transcript[j]
            entry_end = entry["start"] + entry.get("duration", 2.0)
            if entry_end - window_start > CHUNK_WINDOW_SEC:
                break
            window_words.append(entry["text"].strip())
            window_end = entry_end
            j += 1
        if window_words:
            chunks.append({
                "text": " ".join(window_words),
                "start_seconds": int(window_start),
                "end_seconds": int(window_end),
            })
        # Advance by (window - overlap) seconds
        advance_to = window_start + (CHUNK_WINDOW_SEC - CHUNK_OVERLAP_SEC)
        i_next = i + 1
        while i_next < len(transcript) and transcript[i_next]["start"] < advance_to:
            i_next += 1
        if i_next == i:
            i_next = i + 1  # safety: always move forward
        i = i_next
    return chunks


async def _ensure_index() -> None:
    es = get_es()
    try:
        exists = await es.indices.exists(index=ES_INDEX)
        if exists:
            return
    except Exception:
        pass
    try:
        await es.indices.create(
            index=ES_INDEX,
            mappings={
                "properties": {
                    "video_id": {"type": "keyword"},
                    "title": {"type": "text"},
                    "chunk_text": {"type": "text"},
                    "start_seconds": {"type": "integer"},
                    "end_seconds": {"type": "integer"},
                    "embedding": {
                        "type": "dense_vector",
                        "dims": 384,
                        "index": True,
                        "similarity": "cosine",
                    },
                }
            },
        )
        logger.info("[yt_extractor] Created ES index '%s'", ES_INDEX)
    except Exception as exc:
        # Index may have been created concurrently — not fatal
        logger.debug("[yt_extractor] _ensure_index: %s", exc)


async def is_video_indexed(video_id: str) -> bool:
    es = get_es()
    try:
        result = await es.count(index=ES_INDEX, query={"term": {"video_id": video_id}})
        return result["count"] > 0
    except Exception:
        return False


async def index_video(video_id: str, title: str, transcript: list[dict]) -> bool:
    """Chunk transcript, embed with sentence-transformers, and bulk-upsert to ES."""
    if not transcript:
        return False
    try:
        await _ensure_index()
        chunks = chunk_transcript(transcript)
        if not chunks:
            return False

        loop = asyncio.get_event_loop()
        model = await loop.run_in_executor(None, _load_model_sync)
        texts = [c["text"] for c in chunks]
        embeddings = await loop.run_in_executor(
            None, lambda: model.encode(texts, show_progress_bar=False).tolist()
        )

        es = get_es()
        operations: list[dict] = []
        for chunk, emb in zip(chunks, embeddings):
            doc_id = f"{video_id}_{chunk['start_seconds']}"
            operations.append({"index": {"_index": ES_INDEX, "_id": doc_id}})
            operations.append({
                "video_id": video_id,
                "title": title,
                "chunk_text": chunk["text"],
                "start_seconds": chunk["start_seconds"],
                "end_seconds": chunk["end_seconds"],
                "embedding": emb,
            })
        await es.bulk(operations=operations)
        logger.info("[yt_extractor] Indexed %d chunks for video %s", len(chunks), video_id)
        return True
    except Exception as exc:
        logger.warning("[yt_extractor] index_video failed for %s: %s", video_id, exc)
        return False


async def preload_model() -> None:
    """Warm up the embedding model at server startup to avoid first-request latency."""
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _load_model_sync)
