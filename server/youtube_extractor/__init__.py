import asyncio
import numpy as np
from app.core.logger import get_logger
from .searcher import search_videos
from .transcript import fetch_transcript
from .indexer import index_video, is_video_indexed
from .retriever import find_best_segment
from .indexer import embed_texts
logger = get_logger(__name__)

_MIN_SCORE = 0.85
_HIGH_SCORE = 0.92
_MIN_GAP_SECONDS = 60
_YT_WATCH_BASE_URL = "https://www.youtube.com/watch?v="

# 🔥 In-memory embedding cache (replace with Redis if needed)
_embedding_cache = {}


# ================================
# 🧠 Embedding + Similarity
# ================================

def cosine_sim(a, b):
    return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b))


async def get_embedding_cached(text: str):
    key = hash(text)
    if key in _embedding_cache:
        return _embedding_cache[key]

    emb = await embed_text(text)
    _embedding_cache[key] = emb
    return emb


def build_video_text(video: dict):
    title = video.get("title", "")
    desc = (video.get("description") or "")[:500]
    return f"{title}. {desc}"


# ================================
# 🎯 Video Filtering + Scoring
# ================================

def is_good_video(video):
    title = video.get("title", "").lower()

    bad_keywords = ["lecture", "full course", "revision", "live class"]
    if any(k in title for k in bad_keywords):
        return False

    return True


def boost_score(video, sim_score):
    score = sim_score
    title = video.get("title", "").lower()
    channel = (video.get("channel") or "").lower()
    duration = video.get("duration_seconds", 0)

    if "animation" in title:
        score += 0.05

    if "explained" in title:
        score += 0.03

    if "visual" in title:
        score += 0.02

    if channel in ["kurzgesagt", "amoeba sisters", "fuseschool"]:
        score += 0.08

    if 60 < duration < 400:
        score += 0.04

    return score


async def rerank_videos_multiquery(videos, query_core):
    """
    Multi-query semantic reranking using embeddings.
    Uses your existing embed_texts (batch optimized).
    """

    if not videos:
        return videos

    # 🔥 Multi-query expansion
    queries = [
        query_core,
        f"{query_core} animation",
        f"{query_core} simple explanation",
        f"{query_core} visual explanation"
    ]

    # Prepare video texts
    video_texts = [
        f"{v.get('title','')}. {(v.get('description') or '')[:400]}"
        for v in videos
    ]

    # 🔥 Batch embed ALL (queries + videos in ONE call)
    embeddings = await embed_texts(queries + video_texts)

    query_embs = embeddings[:len(queries)]
    video_embs = embeddings[len(queries):]

    def cosine(a, b):
        return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

    scored = []

    for v, v_emb in zip(videos, video_embs):
        # 🔥 Multi-query similarity (average)
        sims = [cosine(q_emb, v_emb) for q_emb in query_embs]
        sim_score = sum(sims) / len(sims)

        # 🔥 Boosting (very important)
        title = v.get("title", "").lower()
        channel = (v.get("channel") or "").lower()
        duration = v.get("duration_seconds", 0)

        score = sim_score

        if "animation" in title:
            score += 0.05
        if "explained" in title:
            score += 0.03
        if "visual" in title:
            score += 0.02

        if channel in ["kurzgesagt", "amoeba sisters", "fuseschool"]:
            score += 0.08

        if 60 < duration < 400:
            score += 0.04

        scored.append((v, score))

    scored.sort(key=lambda x: x[1], reverse=True)

    return [v for v, _ in scored]

# ================================
# 🎥 Clip Helpers
# ================================

def normalize_clip(clip: dict):
    start = int(clip.get("start_seconds", 0))
    clip["start_seconds"] = start
    clip["end_seconds"] = start + 30  # force 30s clip
    return clip


def _decorate_clip(clip: dict, video_lookup: dict):
    video_meta = video_lookup.get(clip["video_id"], {})
    start_seconds = int(clip.get("start_seconds", 0))
    end_seconds = int(clip.get("end_seconds", 0))

    enriched = dict(clip)
    enriched["watch_url"] = video_meta.get("watch_url") or f"{_YT_WATCH_BASE_URL}{clip['video_id']}"
    enriched["start_url"] = f"{enriched['watch_url']}&t={start_seconds}s"
    enriched["clip_duration_seconds"] = max(0, end_seconds - start_seconds)
    enriched["confidence"] = clip.get("score", 0)

    if video_meta.get("channel"):
        enriched["channel"] = video_meta["channel"]

    return enriched


async def _process_video(video: dict) -> bool:
    video_id = video["video_id"]

    if await is_video_indexed(video_id):
        logger.debug("[yt] cache hit %s", video_id)
        return True

    transcript = await fetch_transcript(video_id)
    if not transcript:
        return False

    return await index_video(video_id, video["title"], transcript)


# ================================
# 🚀 MAIN FUNCTION
# ================================

async def enrich_steps_with_videos(
    question: str,
    plan: dict,
    video_search_query: str = "",
    preferred_channels=None,
    session_theme: str = "",
):
    try:
        # =====================
        # 🧠 Query building
        # =====================
        theme = (session_theme or plan.get("question_focus") or question).strip()
        query_core = (video_search_query or theme or question).strip()

        intent = "animation visual simple explanation for kids"

        base_query = f"{query_core} {intent}".strip()[:220]

        logger.info("[yt] search query: %s", base_query)

        # =====================
        # 🔍 Search
        # =====================
        videos = await search_videos(base_query, max_results=10)
        if not videos:
            return []

        # =====================
        # 🔥 Filter BAD videos FIRST
        # =====================
        videos = [v for v in videos if is_good_video(v)]

        # =====================
        # 🔥 MULTI-QUERY RERANK (MAIN UPGRADE)
        # =====================
        videos = await rerank_videos_multiquery(videos, query_core)

        # Take top 4 best videos
        videos = videos[:4]

        video_lookup = {v["video_id"]: v for v in videos}

        # =====================
        # ⚡ Index transcripts (parallel)
        # =====================
        await asyncio.gather(
            *[_process_video(v) for v in videos],
            return_exceptions=True
        )

        video_ids = [v["video_id"] for v in videos]

        # =====================
        # 🔥 Multi-query segment retrieval
        # =====================
        queries = [
            query_core,
            f"{query_core} explanation",
            f"{query_core} animation"
        ]

        raw_clips = await asyncio.gather(
            *[find_best_segment(q, video_ids) for q in queries],
            return_exceptions=True
        )

        # =====================
        # 🧠 Deduplicate + filter
        # =====================
        seen = set()
        candidates = []

        for clip in raw_clips:
            if not isinstance(clip, dict):
                continue

            if clip.get("score", 0) < _MIN_SCORE:
                continue

            uid = f"{clip['video_id']}_{clip['start_seconds']}"
            if uid in seen:
                continue

            seen.add(uid)

            clip = normalize_clip(clip)
            candidates.append(clip)

        candidates.sort(key=lambda c: c["score"], reverse=True)

        if not candidates:
            return []

        # =====================
        # 🎯 Pick best clips
        # =====================
        result = []

        best = candidates[0]
        result.append(_decorate_clip(best, video_lookup))

        for clip in candidates[1:]:
            if clip["score"] < _HIGH_SCORE:
                break

            gap = abs(clip["start_seconds"] - best["start_seconds"])

            if clip["video_id"] == best["video_id"] and gap < _MIN_GAP_SECONDS:
                continue

            result.append(_decorate_clip(clip, video_lookup))
            break

        logger.info("[yt] returning %d clips", len(result))
        return result

    except Exception as e:
        logger.warning("[yt] failed: %s", e)
        return []