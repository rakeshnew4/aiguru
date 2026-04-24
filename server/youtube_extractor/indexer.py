from app.core.logger import get_logger
from elasticsearch import AsyncElasticsearch
from .config import (
    ES_HOST, ES_INDEX, EMBED_MODEL_NAME, EMBED_DIMS,
    VERTEX_SA_JSON, VERTEX_PROJECT, VERTEX_LOCATION,
    CHUNK_WINDOW_SEC, CHUNK_OVERLAP_SEC,
)

logger = get_logger(__name__)

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


_vertex_model = None


def _get_vertex_model():
    """Lazy-init Vertex AI TextEmbeddingModel with service account credentials."""
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


def _embed_batch_sync(texts: list[str]) -> list[list[float]]:
    model = _get_vertex_model()
    return [e.values for e in model.get_embeddings(texts)]


async def embed_texts(texts: list[str]) -> list[list[float]]:
    """Embed texts using the configured Vertex AI embedding model."""
    import asyncio
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _embed_batch_sync, texts)


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
        advance_to = window_start + (CHUNK_WINDOW_SEC - CHUNK_OVERLAP_SEC)
        i_next = i + 1
        while i_next < len(transcript) and transcript[i_next]["start"] < advance_to:
            i_next += 1
        if i_next == i:
            i_next = i + 1
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
                        "dims": EMBED_DIMS,
                        "index": True,
                        "similarity": "cosine",
                    },
                }
            },
        )
        logger.info("[yt_extractor] Created ES index '%s' (dims=%d)", ES_INDEX, EMBED_DIMS)
    except Exception as exc:
        logger.debug("[yt_extractor] _ensure_index: %s", exc)


async def is_video_indexed(video_id: str) -> bool:
    es = get_es()
    try:
        result = await es.count(index=ES_INDEX, query={"term": {"video_id": video_id}})
        return result["count"] > 0
    except Exception:
        return False


async def index_video(video_id: str, title: str, transcript: list[dict]) -> bool:
    """Chunk transcript, embed with Gemini text-embedding-004, and bulk-upsert to ES."""
    if not transcript:
        return False
    try:
        await _ensure_index()
        chunks = chunk_transcript(transcript)
        if not chunks:
            return False

        valid_chunks = [c for c in chunks if c["text"].strip()]
        if not valid_chunks:
            logger.info("[yt_extractor] Skipping video %s: all transcript chunks empty", video_id)
            return False

        texts = [c["text"] for c in valid_chunks]
        embeddings = await embed_texts(texts)

        es = get_es()
        operations: list[dict] = []
        for chunk, emb in zip(valid_chunks, embeddings):
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
        logger.info("[yt_extractor] Indexed %d chunks for video %s", len(valid_chunks), video_id)
        return True
    except Exception as exc:
        logger.warning("[yt_extractor] index_video failed for %s: %s", video_id, exc)
        return False


async def preload_model() -> None:
    """No-op: Gemini embedding is a remote API, no local model to warm up."""
    pass
