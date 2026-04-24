import asyncio
from app.core.logger import get_logger
from .searcher import search_videos
from .transcript import fetch_transcript
from .indexer import index_video, is_video_indexed
from .retriever import find_best_segment

logger = get_logger(__name__)

# Minimum score to ever show a clip; second clip needs a higher bar
_MIN_SCORE = 0.65
_HIGH_SCORE = 0.72
_MIN_GAP_SECONDS = 60  # same-video clips must be ≥60s apart to be considered "different"
_YT_WATCH_BASE_URL = "https://www.youtube.com/watch?v="


def _decorate_clip(clip: dict, video_lookup: dict[str, dict]) -> dict:
    video_meta = video_lookup.get(clip["video_id"], {})
    start_seconds = int(clip.get("start_seconds", 0))
    end_seconds = int(clip.get("end_seconds", 0))

    enriched = dict(clip)
    enriched["watch_url"] = video_meta.get("watch_url") or f"{_YT_WATCH_BASE_URL}{clip['video_id']}"
    enriched["start_url"] = f"{enriched['watch_url']}&t={start_seconds}s"
    enriched["clip_duration_seconds"] = max(0, end_seconds - start_seconds)

    if video_meta.get("duration_seconds") is not None:
        enriched["video_duration_seconds"] = int(video_meta.get("duration_seconds") or 0)
    if video_meta.get("channel"):
        enriched["channel"] = video_meta["channel"]

    return enriched


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
    """Search YouTube, index transcripts, find at most 1-2 best clips for the session.

    Returns a sparse list aligned with plan['steps']:
    - At most 2 non-None entries (the best-matched steps only).
    - Thresholds: first clip ≥ 0.65, second clip ≥ 0.72 and meaningfully different.
    """
    step_titles: list[str] = plan.get("steps", [])
    if not step_titles:
        return []

    results: list[dict | None] = [None] * len(step_titles)

    kc = plan.get("key_concepts", "")
    if isinstance(kc, list):
        kc = " ".join(str(c) for c in kc)
    base_query = f"{question} {kc}".strip()[:200]
    logger.info("[yt_extractor] base_query='%s'", base_query[:120])

    try:
        videos = await search_videos(base_query, max_results=3)
        if not videos:
            logger.info("[yt_extractor] No YouTube results for: %s", base_query[:80])
            return results
        logger.info("[yt_extractor] Found %d videos: %s", len(videos), [v['video_id'] for v in videos])
        video_lookup = {video["video_id"]: video for video in videos}

        await asyncio.gather(*[_process_video(v) for v in videos], return_exceptions=True)
        video_ids = [v["video_id"] for v in videos]

        # Find best segment per step concurrently (uses SCORE_THRESHOLD from config)
        raw_clips = await asyncio.gather(
            *[find_best_segment(title, video_ids) for title in step_titles],
            return_exceptions=True,
        )

        # Collect (step_idx, clip) candidates with score ≥ _MIN_SCORE
        candidates: list[tuple[int, dict]] = [
            (i, clip)
            for i, clip in enumerate(raw_clips)
            if isinstance(clip, dict) and clip.get("score", 0) >= _MIN_SCORE
        ]
        candidates.sort(key=lambda x: x[1]["score"], reverse=True)

        if not candidates:
            logger.info("[yt_extractor] No clips above threshold for: %s", base_query[:60])
            return results

        # Always include the best clip
        best_idx, best_clip = candidates[0]
        results[best_idx] = _decorate_clip(best_clip, video_lookup)
        logger.info(
            "[yt_extractor] Best clip: step=%d score=%.3f video=%s t=%d-%ds",
            best_idx, best_clip["score"], best_clip["video_id"],
            best_clip["start_seconds"], best_clip["end_seconds"],
        )

        # Optionally include a second clip if it scores high enough and is distinct
        for idx, clip in candidates[1:]:
            if clip.get("score", 0) < _HIGH_SCORE:
                break
            same_video = clip["video_id"] == best_clip["video_id"]
            gap = abs(clip["start_seconds"] - best_clip["start_seconds"])
            if same_video and gap < _MIN_GAP_SECONDS:
                continue  # too close to the first clip on the same video
            results[idx] = _decorate_clip(clip, video_lookup)
            logger.info(
                "[yt_extractor] Second clip: step=%d score=%.3f video=%s t=%d-%ds",
                idx, clip["score"], clip["video_id"],
                clip["start_seconds"], clip["end_seconds"],
            )
            break  # max 2 total

        found = sum(1 for r in results if r)
        logger.info("[yt_extractor] Attached %d clip(s) across %d steps", found, len(step_titles))

    except Exception as exc:
        logger.warning("[yt_extractor] enrich_steps_with_videos failed: %s", exc)

    return results
