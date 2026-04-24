import asyncio
import logging
from .searcher import search_videos
from .transcript import fetch_transcript
from .indexer import index_video, is_video_indexed
from .retriever import find_best_segment

logger = logging.getLogger(__name__)


async def _process_video(video: dict) -> bool:
    """Fetch transcript and index a video; skip when already in ES."""
    video_id = video["video_id"]
    if await is_video_indexed(video_id):
        logger.debug("[yt_extractor] %s already indexed — cache hit", video_id)
        return True
    transcript = await fetch_transcript(video_id)
    if not transcript:
        return False
    return await index_video(video_id, video["title"], transcript)


async def enrich_steps_with_videos(question: str, plan: dict) -> list[dict | None]:
    """Search YouTube, index transcripts, and find best clip per BB step.

    Designed to run concurrently with the main LLM call via asyncio.ensure_future.
    Returns a list aligned with plan['steps']: each element is either
    {video_id, start_seconds, end_seconds, title, score} or None.
    """
    step_titles: list[str] = plan.get("steps", [])
    if not step_titles:
        return []

    results: list[dict | None] = [None] * len(step_titles)

    # Blend question + key concepts into a single search query
    key_concepts = plan.get("key_concepts", "")
    base_query = f"{question} {key_concepts}".strip()[:200]

    try:
        videos = await search_videos(base_query, max_results=3)
        if not videos:
            logger.info("[yt_extractor] No YouTube results for: %s", base_query[:80])
            return results

        # Index all videos concurrently (no-op for already-indexed ones)
        await asyncio.gather(*[_process_video(v) for v in videos], return_exceptions=True)
        video_ids = [v["video_id"] for v in videos]

        # Retrieve best segment per step concurrently
        clips = await asyncio.gather(
            *[find_best_segment(title, video_ids) for title in step_titles],
            return_exceptions=True,
        )
        for i, clip in enumerate(clips):
            if isinstance(clip, dict):
                results[i] = clip

        found = sum(1 for r in results if r)
        logger.info("[yt_extractor] Enriched %d/%d steps with YouTube clips", found, len(step_titles))
    except Exception as exc:
        logger.warning("[yt_extractor] enrich_steps_with_videos failed: %s", exc)

    return results
