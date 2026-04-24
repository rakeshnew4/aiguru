from app.core.logger import get_logger
from .indexer import get_es, embed_texts
from .config import ES_INDEX, SCORE_THRESHOLD

logger = get_logger(__name__)


async def find_best_segment(query: str, video_ids: list[str] | None = None) -> dict | None:
    """Run kNN search in ES and return the best matching clip for query.

    Returns {video_id, start_seconds, end_seconds, title, score} or None when
    no match is above SCORE_THRESHOLD or ES is unavailable.
    """
    try:
        query_emb: list[float] = (await embed_texts([query]))[0]

        knn_body: dict = {
            "field": "embedding",
            "query_vector": query_emb,
            "k": 1,
            "num_candidates": 20,
        }
        if video_ids:
            knn_body["filter"] = [{"terms": {"video_id": video_ids}}]

        es = get_es()
        result = await es.search(
            index=ES_INDEX,
            knn=knn_body,
            size=1,
            source=["video_id", "title", "start_seconds", "end_seconds"],
        )
        hits = result["hits"]["hits"]
        if not hits:
            return None
        hit = hits[0]
        score: float = hit.get("_score") or 0.0
        if score < SCORE_THRESHOLD:
            logger.info(
                "[yt_extractor] Best segment score %.3f below threshold %.3f for '%s'",
                score, SCORE_THRESHOLD, query[:60],
            )
            return None
        logger.info(
            "[yt_extractor] Segment matched score=%.3f for '%s'",
            score, query[:60],
        )
        src = hit["_source"]
        return {
            "video_id": src["video_id"],
            "start_seconds": src["start_seconds"],
            "end_seconds": src["end_seconds"],
            "title": src["title"],
            "score": score,
        }
    except Exception as exc:
        logger.warning("[yt_extractor] find_best_segment failed for '%s': %s", query[:60], exc)
        return None
