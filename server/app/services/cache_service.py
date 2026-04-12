import redis
import hashlib
import json

from app.core.config import settings
from app.core.logger import get_logger

logger = get_logger(__name__)

_redis_client: redis.Redis | None = None


def _get_client() -> redis.Redis:
    global _redis_client
    if _redis_client is None:
        _redis_client = redis.Redis.from_url(settings.REDIS_URL, decode_responses=True)
    return _redis_client


def _normalize(text: str) -> str:
    return text.lower().strip()


def _make_key(page_id: str, question: str) -> str:
    raw = f"{page_id}:{_normalize(question)}"
    return hashlib.md5(raw.encode()).hexdigest()


def get_cache(page_id: str, question: str) -> dict | None:
    try:
        key = _make_key(page_id, question)
        data = _get_client().get(key)
        if data:
            logger.info("Cache HIT for page_id=%s", page_id)
            return json.loads(data)
    except redis.RedisError as exc:
        logger.warning("Redis get failed: %s", exc)
    return None


def set_cache(page_id: str, question: str, value: dict) -> None:
    try:
        key = _make_key(page_id, question)
        _get_client().set(key, json.dumps(value), ex=60 * 60 * 24 * 30)  # 30 days
        logger.info("Cache SET for page_id=%s", page_id)
    except redis.RedisError as exc:
        logger.warning("Redis set failed: %s", exc)
