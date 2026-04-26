import asyncio
from app.core.logger import get_logger
from .searcher import search_videos
from .transcript import fetch_transcript
from .indexer import index_video, is_video_indexed
from .retriever import find_best_segment

logger = get_logger(__name__)

# Minimum score to ever show a clip; second clip needs a higher bar
_MIN_SCORE = 0.85
_HIGH_SCORE = 0.92
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


async def enrich_steps_with_videos(
    question: str,
    plan: dict,
    video_search_query: str = "",
    preferred_channels: list[str] | None = None,
    session_theme: str = "",
) -> list[dict]:
    """Search YouTube once for the whole lesson and find 1-2 best reference clips."""
    preferred_channels = [str(c).strip() for c in (preferred_channels or []) if str(c).strip()][:4]
    if not preferred_channels:
        preferred_channels = ["Physics Wallah", "Khan Academy India", "Magnet Brains"]

    theme = (session_theme or plan.get("question_focus") or question).strip()
    query_core = (video_search_query or theme or question).strip()
    channel_hint = " ".join(preferred_channels[:3]).strip()
    location_hint = "" if "india" in query_core.lower() else "India"
    base_query = " ".join(part for part in [query_core, channel_hint, location_hint] if part).strip()[:220]
    logger.info(
        "[yt_extractor] base_query='%s' | theme='%s' | channels=%s",
        base_query[:120], theme[:80], preferred_channels[:3]
    )

    try:
        videos = await search_videos(base_query, max_results=4)
        if not videos:
            logger.info("[yt_extractor] No YouTube results for: %s", base_query[:80])
            return []
        logger.info("[yt_extractor] Found %d videos: %s", len(videos), [v['video_id'] for v in videos])
        video_lookup = {v["video_id"]: v for v in videos}

        await asyncio.gather(*[_process_video(v) for v in videos], return_exceptions=True)
        video_ids = [v["video_id"] for v in videos]

        # Query the transcript index with one lesson-level theme instead of step-specific fragments.
        queries = [query_core]
        if theme and theme.lower() != query_core.lower():
            queries.append(theme)
        raw_clips = await asyncio.gather(
            *[find_best_segment(q, video_ids) for q in queries],
            return_exceptions=True,
        )

        # Deduplicate by video+timestamp, filter by _MIN_SCORE
        seen: set[str] = set()
        candidates: list[dict] = []
        for clip in raw_clips:
            if not isinstance(clip, dict):
                continue
            uid = f"{clip['video_id']}_{clip['start_seconds']}"
            if uid in seen or clip.get("score", 0) < _MIN_SCORE:
                continue
            seen.add(uid)
            candidates.append(clip)
        candidates.sort(key=lambda c: c["score"], reverse=True)

        if not candidates:
            logger.info("[yt_extractor] No clips above threshold for: %s", base_query[:60])
            return []

        result: list[dict] = []

        best = candidates[0]
        result.append(_decorate_clip(best, video_lookup))
        logger.info(
            "[yt_extractor] Best clip: score=%.3f video=%s t=%d-%ds",
            best["score"], best["video_id"], best["start_seconds"], best["end_seconds"],
        )

        for clip in candidates[1:]:
            if clip["score"] < _HIGH_SCORE:
                break
            same_video = clip["video_id"] == best["video_id"]
            gap = abs(clip["start_seconds"] - best["start_seconds"])
            if same_video and gap < _MIN_GAP_SECONDS:
                continue
            result.append(_decorate_clip(clip, video_lookup))
            logger.info(
                "[yt_extractor] Second clip: score=%.3f video=%s t=%d-%ds",
                clip["score"], clip["video_id"], clip["start_seconds"], clip["end_seconds"],
            )
            break

        logger.info("[yt_extractor] Returning %d clip(s) for session", len(result))
        return result

    except Exception as exc:
        logger.warning("[yt_extractor] enrich_steps_with_videos failed: %s", exc)
        return []
