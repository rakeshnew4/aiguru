"""
ncert_extractor/retriever.py

Semantic search over indexed NCERT chapters.
Called by context_service.get_context() at request time.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Optional

from .config import ES_INDEX, RETRIEVAL_K, SCORE_THRESHOLD, MAX_CONTEXT_CHARS
from .indexer import get_es, embed_texts

logger = logging.getLogger(__name__)


async def retrieve_context(
    question: str,
    chapter_id: str,
    k: int = RETRIEVAL_K,
) -> Optional[str]:
    """
    Find the most relevant NCERT text chunks for a given question+chapter.

    Returns a single concatenated context string (capped at MAX_CONTEXT_CHARS),
    or None if ES is unavailable or no good match found.

    Args:
        question:   The student's question (used as the query vector).
        chapter_id: Firestore chapter doc ID (e.g. "sci10_ch1") used as a filter.
        k:          Max number of chunks to retrieve.
    """
    try:
        query_emb: list = (await embed_texts([question]))[0]

        knn_body = {
            "field":        "embedding",
            "query_vector": query_emb,
            "k":            k,
            "num_candidates": k * 5,
            "filter": [{"term": {"chapter_id": chapter_id}}],
        }

        es = get_es()
        result = await es.search(
            index=ES_INDEX,
            knn=knn_body,
            size=k,
            source=["chunk_text", "chunk_index"],
        )
        hits = result["hits"]["hits"]
        if not hits:
            logger.info("ncert_retriever: no hits for chapter_id=%s", chapter_id)
            return None

        # Sort by chunk_index to preserve reading order, filter by score
        good_hits = [h for h in hits if (h.get("_score") or 0) >= SCORE_THRESHOLD]
        if not good_hits:
            logger.info(
                "ncert_retriever: all hits below threshold %.2f for chapter=%s",
                SCORE_THRESHOLD, chapter_id,
            )
            return None

        good_hits.sort(key=lambda h: h["_source"].get("chunk_index", 0))

        # Concatenate chunks up to MAX_CONTEXT_CHARS
        parts = []
        total = 0
        for h in good_hits:
            txt = h["_source"].get("chunk_text", "").strip()
            if not txt:
                continue
            if total + len(txt) > MAX_CONTEXT_CHARS:
                remaining = MAX_CONTEXT_CHARS - total
                if remaining > 100:
                    parts.append(txt[:remaining])
                break
            parts.append(txt)
            total += len(txt)

        if not parts:
            return None

        context = "\n\n".join(parts)
        logger.info(
            "ncert_retriever: %d chunks / %d chars for chapter=%s question='%s'",
            len(parts), len(context), chapter_id, question[:60],
        )
        return context

    except Exception as exc:
        logger.warning("ncert_retriever: retrieve_context failed: %s", exc)
        return None


def retrieve_context_sync(question: str, chapter_id: str) -> Optional[str]:
    """
    Synchronous wrapper — used by context_service which is called from a sync context.
    Creates a new event loop only if no running loop exists.
    """
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            # We're inside an async context (e.g. FastAPI) — use run_in_executor
            import concurrent.futures
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as ex:
                future = ex.submit(
                    lambda: asyncio.run(retrieve_context(question, chapter_id))
                )
                return future.result(timeout=5.0)
        else:
            return loop.run_until_complete(retrieve_context(question, chapter_id))
    except Exception as exc:
        logger.warning("ncert_retriever: sync wrapper failed: %s", exc)
        return None
